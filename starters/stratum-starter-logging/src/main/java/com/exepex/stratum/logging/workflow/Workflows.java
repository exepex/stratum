package com.exepex.stratum.logging.workflow;

import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.logging.LoggingConstants;
import io.micrometer.observation.Observation;
import io.micrometer.observation.Observation.CheckedCallable;
import io.micrometer.observation.Observation.CheckedRunnable;
import io.micrometer.observation.ObservationRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Runs a piece of work as a named workflow. While it runs, every log line it writes carries {@code
 * workflow}, {@code root_workflow} (the outermost workflow) and, when given, {@code workflow_id}.
 * When the service has an {@code ObservationRegistry}, as with the observability starter, the work
 * is also timed and traced as one span. Use it where {@link Workflow @Workflow} does not apply, and
 * {@link ReactiveWorkflows} for a {@code Mono} or {@code Flux}.
 */
@RequiredArgsConstructor
public class Workflows {

  /** Times and traces each workflow; {@link ObservationRegistry#NOOP} without observability. */
  private final ObservationRegistry observationRegistry;

  /** Reads and writes the workflow fields of the logging context. */
  private final WorkflowFieldsAccessor workflowFields = new WorkflowFieldsAccessor();

  /**
   * Runs the work as the named workflow.
   *
   * @param name the workflow's fixed name, such as {@code ORDER_CHECKOUT}: 1 to 128 letters,
   *     digits, {@code -}, {@code _}, {@code .} or {@code :}. Never put data in it.
   * @param work the work
   * @param <E> the exception the work may throw
   * @throws E what the work throws, unchanged
   * @throws IllegalArgumentException if the name has another form
   */
  public <E extends Throwable> void run(String name, CheckedRunnable<E> work) throws E {
    run(name, null, work);
  }

  /**
   * Runs the work as the named workflow, with an ID such as an order number.
   *
   * @param name the workflow's fixed name; see {@link #run(String, CheckedRunnable)}
   * @param workflowId the workflow's ID, or {@code null} for none. An ID that is not 1 to 128 safe
   *     characters is left out, so it cannot forge a log line.
   * @param work the work
   * @param <E> the exception the work may throw
   * @throws E what the work throws, unchanged
   * @throws IllegalArgumentException if the name has another form
   */
  public <E extends Throwable> void run(
      String name, @Nullable String workflowId, CheckedRunnable<E> work) throws E {
    var outerFields = enter(name, workflowId);
    var observation = observation(workflowFields.currentFields()).start();
    try {
      observation.scopedChecked(work);
    } finally {
      observation.stop();
      workflowFields.setValue(outerFields);
    }
  }

  /**
   * Runs the work as the named workflow and returns its result.
   *
   * @param name the workflow's fixed name; see {@link #run(String, CheckedRunnable)}
   * @param work the work
   * @param <T> the result's type
   * @param <E> the exception the work may throw
   * @return the work's result
   * @throws E what the work throws, unchanged
   * @throws IllegalArgumentException if the name has another form
   */
  public <T, E extends Throwable> @Nullable T call(String name, CheckedCallable<T, E> work)
      throws E {
    return call(name, null, work);
  }

  /**
   * Runs the work as the named workflow, with an ID such as an order number, and returns its
   * result. When the result is a {@code CompletableFuture} or another {@code CompletionStage}, the
   * workflow ends when it completes, and its failure is recorded.
   *
   * @param name the workflow's fixed name; see {@link #run(String, CheckedRunnable)}
   * @param workflowId the workflow's ID, or {@code null} for none; see {@link #run(String, String,
   *     CheckedRunnable)}
   * @param work the work
   * @param <T> the result's type
   * @param <E> the exception the work may throw
   * @return the work's result
   * @throws E what the work throws, unchanged
   * @throws IllegalArgumentException if the name has another form
   */
  public <T, E extends Throwable> @Nullable T call(
      String name, @Nullable String workflowId, CheckedCallable<T, E> work) throws E {
    var outerFields = enter(name, workflowId);
    var observation = observation(workflowFields.currentFields()).start();
    @Nullable T result = null;
    try {
      result = observation.scopedChecked(work);
      return result;
    } finally {
      // A failure leaves the result null; scopedChecked has recorded it.
      stopWhenDone(observation, result);
      workflowFields.setValue(outerFields);
    }
  }

  /**
   * Returns the fields of a workflow that runs inside the outer one: its own name, the outer root
   * or else its own name, and its own ID when it is given one, else the outer ID.
   *
   * @param name the workflow's name, already checked
   * @param workflowId the workflow's ID, or {@code null} for none
   * @param outerFields the outer workflow's fields, with no entries outside a workflow
   * @return the workflow's fields; an unsafe ID is left out rather than replaced by the outer one
   */
  Map<String, String> fieldsInside(
      String name, @Nullable String workflowId, Map<String, String> outerFields) {
    var fields = new HashMap<>(outerFields);
    fields.put(LoggingConstants.WORKFLOW, name);
    fields.putIfAbsent(LoggingConstants.ROOT_WORKFLOW, name);
    if (workflowId != null) {
      fields.put(LoggingConstants.WORKFLOW_ID, isSafeForLogs(workflowId) ? workflowId : null);
    }
    return fields;
  }

  /**
   * Returns the timer and span of a workflow with these fields, tagged with its name and the
   * outermost workflow's name. The ID goes on the span only, because each ID value would make a new
   * metric series.
   *
   * @param fields the workflow's fields
   * @return the observation, not started
   */
  Observation observation(Map<String, String> fields) {
    var name = fields.get(LoggingConstants.WORKFLOW);
    var workflowId = fields.get(LoggingConstants.WORKFLOW_ID);
    var observation =
        Observation.createNotStarted(LoggingConstants.WORKFLOW_OBSERVATION, observationRegistry)
            .contextualName(name)
            .lowCardinalityKeyValue(LoggingConstants.WORKFLOW, name)
            .lowCardinalityKeyValue(
                LoggingConstants.ROOT_WORKFLOW, fields.get(LoggingConstants.ROOT_WORKFLOW));
    return workflowId == null
        ? observation
        : observation.highCardinalityKeyValue(LoggingConstants.WORKFLOW_ID, workflowId);
  }

  /**
   * Refuses a workflow name that is not 1 to 128 safe characters.
   *
   * @param name the workflow's name
   * @throws IllegalArgumentException if the name has another form
   */
  void requireSafeName(String name) {
    if (!isSafeForLogs(name)) {
      throw new IllegalArgumentException(
          "A workflow name must be 1 to 128 letters, digits, '-', '_', '.' or ':'");
    }
  }

  /**
   * Puts the workflow's fields in the logging context and returns the outer workflow's fields, to
   * put back when this one ends.
   */
  private Map<String, String> enter(String name, @Nullable String workflowId) {
    requireSafeName(name);
    var outerFields = workflowFields.currentFields();
    workflowFields.setValue(fieldsInside(name, workflowId, outerFields));
    return outerFields;
  }

  /**
   * Stops the observation now, or when the result is a stage that has not completed yet. A stage's
   * failure arrives wrapped in a {@code CompletionException} when the stage depends on another.
   */
  private void stopWhenDone(Observation observation, @Nullable Object result) {
    if (result instanceof CompletionStage<?> stage) {
      stage.whenComplete((value, failure) -> stop(observation, unwrap(failure)));
    } else {
      observation.stop();
    }
  }

  /** Records the failure, if any, and stops the observation. */
  private void stop(Observation observation, @Nullable Throwable failure) {
    if (failure != null) {
      observation.error(failure);
    }
    observation.stop();
  }

  /** Returns the cause of a {@code CompletionException}, or the failure itself. */
  private @Nullable Throwable unwrap(@Nullable Throwable failure) {
    return failure instanceof CompletionException wrapped && wrapped.getCause() != null
        ? wrapped.getCause()
        : failure;
  }

  /** Returns whether a name or ID can go in a log line as it is. */
  private boolean isSafeForLogs(String nameOrId) {
    return LoggingContextConstants.ID_FORMAT_SAFE_FOR_LOGS.matcher(nameOrId).matches();
  }
}
