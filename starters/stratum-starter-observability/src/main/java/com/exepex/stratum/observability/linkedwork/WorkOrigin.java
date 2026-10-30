package com.exepex.stratum.observability.linkedwork;

import com.exepex.stratum.core.error.LoggingContextConstants;
import java.util.regex.Pattern;
import lombok.Builder;

/**
 * Where a piece of work came from: the trace and span of the request that caused it, and its
 * correlation ID. A service stores {@link #toText()} with the work, such as in a database row, and
 * gives {@link #fromText(String)} back to {@link LinkedWork} when it runs the work later.
 *
 * @param traceId the request's trace ID, 32 lowercase hexadecimal characters, not all zeros
 * @param spanId the request's span ID, 16 lowercase hexadecimal characters, not all zeros
 * @param sampled whether the request's trace was sampled
 * @param correlationId the request's correlation ID, 1 to 128 characters safe for logs
 */
@Builder
public record WorkOrigin(String traceId, String spanId, boolean sampled, String correlationId) {

  /** A trace ID as W3C trace context writes it. */
  private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");

  /** A span ID as W3C trace context writes it. */
  private static final Pattern SPAN_ID = Pattern.compile("[0-9a-f]{16}");

  /** An ID of zeros only, which W3C trace context defines as invalid. */
  private static final Pattern ALL_ZEROS = Pattern.compile("0+");

  /** The stored form: a W3C {@code traceparent}, a space, then the correlation ID. */
  private static final Pattern STORED_FORM =
      Pattern.compile(
          "00-(?<traceId>[0-9a-f]{32})-(?<spanId>[0-9a-f]{16})-(?<flags>0[01])"
              + " (?<correlationId>[A-Za-z0-9._:-]{1,128})");

  /** The {@code traceparent} flags of a sampled trace. */
  private static final String SAMPLED_FLAGS = "01";

  /** The {@code traceparent} flags of a trace that was not sampled. */
  private static final String NOT_SAMPLED_FLAGS = "00";

  /**
   * Checks the parts, so a stored origin can always be read back.
   *
   * @throws IllegalArgumentException if a part has another form; the message never shows values
   */
  public WorkOrigin {
    if (!TRACE_ID.matcher(traceId).matches()
        || !SPAN_ID.matcher(spanId).matches()
        || ALL_ZEROS.matcher(traceId).matches()
        || ALL_ZEROS.matcher(spanId).matches()
        || !LoggingContextConstants.isSafeForLogs(correlationId)) {
      throw new IllegalArgumentException(
          "A work origin needs a 32-character trace ID and a 16-character span ID, both lowercase"
              + " hexadecimal and not all zeros, and a correlation ID of 1 to 128 letters, digits,"
              + " '-', '_', '.' or ':'");
    }
  }

  /**
   * Reads an origin stored with {@link #toText()}.
   *
   * @param storedOrigin the stored text
   * @return the origin
   * @throws IllegalArgumentException if the text has another form; the message never shows it
   */
  public static WorkOrigin fromText(String storedOrigin) {
    var matcher = STORED_FORM.matcher(storedOrigin);
    if (!matcher.matches()) {
      throw new IllegalArgumentException(
          "A stored work origin must be a W3C traceparent, a space and a correlation ID");
    }
    return WorkOrigin.builder()
        .traceId(matcher.group("traceId"))
        .spanId(matcher.group("spanId"))
        .sampled(SAMPLED_FLAGS.equals(matcher.group("flags")))
        .correlationId(matcher.group("correlationId"))
        .build();
  }

  /**
   * Returns the text to store with the work: at most 184 characters, all safe for logs.
   *
   * @return the stored form
   */
  public String toText() {
    return "00-%s-%s-%s %s"
        .formatted(traceId, spanId, sampled ? SAMPLED_FLAGS : NOT_SAMPLED_FLAGS, correlationId);
  }
}
