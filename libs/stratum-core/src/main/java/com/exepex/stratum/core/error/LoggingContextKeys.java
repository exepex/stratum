package com.exepex.stratum.core.error;

import lombok.experimental.UtilityClass;

/**
 * The keys under which tracing and Stratum put request IDs in the logging context (SLF4J's MDC).
 */
@UtilityClass
public class LoggingContextKeys {

  /** The trace ID of the request, set by tracing. */
  public static final String TRACE_ID = "traceId";

  /** The span ID of the operation, set by tracing. */
  public static final String SPAN_ID = "spanId";

  /** The ID that ties the steps of one business workflow together, set by Stratum. */
  public static final String CORRELATION_ID = "correlationId";
}
