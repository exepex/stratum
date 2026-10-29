package com.exepex.stratum.core.testsupport;

import com.exepex.stratum.core.error.LoggingContextKeys;
import lombok.experimental.UtilityClass;
import org.slf4j.MDC;

/** Sample trace, span and correlation IDs. A request has these IDs whether it succeeds or fails. */
@UtilityClass
public class TracingTestData {

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
    MDC.put(LoggingContextKeys.TRACE_ID, TRACE_ID);
    MDC.put(LoggingContextKeys.SPAN_ID, SPAN_ID);
    MDC.put(LoggingContextKeys.CORRELATION_ID, CORRELATION_ID);
  }
}
