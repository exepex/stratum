package com.exepex.stratum.logging.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.logging.LoggingConstants;
import com.exepex.stratum.logging.testsupport.FinishedObservations;
import io.micrometer.observation.Observation;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;
import reactor.core.scheduler.Schedulers;

@DisplayName("ReactiveWorkflows runs each subscription of a Mono or Flux as a workflow")
class ReactiveWorkflowsTest {

  /** The outer workflow's name. */
  private static final String CHECKOUT = "ORDER_CHECKOUT";

  /** The inner workflow's name. */
  private static final String PAYMENT = "PAYMENT";

  /** An order number used as the workflow's ID. */
  private static final String ORDER_NUMBER = "ORD-1042";

  /** The registry, which keeps the observations it finishes. */
  private final FinishedObservations observations = new FinishedObservations();

  /** Runs the workflows of work that is not reactive. */
  private final Workflows workflows = new Workflows(observations.getRegistry());

  /** Runs the reactive workflows. */
  private final ReactiveWorkflows reactiveWorkflows = new ReactiveWorkflows(workflows);

  /** The failure of the work in the tests where it fails. */
  private final IllegalStateException paymentDeclined =
      new IllegalStateException("payment declined");

  /** Clears the logging context, so no test sees fields left by another. */
  @AfterEach
  void clearLoggingContext() {
    MDC.clear();
  }

  /** Returns {@code workflow}, {@code root_workflow} and {@code workflow_id}, as they are now. */
  private List<String> workflowFields() {
    return Arrays.asList(
        MDC.get(LoggingConstants.WORKFLOW),
        MDC.get(LoggingConstants.ROOT_WORKFLOW),
        MDC.get(LoggingConstants.WORKFLOW_ID));
  }

  /** Emits the workflow fields seen by a {@code handle} operator, which Reactor gives them to. */
  private void emitWorkflowFields(Object ignoredValue, SynchronousSink<List<String>> sink) {
    sink.next(workflowFields());
  }

  /** Returns a payment that sees its workflow fields on another thread. */
  private Mono<List<String>> paymentOnAnotherThread() {
    return Mono.just(PAYMENT)
        .publishOn(Schedulers.boundedElastic())
        .handle(this::emitWorkflowFields);
  }

  @Test
  @DisplayName("nothing runs until subscribed, and each subscription is one run with the fields")
  void eachSubscriptionIsOneRun() {
    var checkout = reactiveWorkflows.mono(CHECKOUT, ORDER_NUMBER, paymentOnAnotherThread());
    assertThat(observations.getFinished()).isEmpty();

    assertThat(checkout.block()).containsExactly(CHECKOUT, CHECKOUT, ORDER_NUMBER);
    assertThat(checkout.block()).containsExactly(CHECKOUT, CHECKOUT, ORDER_NUMBER);

    await().untilAsserted(() -> assertThat(observations.getFinished()).hasSize(2));
    assertThat(workflowFields()).containsOnlyNulls();
  }

  @Test
  @DisplayName(
      "an inner workflow keeps the outer root and ID, and its span is inside the outer one")
  void innerWorkflowKeepsRootAndId() {
    var payment = reactiveWorkflows.flux(PAYMENT, null, paymentOnAnotherThread().flux());

    var paymentFields = reactiveWorkflows.mono(CHECKOUT, ORDER_NUMBER, payment.next()).block();

    assertThat(paymentFields).containsExactly(PAYMENT, CHECKOUT, ORDER_NUMBER);
    await().untilAsserted(() -> assertThat(observations.getFinished()).hasSize(2));
    var paymentObservation = observationNamed(PAYMENT);
    assertThat(paymentObservation.getParentObservation())
        .extracting(parent -> parent.getContextView().getContextualName())
        .isEqualTo(CHECKOUT);
  }

  @Test
  @DisplayName("a run subscribed inside a workflow that is not reactive keeps its root and ID")
  void runInsideImperativeWorkflowKeepsRootAndId() {
    var paymentFields =
        workflows.call(
            CHECKOUT,
            ORDER_NUMBER,
            () -> reactiveWorkflows.mono(PAYMENT, null, paymentOnAnotherThread()).block());

    assertThat(paymentFields).containsExactly(PAYMENT, CHECKOUT, ORDER_NUMBER);
  }

  @Test
  @DisplayName("a failed run records its failure, and a cancelled run still ends")
  void failedAndCancelledRunsEnd() {
    var failedPayment = reactiveWorkflows.mono(PAYMENT, null, Mono.error(paymentDeclined));
    assertThatIllegalStateException().isThrownBy(failedPayment::block);
    reactiveWorkflows.flux(CHECKOUT, null, Flux.never()).subscribe().dispose();

    await().untilAsserted(() -> assertThat(observations.getFinished()).hasSize(2));
    assertThat(observationNamed(PAYMENT).getError()).isSameAs(paymentDeclined);
    assertThat(observationNamed(CHECKOUT).getError()).isNull();
  }

  @Test
  @DisplayName("a name that is not 1 to 128 safe characters is refused when the work is built")
  void refusesUnsafeName() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> reactiveWorkflows.mono("order checkout", null, Mono.empty()));
  }

  /** Returns the finished observation of the named workflow. */
  private Observation.Context observationNamed(String name) {
    return observations.getFinished().stream()
        .filter(finished -> name.equals(finished.getContextualName()))
        .findFirst()
        .orElseThrow();
  }
}
