package com.exepex.stratum.test;

import lombok.experimental.UtilityClass;
import org.slf4j.MDC;

/**
 * Sample trace, span and correlation IDs, and the logging-context keys that tracing and Stratum
 * store them under. Use them to test code that reads the IDs, such as Stratum's error reports.
 */
@UtilityClass
public class LoggingContextIds {

  /** Where tracing puts the trace ID in the logging context. */
  public static final String TRACE_ID_KEY = "traceId";

  /** Where tracing puts the span ID in the logging context. */
  public static final String SPAN_ID_KEY = "spanId";

  /** Where Stratum puts the correlation ID in the logging context. */
  public static final String CORRELATION_ID_KEY = "correlationId";

  /** A trace ID, as tracing writes it. */
  public static final String TRACE_ID = "4bf92f3577b34da6";

  /** A span ID, as tracing writes it. */
  public static final String SPAN_ID = "00f067aa0ba902b7";

  /** A correlation ID for one order's workflow. */
  public static final String CORRELATION_ID = "order-42";

  /**
   * Puts {@link #TRACE_ID}, {@link #SPAN_ID} and {@link #CORRELATION_ID} in the current thread's
   * logging context (SLF4J's MDC), as tracing and Stratum do for a request. Clear it with {@code
   * MDC.clear()} after the test, so the next test starts without IDs.
   */
  public static void putInLoggingContext() {
    MDC.put(TRACE_ID_KEY, TRACE_ID);
    MDC.put(SPAN_ID_KEY, SPAN_ID);
    MDC.put(CORRELATION_ID_KEY, CORRELATION_ID);
  }
}
