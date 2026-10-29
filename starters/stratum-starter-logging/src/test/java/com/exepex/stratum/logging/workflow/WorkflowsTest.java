package com.exepex.stratum.logging.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.exepex.stratum.logging.LoggingConstants;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("Workflows puts workflow fields on log lines and times and traces the work")
class WorkflowsTest {

  /** The outer workflow's name. */
  private static final String CHECKOUT = "ORDER_CHECKOUT";

  /** The inner workflow's name. */
  private static final String PAYMENT = "PAYMENT";

  /** An order number used as the workflow's ID. */
  private static final String ORDER_NUMBER = "ORD-1042";

  /** An ID with a line break, which could forge a log line. */
  private static final String FORGING_ID = "ORD-1\n{\"level\":\"ERROR\"}";

  /** The observations finished by the registry, in order. */
  private final List<Observation.Context> finishedObservations = new ArrayList<>();

  /** Runs workflows with no observability. */
  private final Workflows workflowsWithoutObservability = new Workflows(ObservationRegistry.NOOP);

  /** The failure of the work in the tests where it throws. */
  private final IllegalStateException paymentDeclined =
      new IllegalStateException("payment declined");

  /** The workflow fields seen by the inner and then the outer workflow. */
  private final List<List<String>> fieldsSeen = new ArrayList<>();

  /** Clears the logging context, so no test sees fields left by another. */
  @AfterEach
  void clearLoggingContext() {
    MDC.clear();
  }

  /** Returns workflows whose registry keeps every finished observation. */
  private Workflows observedWorkflows() {
    var observationRegistry = ObservationRegistry.create();
    observationRegistry.observationConfig().observationHandler(new FinishedObservations());
    return new Workflows(observationRegistry);
  }

  /** Returns {@code workflow}, {@code root_workflow} and {@code workflow_id}, as they are now. */
  private List<String> workflowFields() {
    return Arrays.asList(
        MDC.get(LoggingConstants.WORKFLOW),
        MDC.get(LoggingConstants.ROOT_WORKFLOW),
        MDC.get(LoggingConstants.WORKFLOW_ID));
  }

  /** Work that fails. */
  private void declinePayment() {
    throw paymentDeclined;
  }

  /** Work that does nothing. */
  private void placeOrder() {
    // Only the workflow around it matters.
  }

  /** Runs the payment workflow, then notes the fields both inside and after it. */
  private void runPaymentThenNoteFields() {
    workflowsWithoutObservability.run(PAYMENT, () -> fieldsSeen.add(workflowFields()));
    fieldsSeen.add(workflowFields());
  }

  @Test
  @DisplayName("the fields are set while the work runs and removed when it ends")
  void setsFieldsWhileWorkRuns() {
    var fieldsDuringWork =
        workflowsWithoutObservability.call(CHECKOUT, ORDER_NUMBER, this::workflowFields);

    assertThat(fieldsDuringWork).containsExactly(CHECKOUT, CHECKOUT, ORDER_NUMBER);
    assertThat(workflowFields()).containsOnlyNulls();
  }

  @Test
  @DisplayName("an inner workflow keeps the root and the ID, and the outer one is restored after")
  void innerWorkflowKeepsRootAndId() {
    workflowsWithoutObservability.run(CHECKOUT, ORDER_NUMBER, this::runPaymentThenNoteFields);

    assertThat(fieldsSeen)
        .containsExactly(
            List.of(PAYMENT, CHECKOUT, ORDER_NUMBER), List.of(CHECKOUT, CHECKOUT, ORDER_NUMBER));
  }

  @Test
  @DisplayName("an ID that could forge a log line is left out, not replaced by the outer ID")
  void leavesOutUnsafeId() {
    var innerFields =
        workflowsWithoutObservability.call(
            CHECKOUT,
            ORDER_NUMBER,
            () -> workflowsWithoutObservability.call(PAYMENT, FORGING_ID, this::workflowFields));

    assertThat(innerFields).containsExactly(PAYMENT, CHECKOUT, null);
  }

  @Test
  @DisplayName("when the work throws, the exception passes unchanged and the fields are removed")
  void restoresFieldsWhenWorkThrows() {
    assertThatIllegalStateException()
        .isThrownBy(() -> workflowsWithoutObservability.run(CHECKOUT, this::declinePayment))
        .isSameAs(paymentDeclined);
    assertThat(workflowFields()).containsOnlyNulls();
  }

  @Test
  @DisplayName("a name that is not 1 to 128 safe characters is refused")
  void refusesUnsafeName() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> workflowsWithoutObservability.run("order checkout", this::placeOrder));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> workflowsWithoutObservability.run("", this::placeOrder));
  }

  @Test
  @DisplayName("with a registry, each workflow is one observation tagged with its names")
  void observesEachWorkflow() {
    var workflows = observedWorkflows();

    workflows.run(CHECKOUT, ORDER_NUMBER, () -> workflows.run(PAYMENT, this::placeOrder));

    assertThat(finishedObservations)
        .extracting(Observation.Context::getContextualName)
        .containsExactly(PAYMENT, CHECKOUT);
    var payment = finishedObservations.getFirst();
    assertThat(payment.getName()).isEqualTo(LoggingConstants.WORKFLOW_OBSERVATION);
    assertThat(payment.getLowCardinalityKeyValues())
        .containsExactlyInAnyOrder(
            KeyValue.of(LoggingConstants.WORKFLOW, PAYMENT),
            KeyValue.of(LoggingConstants.ROOT_WORKFLOW, CHECKOUT));
    assertThat(finishedObservations)
        .extracting(Observation.Context::getHighCardinalityKeyValues)
        .allSatisfy(
            idTags ->
                assertThat(idTags)
                    .containsExactly(KeyValue.of(LoggingConstants.WORKFLOW_ID, ORDER_NUMBER)));
  }

  @Test
  @DisplayName("with a registry, a failure is recorded on the workflow's observation")
  void recordsFailure() {
    assertThatIllegalStateException()
        .isThrownBy(() -> observedWorkflows().run(CHECKOUT, this::declinePayment));
    assertThat(finishedObservations.getFirst().getError()).isSameAs(paymentDeclined);
  }

  /** Keeps every observation the registry finishes. */
  private final class FinishedObservations implements ObservationHandler<Observation.Context> {

    /**
     * Keeps the finished observation.
     *
     * @param context the observation's context
     */
    @Override
    public void onStop(Observation.Context context) {
      finishedObservations.add(context);
    }

    /**
     * Handles every observation.
     *
     * @param context the observation's context
     * @return always {@code true}
     */
    @Override
    public boolean supportsContext(Observation.Context context) {
      return true;
    }
  }
}
