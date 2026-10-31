package com.exepex.stratum.logging.mdc;

import ch.qos.logback.classic.util.LogbackMDCAdapter;
import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.logging.LoggingConstants;
import io.micrometer.context.ThreadLocalAccessor;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.slf4j.spi.MDCAdapter;
import org.springframework.util.ClassUtils;

/**
 * Reads and writes the MDC fields the service put itself, such as {@code customerId}, so that
 * Spring's executors and Reactor carry them to the threads a request's work moves to, and put back
 * what each thread held before once the work there ends. The trace, span and correlation IDs and
 * the workflow fields are left to tracing and the workflow accessor, which keep them in step with
 * each span and workflow, and the request fields to the web module. Registered with Micrometer's
 * context propagation by {@link ServiceMdcFieldsPropagation} while the logging module is on.
 *
 * <p>Reactor calls it around every operator for every element, so it reads the MDC without copying
 * it when the backend is Logback, and writes only when the fields differ. Its methods use loops
 * rather than streams for the same reason.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class ServiceMdcFieldsAccessor implements ThreadLocalAccessor<Map<String, String>> {

  /**
   * The MDC fields that tracing, workflows and the web module set, which this accessor never copies
   * or removes.
   */
  static final Set<String> FIELDS_MANAGED_ELSEWHERE =
      Stream.concat(
              Stream.of(
                  LoggingContextConstants.TRACE_ID,
                  LoggingContextConstants.SPAN_ID,
                  LoggingContextConstants.CORRELATION_ID,
                  LoggingConstants.WORKFLOW,
                  LoggingConstants.ROOT_WORKFLOW,
                  LoggingConstants.WORKFLOW_ID),
              LoggingContextConstants.WEB_REQUEST_FIELDS.stream())
          .collect(Collectors.toUnmodifiableSet());

  /** Whether the MDC backend is Logback, Spring Boot's default, whose MDC can be read uncopied. */
  private static final boolean LOGBACK_MDC_PRESENT =
      ClassUtils.isPresent(
          "ch.qos.logback.classic.util.LogbackMDCAdapter", ClassUtils.getDefaultClassLoader());

  /**
   * The SLF4J backend's MDC, which this accessor reads and writes; given directly only by tests of
   * a backend other than Logback.
   */
  private final MDCAdapter mdcAdapter;

  /** Creates the accessor for the MDC of the SLF4J backend the service runs on. */
  public ServiceMdcFieldsAccessor() {
    this(MDC.getMDCAdapter());
  }

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
   * Returns the service's own MDC fields on this thread, never {@code null}.
   *
   * @return the fields, possibly none
   */
  Map<String, String> serviceFieldsOnThisThread() {
    var loggingContext = loggingContextOnThisThread();
    // Most threads hold no service fields, so count them before making a map.
    var serviceFieldCount = 0;
    for (var field : loggingContext.entrySet()) {
      if (isServiceField(field.getKey(), field.getValue())) {
        serviceFieldCount++;
      }
    }
    if (serviceFieldCount == 0) {
      return Map.of();
    }
    var serviceFields = HashMap.<String, String>newHashMap(serviceFieldCount);
    for (var field : loggingContext.entrySet()) {
      if (isServiceField(field.getKey(), field.getValue())) {
        serviceFields.put(field.getKey(), field.getValue());
      }
    }
    return serviceFields;
  }

  /**
   * Replaces the service's own MDC fields on this thread with the carried ones, unless they are
   * already the same.
   *
   * @param carriedFields the fields of the work that moved to this thread
   */
  @Override
  public void setValue(Map<String, String> carriedFields) {
    var loggingContext = loggingContextOnThisThread();
    if (sameServiceFields(loggingContext, carriedFields)) {
      return;
    }
    removeServiceFields(loggingContext);
    carriedFields.forEach(mdcAdapter::put);
  }

  /**
   * Removes the service's own MDC fields from this thread, keeping the trace and workflow fields.
   */
  @Override
  public void setValue() {
    removeServiceFields(loggingContextOnThisThread());
  }

  /** Returns whether the MDC field is one the service put itself. */
  private boolean isServiceField(String name, String value) {
    return value != null && !FIELDS_MANAGED_ELSEWHERE.contains(name);
  }

  /** Returns whether the MDC holds exactly the carried fields as its service fields. */
  private boolean sameServiceFields(
      Map<String, String> loggingContext, Map<String, String> carriedFields) {
    var serviceFieldCount = 0;
    for (var field : loggingContext.entrySet()) {
      if (isServiceField(field.getKey(), field.getValue())) {
        if (!field.getValue().equals(carriedFields.get(field.getKey()))) {
          return false;
        }
        serviceFieldCount++;
      }
    }
    return serviceFieldCount == carriedFields.size();
  }

  /** Removes from the MDC every service field that the given view of it holds. */
  private void removeServiceFields(Map<String, String> loggingContext) {
    for (var field : loggingContext.entrySet()) {
      if (isServiceField(field.getKey(), field.getValue())) {
        mdcAdapter.remove(field.getKey());
      }
    }
  }

  /**
   * Returns this thread's MDC. With Logback it is Logback's cached read-only copy, which later
   * changes to the MDC leave as it is, so it can be read while fields are removed; otherwise a new
   * copy.
   */
  private Map<String, String> loggingContextOnThisThread() {
    var loggingContext =
        LOGBACK_MDC_PRESENT && mdcAdapter instanceof LogbackMDCAdapter logbackMdc
            ? logbackMdc.getPropertyMap()
            : mdcAdapter.getCopyOfContextMap();
    return Objects.requireNonNullElse(loggingContext, Map.of());
  }
}
