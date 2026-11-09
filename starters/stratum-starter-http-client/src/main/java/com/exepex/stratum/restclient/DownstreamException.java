package com.exepex.stratum.restclient;

import com.exepex.stratum.core.logging.LoggingContextConstants;
import java.io.Serial;
import java.util.Optional;
import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Another service answered a call of a Stratum client with an error status, 4xx or 5xx, after the
 * client's retries. It carries what the answer said, with at most 64 KB of its body, and where the
 * call went, so the service can decide what to do; uncaught, its caller gets {@code 500} and never
 * the other service's body.
 *
 * <pre>{@code
 * try {
 *   return inventory.stock(sku);
 * } catch (DownstreamException failure) {
 *   if (failure.getStatusCode().value() == 404) {
 *     return Stock.none(sku);
 *   }
 *   throw failure;
 * }
 * }</pre>
 *
 * <p>Its message names the client, the status and the endpoint's template, never the body or a
 * value of the path or query, so that logging it leaks no data.
 */
public final class DownstreamException extends RestClientResponseException {

  @Serial private static final long serialVersionUID = 1L;

  /** The name of the client that made the call, such as {@code inventory}. */
  @Getter private final String client;

  /** The call's method and URI template, such as {@code GET /stock/{sku}}. */
  @Getter private final String endpoint;

  /** The trace ID of the call, or null when none was being logged. */
  private final @Nullable String traceId;

  /**
   * Makes the exception from Spring's exception for the error answer.
   *
   * @param client the client's name
   * @param endpoint the call's method and URI template
   * @param answer Spring's exception for the error answer, whose converters read the body
   */
  public DownstreamException(String client, String endpoint, RestClientResponseException answer) {
    super(
        client + " answered " + answer.getStatusCode().value() + " to " + endpoint,
        answer.getStatusCode(),
        answer.getStatusText(),
        answer.getResponseHeaders(),
        answer.getResponseBodyAsByteArray(),
        Optional.ofNullable(answer.getResponseHeaders())
            .map(HttpHeaders::getContentType)
            .map(MediaType::getCharset)
            .orElse(null));
    this.client = client;
    this.endpoint = endpoint;
    this.traceId = traceIdOrNull();
    // Spring's exception is not the cause, as its message holds part of the body; it only converts.
    setBodyConvertFunction(
        type -> answer.getResponseBodyAs(ParameterizedTypeReference.forType(type.getType())));
  }

  /**
   * Returns the trace ID of the call, which finds its logs.
   *
   * @return the trace ID, or empty when none was being logged
   */
  public Optional<String> getTraceId() {
    return Optional.ofNullable(traceId);
  }

  /**
   * Returns the error code the other service put in its body, the {@code code} member of an {@code
   * application/problem+json} body, such as a Stratum service's {@code ORD-0001}.
   *
   * @return the code, or empty for any other body or one that cannot be read
   */
  public Optional<String> getDownstreamCode() {
    if (!isProblemDetail()) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(getResponseBodyAs(ProblemDetail.class))
          .map(ProblemDetail::getProperties)
          .map(properties -> properties.get("code"))
          .filter(String.class::isInstance)
          .map(String.class::cast);
    } catch (RestClientException _) {
      return Optional.empty();
    }
  }

  /** Returns whether the body is a problem detail, by its content type. */
  private boolean isProblemDetail() {
    return Optional.ofNullable(getResponseHeaders())
        .map(HttpHeaders::getContentType)
        .filter(MediaType.APPLICATION_PROBLEM_JSON::isCompatibleWith)
        .isPresent();
  }

  /** Reads the trace ID from the logging context, or null if there is none safe to log. */
  private @Nullable String traceIdOrNull() {
    var id = MDC.get(LoggingContextConstants.TRACE_ID);
    return LoggingContextConstants.isSafeForLogs(id) ? id : null;
  }
}
