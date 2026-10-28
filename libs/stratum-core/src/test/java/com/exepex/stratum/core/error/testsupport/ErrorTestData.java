package com.exepex.stratum.core.error.testsupport;

import com.exepex.stratum.core.error.ErrorDetail;
import lombok.experimental.UtilityClass;
import org.slf4j.MDC;

/** Values shared by the error tests: an item and a set of diagnostic IDs. */
@UtilityClass
public class ErrorTestData {

  /** Where tracing puts the trace ID in the logging context. */
  public static final String TRACE_ID_KEY = "traceId";

  /** Where tracing puts the span ID in the logging context. */
  public static final String SPAN_ID_KEY = "spanId";

  /** Where Stratum puts the correlation ID in the logging context. */
  public static final String CORRELATION_ID_KEY = "correlationId";

  /** The length of the longest ID that an error report keeps. */
  public static final int LONGEST_ID_LENGTH = 128;

  /** A problem with one field of an order. */
  public static final ErrorDetail QUANTITY_PROBLEM =
      new ErrorDetail("quantity", "must be positive");

  /** The item the test orders ask for. */
  public static final String SKU = "SKU-1";

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
