package com.exepex.stratum.core.testsupport;

import lombok.experimental.UtilityClass;
import org.slf4j.MDC;

/**
 * Sample trace, span and correlation IDs, put in the logging context under the keys that tracing
 * and Stratum write. The keys are spelled out here, so a test fails if Stratum's constants change.
 */
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
    MDC.put("traceId", TRACE_ID);
    MDC.put("spanId", SPAN_ID);
    MDC.put("correlationId", CORRELATION_ID);
  }
}
