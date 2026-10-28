package com.exepex.stratum.core.testsupport;

import lombok.experimental.UtilityClass;
import org.slf4j.MDC;

/**
 * Sample trace, span and correlation IDs, and the logging-context keys that tracing and Stratum
 * store them under. A request has these IDs whether it succeeds or fails.
 */
@UtilityClass
public class TracingTestData {

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
   * logging context, as tracing and Stratum do. Clear it with {@code MDC.clear()} after the test.
   */
  public static void putIdsInLoggingContext() {
    MDC.put(TRACE_ID_KEY, TRACE_ID);
    MDC.put(SPAN_ID_KEY, SPAN_ID);
    MDC.put(CORRELATION_ID_KEY, CORRELATION_ID);
  }
}
