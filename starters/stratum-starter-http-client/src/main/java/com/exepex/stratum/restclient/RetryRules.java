package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.FailureCauses;
import com.exepex.stratum.httpclient.HttpClientConstants;
import io.github.resilience4j.core.IntervalFunction;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Set;
import lombok.experimental.UtilityClass;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * The fixed retry and circuit breaker rules of Stratum's clients (ADR 0011, decisions 4.2 to 4.5):
 * which failed attempt is sent again, how long to wait first, and which failure the breaker counts.
 */
@StratumInternal
@UtilityClass
public class RetryRules {

  /** The methods that are safe to send again after any failure (RFC 9110). */
  private static final Set<HttpMethod> IDEMPOTENT =
      Set.of(
          HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.PUT, HttpMethod.DELETE);

  /** The answers after which an idempotent call is sent again. */
  private static final Set<Integer> RETRIED_STATUSES =
      Set.of(
          HttpStatus.BAD_GATEWAY.value(),
          HttpStatus.SERVICE_UNAVAILABLE.value(),
          HttpStatus.GATEWAY_TIMEOUT.value(),
          HttpStatus.TOO_MANY_REQUESTS.value());

  /** The answers whose {@code Retry-After} sets the wait. */
  private static final Set<Integer> RETRY_AFTER_STATUSES =
      Set.of(HttpStatus.SERVICE_UNAVAILABLE.value(), HttpStatus.TOO_MANY_REQUESTS.value());

  /** The longest {@code Retry-After} value that is read; a longer one is ignored unread. */
  private static final int MAX_RETRY_AFTER_LENGTH = 32;

  /** The most digits of a {@code Retry-After} in seconds, so that it cannot overflow. */
  private static final int MAX_RETRY_AFTER_DIGITS = 9;

  /** How much each wait grows: it doubles. */
  private static final double BACK_OFF_MULTIPLIER = 2;

  /** How far each wait is spread at random: up to half of it either way. */
  private static final double BACK_OFF_SPREAD = 0.5;

  /**
   * Returns whether Stratum sends the call again after this attempt: its rules allow it, and the
   * next wait still leaves the least time for one more attempt before {@code total-timeout}.
   *
   * @param attempt the attempt that ended
   * @return whether to send the call again
   */
  public static boolean shouldRetry(ClientAttempt attempt) {
    return isRetryable(attempt)
        && attempt
                .getNextWait()
                .plus(HttpClientConstants.MIN_ATTEMPT_TIME)
                .compareTo(attempt.getTimeLeft())
            <= 0;
  }

  /**
   * Returns whether the rules allow the call to be sent again after this attempt, whatever the time
   * left: never a streamed body, never a success; an error answer of 502, 503, 504 or 429 for an
   * idempotent method; a failure with no answer as {@link #isRetryableWithoutAnswer} says; and an
   * answer whose body was cut off, for an idempotent method.
   *
   * @param attempt the attempt that ended
   * @return whether the rules allow another attempt
   */
  static boolean isRetryable(ClientAttempt attempt) {
    if (attempt.isStreamedBody()) {
      return false;
    }
    return switch (attempt.getFailure().orElse(null)) {
      case RestClientResponseException answer ->
          IDEMPOTENT.contains(attempt.getMethod())
              && RETRIED_STATUSES.contains(answer.getStatusCode().value());
      case ResourceAccessException noAnswer ->
          isRetryableWithoutAnswer(attempt.getMethod(), noAnswer);
      case RestClientException failed when isBodyCutOff(failed) ->
          IDEMPOTENT.contains(attempt.getMethod());
      case null, default -> false;
    };
  }

  /**
   * Returns the wait before the next attempt: the {@code Retry-After} of a 503 or 429, or else the
   * back-off's wait for this attempt.
   *
   * @param failure why the attempt failed, or {@code null}
   * @param attemptNumber the attempt's number, from 1
   * @param backOff the client's back-off
   * @param now the current time, for a {@code Retry-After} date
   * @return the wait
   */
  public static Duration nextWait(
      @Nullable RuntimeException failure,
      int attemptNumber,
      IntervalFunction backOff,
      Instant now) {
    return retryAfter(failure, now)
        .orElseGet(() -> Duration.ofMillis(backOff.apply(attemptNumber)));
  }

  /**
   * Returns the back-off of a client: {@code retry-delay} first, doubled each time, spread at
   * random by up to half, and at most 2s, or {@code retry-delay} when that is longer.
   *
   * @param retryDelay the wait before the first retry
   * @return the back-off
   */
  public static IntervalFunction backOff(Duration retryDelay) {
    var longest =
        retryDelay.compareTo(HttpClientConstants.MAX_RETRY_DELAY) > 0
            ? retryDelay
            : HttpClientConstants.MAX_RETRY_DELAY;
    return IntervalFunction.ofExponentialRandomBackoff(
        retryDelay, BACK_OFF_MULTIPLIER, BACK_OFF_SPREAD, longest);
  }

  /**
   * Returns whether the circuit breaker counts a failed attempt: no answer, such as a timeout or a
   * dropped connection, an answer whose body was cut off, or an answer of 5xx or 429. Other 4xx
   * answers and a body that is not valid are never counted.
   *
   * @param failure the attempt's failure
   * @return whether it counts as a failure
   */
  public static boolean countsAsBreakerFailure(Throwable failure) {
    return switch (failure) {
      case ResourceAccessException _ -> true;
      case RestClientResponseException answer ->
          answer.getStatusCode().is5xxServerError()
              || answer.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value();
      case RestClientException failed when isBodyCutOff(failed) -> true;
      default -> false;
    };
  }

  /**
   * Returns the wait an answer's {@code Retry-After} asks for: whole seconds, or an HTTP date. A
   * value that is neither, is negative, is a date already past or has more than 32 characters is
   * ignored.
   *
   * @param value the header's value, or {@code null}
   * @param now the current time
   * @return the wait, or empty when the value is ignored
   */
  static Optional<Duration> parseRetryAfter(@Nullable String value, Instant now) {
    if (value == null || value.length() > MAX_RETRY_AFTER_LENGTH) {
      return Optional.empty();
    }
    var trimmed = value.strip();
    if (!trimmed.isEmpty() && trimmed.chars().allMatch(Character::isDigit)) {
      return trimmed.length() > MAX_RETRY_AFTER_DIGITS
          ? Optional.empty()
          : Optional.of(Duration.ofSeconds(Long.parseLong(trimmed)));
    }
    try {
      var wait =
          Duration.between(now, ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME));
      return wait.isNegative() ? Optional.empty() : Optional.of(wait);
    } catch (DateTimeParseException _) {
      return Optional.empty();
    }
  }

  /** Returns the {@code Retry-After} wait of a 503 or 429 answer, if it has a valid one. */
  private static Optional<Duration> retryAfter(@Nullable RuntimeException failure, Instant now) {
    if (failure instanceof RestClientResponseException answer
        && RETRY_AFTER_STATUSES.contains(answer.getStatusCode().value())) {
      var headers = answer.getResponseHeaders();
      return parseRetryAfter(
          headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER), now);
    }
    return Optional.empty();
  }

  /**
   * Returns whether a call that got no answer is sent again: never after a full pool, which is the
   * service's own load; always after a failed connection, when nothing was sent; and after any
   * other failure, such as a dropped connection or a read timeout, only for an idempotent method.
   */
  private static boolean isRetryableWithoutAnswer(
      HttpMethod method, ResourceAccessException failure) {
    if (FailureCauses.causesOf(failure)
        .anyMatch(ConnectionRequestTimeoutException.class::isInstance)) {
      return false;
    }
    return FailureCauses.causesOf(failure).anyMatch(RetryRules::isConnectFailure)
        || IDEMPOTENT.contains(method);
  }

  /**
   * Returns the I/O failure that ended a call, found anywhere in its causes: a call that got no
   * answer, or a body cut off while it was read, such as by a read timeout or a dropped connection.
   * Spring wraps a cut-off body in a plain {@link RestClientException}, through the converter's own
   * exceptions when the body is JSON. A body that is not valid, such as bad JSON, has no I/O
   * failure among its causes.
   *
   * @param failure the failure that ended the call
   * @return its I/O failure, or empty when it has none
   */
  public static Optional<IOException> ioFailureOf(RestClientException failure) {
    return FailureCauses.causesOf(failure)
        .filter(IOException.class::isInstance)
        .map(IOException.class::cast)
        .findFirst();
  }

  /** Returns whether an answer's body was cut off while it was read, as {@link #ioFailureOf}. */
  private static boolean isBodyCutOff(RestClientException failure) {
    return ioFailureOf(failure).isPresent();
  }

  /** Returns whether a cause is a failure to connect, after which nothing was sent. */
  private static boolean isConnectFailure(Throwable cause) {
    return cause instanceof ConnectException
        || cause instanceof ConnectTimeoutException
        || cause instanceof UnknownHostException
        || cause instanceof NoRouteToHostException;
  }
}
