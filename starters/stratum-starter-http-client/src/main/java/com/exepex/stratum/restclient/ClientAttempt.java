package com.exepex.stratum.restclient;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import lombok.Builder;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;

/**
 * One attempt of a call by a Stratum client, as its retry rules see it. A service's {@link
 * StratumClientCustomizer#customizeRetry} can decide on it with its own rules.
 *
 * @param method the call's HTTP method
 * @param result what the attempt returned, or {@code null} when it failed or returns nothing
 * @param failure why the attempt failed, or empty when it did not: Spring's {@code
 *     RestClientResponseException} for an error answer, such as a 503, or {@code
 *     ResourceAccessException} when no answer came, such as after a timeout
 * @param streamedBody whether the request's body is streamed, which is never sent twice
 * @param timeLeft the time left before the call's {@code total-timeout} when the attempt ended
 * @param nextWait the wait before the next attempt, if there is one: the answer's {@code
 *     Retry-After}, or else the growing back-off from {@code retry-delay}
 */
@Builder(toBuilder = true)
public record ClientAttempt(
    HttpMethod method,
    @Nullable Object result,
    Optional<RuntimeException> failure,
    boolean streamedBody,
    Duration timeLeft,
    Duration nextWait) {

  /**
   * Makes the attempt, with no failure when none is given.
   *
   * @param method the call's HTTP method
   * @param result what the attempt returned
   * @param failure why the attempt failed
   * @param streamedBody whether the request's body is streamed
   * @param timeLeft the time left before the call's {@code total-timeout}
   * @param nextWait the wait before the next attempt
   */
  public ClientAttempt {
    failure = Objects.requireNonNullElse(failure, Optional.empty());
  }
}
