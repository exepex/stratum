package com.exepex.stratum.core.error;

import lombok.experimental.UtilityClass;

/** Names and limits shared by Stratum's error reports and its logging (ST-ENG-45). */
@UtilityClass
public class ErrorConstants {

  /** The start of every Stratum code, such as {@code SX-CORE-1001}. No service may use it. */
  public static final String STRATUM_CODE_PREFIX = "SX-";

  /** What separates the code from the message in logs and exception messages. */
  public static final String CODE_SEPARATOR = ": ";

  /** The package that every class allowed to declare a Stratum code lives in. */
  public static final String STRATUM_PACKAGE = "com.exepex.stratum.";

  /** Where tracing puts the current trace ID in the logging context (Micrometer Tracing). */
  public static final String TRACE_ID_KEY = "traceId";

  /** Where tracing puts the current span ID in the logging context (Micrometer Tracing). */
  public static final String SPAN_ID_KEY = "spanId";

  /** Where Stratum puts the current correlation ID in the logging context. */
  public static final String CORRELATION_ID_KEY = "correlationId";

  /** The longest trace, span or correlation ID that an error report keeps. */
  public static final int MAX_ID_LENGTH = 128;
}
