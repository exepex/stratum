package com.exepex.stratum.core.error;

import java.io.Serializable;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * The IDs that tie a reported problem to the logs around it. Tracing sets the trace and span IDs;
 * Stratum sets the correlation ID, which ties together every step of one business workflow, such as
 * one order or one batch job, across many requests.
 *
 * @param traceId the trace ID of the current request, or {@code null} without tracing
 * @param spanId the span ID of the current operation, or {@code null} without tracing
 * @param correlationId the current workflow's correlation ID, or {@code null} when none was set
 */
public record DiagnosticIds(
    @Nullable String traceId, @Nullable String spanId, @Nullable String correlationId)
    implements Serializable {

  /**
   * What a usable ID looks like: letters, digits, {@code -}, {@code _}, {@code .} and {@code :}.
   * Anything else, such as a line break, could forge a log line or break a response.
   */
  private static final Pattern ID_FORMAT =
      Pattern.compile("[A-Za-z0-9._:-]{1," + ErrorConstants.MAX_ID_LENGTH + "}");

  /**
   * Reads the IDs of the current thread from the logging context, where tracing and Stratum put
   * them. An ID that is not {@linkplain #isUsable usable} is left out.
   *
   * @return the current IDs; each is {@code null} when it is missing or not usable
   */
  public static DiagnosticIds current() {
    return new DiagnosticIds(
        usableOrNull(MDC.get(ErrorConstants.TRACE_ID_KEY)),
        usableOrNull(MDC.get(ErrorConstants.SPAN_ID_KEY)),
        usableOrNull(MDC.get(ErrorConstants.CORRELATION_ID_KEY)));
  }

  /**
   * Returns whether an ID is safe to write to logs and responses: at most {@value
   * ErrorConstants#MAX_ID_LENGTH} characters, all letters, digits, {@code -}, {@code _}, {@code .}
   * or {@code :}. A module that accepts a correlation ID from outside, such as from a request
   * header, checks it with this method first.
   *
   * @param id the ID to check
   * @return whether the ID can be used
   */
  public static boolean isUsable(@Nullable String id) {
    return id != null && ID_FORMAT.matcher(id).matches();
  }

  /**
   * Returns the ID when it is usable.
   *
   * @param id the ID read from the logging context
   * @return the ID, or {@code null} when it is missing or not usable
   */
  private static @Nullable String usableOrNull(@Nullable String id) {
    return isUsable(id) ? id : null;
  }
}
