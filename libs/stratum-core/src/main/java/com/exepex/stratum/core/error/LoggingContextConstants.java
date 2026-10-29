package com.exepex.stratum.core.error;

import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * The keys under which tracing and Stratum put request IDs in the logging context (SLF4J's MDC).
 */
@UtilityClass
public class LoggingContextConstants {

  /** The trace ID of the request, set by tracing. */
  public static final String TRACE_ID = "traceId";

  /** The span ID of the operation, set by tracing. */
  public static final String SPAN_ID = "spanId";

  /** The ID that ties the steps of one business workflow together, set by Stratum. */
  public static final String CORRELATION_ID = "correlationId";

  /**
   * The format a trace, span or correlation ID must have before Stratum writes it to a log line or
   * an error response: 1 to 128 letters, digits, {@code -}, {@code _}, {@code .} or {@code :}. An
   * ID in any other format, such as one with a line break sent by a caller, could forge a log line
   * or break a response, so Stratum leaves it out.
   */
  public static final Pattern ID_FORMAT_SAFE_FOR_LOGS = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
}
