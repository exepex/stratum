package com.exepex.stratum.logging.workflow;

import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.logging.LoggingConstants;
import io.micrometer.observation.Observation;
import io.micrometer.observation.Observation.CheckedCallable;
import io.micrometer.observation.Observation.CheckedRunnable;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * Runs a piece of work as a named workflow. While it runs, every log line it writes carries {@code
 * workflow}, {@code root_workflow} (the outermost workflow) and, when given, {@code workflow_id}.
 * When the service has an {@code ObservationRegistry}, as with the observability starter, the work
 * is also timed and traced as one span. Use it where {@link Workflow @Workflow} does not apply.
 */
@RequiredArgsConstructor
public class Workflows {

  /** Times and traces each workflow; {@link ObservationRegistry#NOOP} without observability. */
  private final ObservationRegistry observationRegistry;

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
    var restoreOuterWorkflow = enter(name, workflowId);
    try {
      observation(name).observeChecked(work);
    } finally {
      restoreOuterWorkflow.run();
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
   * result.
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
    var restoreOuterWorkflow = enter(name, workflowId);
    try {
      return observation(name).observeChecked(work);
    } finally {
      restoreOuterWorkflow.run();
    }
  }

  /**
   * Puts the workflow's fields in the logging context and returns what puts back the outer
   * workflow's fields when this one ends.
   */
  private Runnable enter(String name, @Nullable String workflowId) {
    if (!isSafeForLogs(name)) {
      throw new IllegalArgumentException(
          "A workflow name must be 1 to 128 letters, digits, '-', '_', '.' or ':'");
    }
    // Read before anything is put, so the outer workflow's fields can be put back.
    final var outerWorkflow = MDC.get(LoggingConstants.WORKFLOW);
    final var outerRootWorkflow = MDC.get(LoggingConstants.ROOT_WORKFLOW);
    final var outerWorkflowId = MDC.get(LoggingConstants.WORKFLOW_ID);
    MDC.put(LoggingConstants.WORKFLOW, name);
    if (outerRootWorkflow == null) {
      MDC.put(LoggingConstants.ROOT_WORKFLOW, name);
    }
    if (workflowId != null) {
      // An unsafe ID is left out rather than letting the outer workflow's ID stand in for it.
      putOrRemove(LoggingConstants.WORKFLOW_ID, isSafeForLogs(workflowId) ? workflowId : null);
    }
    return () -> restore(outerWorkflow, outerRootWorkflow, outerWorkflowId);
  }

  /** Puts the outer workflow's fields back, removing those it did not have. */
  private void restore(
      @Nullable String outerWorkflow,
      @Nullable String outerRootWorkflow,
      @Nullable String outerWorkflowId) {
    putOrRemove(LoggingConstants.WORKFLOW, outerWorkflow);
    putOrRemove(LoggingConstants.ROOT_WORKFLOW, outerRootWorkflow);
    putOrRemove(LoggingConstants.WORKFLOW_ID, outerWorkflowId);
  }

  /** Sets a logging context field, or removes it when the value is {@code null}. */
  private void putOrRemove(String key, @Nullable String value) {
    if (value == null) {
      MDC.remove(key);
    } else {
      MDC.put(key, value);
    }
  }

  /**
   * Returns the workflow's timer and span, tagged with its name and the outermost workflow's name.
   * The ID, its own or the one it inherits, goes on the span only, because each ID value would make
   * a new metric series. Called after {@link #enter}, so the logging context holds the effective
   * values.
   */
  private Observation observation(String name) {
    var rootWorkflow = Objects.requireNonNullElse(MDC.get(LoggingConstants.ROOT_WORKFLOW), name);
    var workflowId = MDC.get(LoggingConstants.WORKFLOW_ID);
    var observation =
        Observation.createNotStarted(LoggingConstants.WORKFLOW_OBSERVATION, observationRegistry)
            .contextualName(name)
            .lowCardinalityKeyValue(LoggingConstants.WORKFLOW, name)
            .lowCardinalityKeyValue(LoggingConstants.ROOT_WORKFLOW, rootWorkflow);
    return workflowId == null
        ? observation
        : observation.highCardinalityKeyValue(LoggingConstants.WORKFLOW_ID, workflowId);
  }

  /** Returns whether a name or ID can go in a log line as it is. */
  private boolean isSafeForLogs(String nameOrId) {
    return LoggingContextConstants.ID_FORMAT_SAFE_FOR_LOGS.matcher(nameOrId).matches();
  }
}
