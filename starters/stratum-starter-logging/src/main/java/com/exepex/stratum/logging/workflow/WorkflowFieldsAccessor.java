package com.exepex.stratum.logging.workflow;

import com.exepex.stratum.logging.LoggingConstants;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import java.util.Map;
import java.util.Objects;

/**
 * Reads and writes the workflow fields of the logging context, so that Spring's executors and
 * Reactor carry them to the threads the work moves to. Registered with Micrometer's context
 * propagation through {@code META-INF/services}.
 */
public class WorkflowFieldsAccessor extends Slf4jThreadLocalAccessor {

  /** Creates the accessor for {@code workflow}, {@code root_workflow} and {@code workflow_id}. */
  public WorkflowFieldsAccessor() {
    super(LoggingConstants.WORKFLOW, LoggingConstants.ROOT_WORKFLOW, LoggingConstants.WORKFLOW_ID);
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
   * Returns the accessor's own key, so it does not replace a service's own MDC accessor.
   *
   * @return {@value LoggingConstants#WORKFLOW_FIELDS}
   */
  @Override
  public Object key() {
    return LoggingConstants.WORKFLOW_FIELDS;
  }
}
