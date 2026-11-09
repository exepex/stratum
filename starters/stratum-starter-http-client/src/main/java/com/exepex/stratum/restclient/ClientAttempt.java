package com.exepex.stratum.restclient;

import java.time.Duration;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;

/**
 * One attempt of a call by a Stratum client, as its retry rules see it. A service's {@link
 * StratumClientCustomizer#customizeRetry} can decide on it with its own rules. Its text leaves out
 * the result and the failure, which can hold the other service's data.
 */
@Value
@Builder(toBuilder = true)
public class ClientAttempt {

  /** The call's HTTP method. */
  HttpMethod method;

  /** What the attempt returned, or {@code null} when it failed or returns nothing. */
  @ToString.Exclude @Nullable Object result;

  /** Why the attempt failed, or {@code null} when it did not; read through {@link #getFailure}. */
  @Getter(AccessLevel.NONE)
  @ToString.Exclude
  @Nullable RuntimeException failure;

  /** Whether the request's body is streamed, which is never sent twice. */
  boolean streamedBody;

  /** The time left before the call's {@code total-timeout} when the attempt ended. */
  Duration timeLeft;

  /**
   * The wait before the next attempt, if there is one: the answer's {@code Retry-After}, or else
   * the client's back-off from {@code retry.delay}.
   */
  Duration nextWait;

  /**
   * Returns why the attempt failed: Spring's {@code RestClientResponseException} for an error
   * answer, such as a 503, or {@code ResourceAccessException} when no answer came, such as after a
   * timeout.
   *
   * @return the failure, or empty when the attempt did not fail
   */
  public Optional<RuntimeException> getFailure() {
    return Optional.ofNullable(failure);
  }
}
