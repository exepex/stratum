package com.exepex.stratum.logging.workflow;

import com.exepex.stratum.logging.LoggingConstants;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.MDC;

/**
 * Reads and writes the workflow fields of the logging context, so that Spring's executors and
 * Reactor carry them to the threads the work moves to. Registered with Micrometer's context
 * propagation through {@code META-INF/services}.
 *
 * <p>Reactor calls it around every operator. It writes a field only when its value changes, because
 * every MDC write makes Logback copy the whole MDC the next time it is read, by each log line and
 * by the service fields accessor.
 */
public class WorkflowFieldsAccessor extends Slf4jThreadLocalAccessor {

  /** The MDC fields this accessor carries. */
  private static final List<String> WORKFLOW_FIELD_NAMES =
      List.of(
          LoggingConstants.WORKFLOW, LoggingConstants.ROOT_WORKFLOW, LoggingConstants.WORKFLOW_ID);

  /** Creates the accessor for {@code workflow}, {@code root_workflow} and {@code workflow_id}. */
  public WorkflowFieldsAccessor() {
    super(WORKFLOW_FIELD_NAMES.toArray(String[]::new));
  }

  /**
   * Returns the workflow fields of the logging context; a field that is not set maps to {@code
   * null}.
   *
   * @return the fields
   */
  Map<String, String> currentFields() {
    // Null only from an accessor for the whole logging context, which this one is not.
    return Objects.requireNonNull(getValue());
  }

  /**
   * Sets the workflow fields to the carried values, writing only those that differ.
   *
   * @param carriedFields the workflow fields of the work that moved to this thread
   */
  @Override
  public void setValue(Map<String, String> carriedFields) {
    for (var field : WORKFLOW_FIELD_NAMES) {
      var carriedValue = carriedFields.get(field);
      if (!Objects.equals(MDC.get(field), carriedValue)) {
        if (carriedValue == null) {
          MDC.remove(field);
        } else {
          MDC.put(field, carriedValue);
        }
      }
    }
  }

  /** Removes the workflow fields that are set, leaving the MDC untouched when none is. */
  @Override
  public void setValue() {
    for (var field : WORKFLOW_FIELD_NAMES) {
      if (MDC.get(field) != null) {
        MDC.remove(field);
      }
    }
  }

  /**
   * Returns the accessor's own key, so it does not replace a service's own MDC accessor.
   *
   * @return {@value LoggingConstants#WORKFLOW_FIELDS}
   */
  @Override
  public Object key() {
    return LoggingConstants.WORKFLOW_FIELDS;
  }
}
