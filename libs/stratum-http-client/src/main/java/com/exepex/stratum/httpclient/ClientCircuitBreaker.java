package com.exepex.stratum.httpclient;

import java.time.Duration;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * When a client's circuit breaker opens, how long it stays open and how it tries to close, with
 * Resilience4j's names. It counts each attempt, retries included. A key left unset comes from
 * {@code defaults}, and one {@code defaults} leaves unset from Stratum.
 *
 * @param enabled Whether the client has a circuit breaker. Defaults to true.
 * @param failureRateThreshold The share of counted attempts, in percent from 1 to 100, that must
 *     fail for the breaker to open. Defaults to 50.
 * @param slidingWindowSize How many of the latest attempts the failure rate is taken over, from 1
 *     to 10000. Defaults to 100.
 * @param minimumNumberOfCalls How many attempts the breaker must see before it may open, from 1 to
 *     10000; above sliding-window-size, the window size is used. Defaults to 100.
 * @param waitDurationInOpenState How long every call fails at once, without being sent, while the
 *     breaker is open, such as 30s. Defaults to 60s.
 * @param permittedNumberOfCallsInHalfOpenState How many trial calls go through after the open
 *     state, from 1 to 1000; below the threshold the breaker closes, otherwise it opens again.
 *     Defaults to 10.
 * @param slowCallDurationThreshold How long an attempt may take before it counts as slow, such as
 *     2s. None by default. Then Resilience4j's 60s applies, which an attempt reaches only with a
 *     total-timeout above 60s.
 * @param slowCallRateThreshold The share of slow attempts, in percent from 1 to 100, that opens the
 *     breaker. Only with slow-call-duration-threshold. None by default. Then Resilience4j's 100
 *     applies.
 */
@Builder(toBuilder = true)
public record ClientCircuitBreaker(
    @Nullable Boolean enabled,
    @Nullable Float failureRateThreshold,
    @Nullable Integer slidingWindowSize,
    @Nullable Integer minimumNumberOfCalls,
    @Nullable Duration waitDurationInOpenState,
    @Nullable Integer permittedNumberOfCallsInHalfOpenState,
    @Nullable Duration slowCallDurationThreshold,
    @Nullable Float slowCallRateThreshold) {

  /**
   * Returns these settings, with each one left unset taken from the fallback.
   *
   * @param fallback the settings used where these leave one unset, or {@code null} for none
   * @return the merged settings
   */
  public ClientCircuitBreaker orElse(@Nullable ClientCircuitBreaker fallback) {
    if (fallback == null) {
      return this;
    }
    return ClientCircuitBreaker.builder()
        .enabled(Optional.ofNullable(enabled).orElse(fallback.enabled))
        .failureRateThreshold(
            Optional.ofNullable(failureRateThreshold).orElse(fallback.failureRateThreshold))
        .slidingWindowSize(
            Optional.ofNullable(slidingWindowSize).orElse(fallback.slidingWindowSize))
        .minimumNumberOfCalls(
            Optional.ofNullable(minimumNumberOfCalls).orElse(fallback.minimumNumberOfCalls))
        .waitDurationInOpenState(
            Optional.ofNullable(waitDurationInOpenState).orElse(fallback.waitDurationInOpenState))
        .permittedNumberOfCallsInHalfOpenState(
            Optional.ofNullable(permittedNumberOfCallsInHalfOpenState)
                .orElse(fallback.permittedNumberOfCallsInHalfOpenState))
        .slowCallDurationThreshold(
            Optional.ofNullable(slowCallDurationThreshold)
                .orElse(fallback.slowCallDurationThreshold))
        .slowCallRateThreshold(
            Optional.ofNullable(slowCallRateThreshold).orElse(fallback.slowCallRateThreshold))
        .build();
  }

  /**
   * Returns whether a slow-call rate is set only together with the slow-call duration, so that the
   * rate is never set on Resilience4j's own 60s by mistake.
   *
   * @return whether the rate has its duration
   */
  boolean isSlowCallRateWithDuration() {
    return slowCallRateThreshold == null || slowCallDurationThreshold != null;
  }

  /**
   * Returns the durations, each of which may be unset.
   *
   * @return {@code wait-duration-in-open-state} and {@code slow-call-duration-threshold}
   */
  Stream<@Nullable Duration> durations() {
    return Stream.of(waitDurationInOpenState, slowCallDurationThreshold);
  }
}
