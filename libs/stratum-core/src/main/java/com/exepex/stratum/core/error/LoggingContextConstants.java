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
   * What a usable trace, span or correlation ID looks like: 1 to 128 letters, digits, {@code -},
   * {@code _}, {@code .} or {@code :}. Anything else, such as a line break, could forge a log line
   * or break a response, so Stratum never uses it.
   */
  public static final Pattern USABLE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
}
