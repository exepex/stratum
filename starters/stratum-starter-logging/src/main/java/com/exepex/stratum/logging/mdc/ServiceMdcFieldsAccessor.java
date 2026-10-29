package com.exepex.stratum.logging.mdc;

import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.logging.LoggingConstants;
import io.micrometer.context.ThreadLocalAccessor;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.MDC;

/**
 * Reads and writes the MDC fields the service put itself, such as {@code customerId}, so that
 * Spring's executors and Reactor carry them to the threads a request's work moves to, and put back
 * what each thread held before once the work there ends. The trace, span and correlation IDs and
 * the workflow fields are left to tracing and the workflow accessor, which keep them in step with
 * each span and workflow. Registered with Micrometer's context propagation through {@code
 * META-INF/services}.
 */
public class ServiceMdcFieldsAccessor implements ThreadLocalAccessor<Map<String, String>> {

  /** The MDC fields that tracing and workflows set, which this accessor never copies or removes. */
  static final Set<String> FIELDS_MANAGED_ELSEWHERE =
      Set.of(
          LoggingContextConstants.TRACE_ID,
          LoggingContextConstants.SPAN_ID,
          LoggingContextConstants.CORRELATION_ID,
          LoggingConstants.WORKFLOW,
          LoggingConstants.ROOT_WORKFLOW,
          LoggingConstants.WORKFLOW_ID);

  /**
   * Returns the key under which the fields are carried.
   *
   * @return {@value LoggingConstants#SERVICE_MDC_FIELDS}
   */
  @Override
  public Object key() {
    return LoggingConstants.SERVICE_MDC_FIELDS;
  }

  /**
   * Returns the service's own MDC fields on this thread. An empty map is carried too, so that work
   * from a thread with no fields never sees another request's fields where it runs.
   *
   * @return the fields, possibly none
   */
  @Override
  public Map<String, String> getValue() {
    return serviceFieldsOnThisThread();
  }

  /**
   * Replaces the service's own MDC fields on this thread with the carried ones.
   *
   * @param carriedFields the fields of the work that moved to this thread
   */
  @Override
  public void setValue(Map<String, String> carriedFields) {
    removeServiceFieldsOnThisThread();
    carriedFields.forEach(MDC::put);
  }

  /**
   * Removes the service's own MDC fields from this thread, keeping the trace and workflow fields.
   */
  @Override
  public void setValue() {
    removeServiceFieldsOnThisThread();
  }

  /** Returns the MDC fields on this thread that neither tracing nor workflows manage. */
  private Map<String, String> serviceFieldsOnThisThread() {
    return Optional.ofNullable(MDC.getCopyOfContextMap()).orElseGet(Map::of).entrySet().stream()
        .filter(field -> !FIELDS_MANAGED_ELSEWHERE.contains(field.getKey()))
        .filter(field -> Objects.nonNull(field.getValue()))
        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  /** Removes the MDC fields on this thread that neither tracing nor workflows manage. */
  private void removeServiceFieldsOnThisThread() {
    serviceFieldsOnThisThread().keySet().forEach(MDC::remove);
  }
}
