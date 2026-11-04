package com.exepex.stratum.observability.it;

import java.util.Map;
import lombok.RequiredArgsConstructor;

/**
 * Each header form that Spring Boot reads by default and that asks the service to keep the caller's
 * trace. Each form has its own first 16 hex digits of the trace ID, so one form's spans can never
 * answer another form's check.
 */
@RequiredArgsConstructor
enum CallerSamplingHeader {

  /** W3C {@code traceparent} with the sampled flag {@code 01}. */
  TRACEPARENT_SAMPLED("1111111111111111") {
    @Override
    Map<String, String> headers(String traceId) {
      return Map.of("traceparent", "00-" + traceId + "-" + CALLER_SPAN_ID + "-01");
    }
  },

  /** The single B3 header with the sampled flag {@code 1}. */
  B3_SAMPLED("2222222222222222") {
    @Override
    Map<String, String> headers(String traceId) {
      return Map.of("b3", traceId + "-" + CALLER_SPAN_ID + "-1");
    }
  },

  /** The single B3 header with the debug flag {@code d}, which exists to force sampling. */
  B3_DEBUG("3333333333333333") {
    @Override
    Map<String, String> headers(String traceId) {
      return Map.of("b3", traceId + "-" + CALLER_SPAN_ID + "-d");
    }
  },

  /** The multiple B3 headers with the debug flag {@code X-B3-Flags: 1}. */
  MULTIPLE_B3_DEBUG("4444444444444444") {
    @Override
    Map<String, String> headers(String traceId) {
      return Map.of("X-B3-TraceId", traceId, "X-B3-SpanId", CALLER_SPAN_ID, "X-B3-Flags", "1");
    }
  };

  /** The caller's span ID, the parent of the service's span. */
  private static final String CALLER_SPAN_ID = "00f067aa0ba902b7";

  /** The first 16 hex digits of every trace ID this form sends. */
  private final String firstHexDigits;

  /**
   * Returns this form's trace ID that ends with the given digits.
   *
   * @param lastHexDigits the last 16 hex digits, the part the sampler decides by
   * @return the trace ID, 32 hex digits
   */
  String traceId(String lastHexDigits) {
    return firstHexDigits + lastHexDigits;
  }

  /**
   * Returns the headers a caller sends to ask for this trace to be kept.
   *
   * @param traceId the caller's trace ID
   * @return the header names and values
   */
  abstract Map<String, String> headers(String traceId);
}
