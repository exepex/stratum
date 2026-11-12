package com.exepex.stratum.httpclient;

import java.time.Duration;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * When a client sends a failed call again, and how long it waits first. Only calls that are safe to
 * repeat are retried, on a failure that may pass, and never past {@code total-timeout}. A key left
 * unset comes from {@code defaults}, and one {@code defaults} leaves unset from Stratum.
 *
 * @param maxRetries How many times a failed call is sent again, from 0 to 9, so 3 means at most 4
 *     attempts. A POST or PATCH is sent again only when its connection failed. Defaults to 3.
 * @param backoffPolicy How the wait grows: exponential, where each wait is multiplier times the
 *     last, or fixed, where every wait is delay. Defaults to exponential.
 * @param delay The wait before the first retry, such as 200ms. Defaults to 200ms.
 * @param multiplier How much each wait grows, from 1.0 to 10.0. Only read with exponential; ignored
 *     with fixed. Defaults to 2.0.
 * @param maxDelay The longest single wait, at least delay, such as 1s. Only read with exponential;
 *     ignored with fixed. Defaults to 2s, or delay when that is longer.
 */
@Builder(toBuilder = true)
public record ClientRetry(
    @Nullable Integer maxRetries,
    @Nullable BackoffPolicy backoffPolicy,
    @Nullable Duration delay,
    @Nullable Double multiplier,
    @Nullable Duration maxDelay) {

  /**
   * Returns these settings, with each one left unset taken from the fallback.
   *
   * @param fallback the settings used where these leave one unset, or {@code null} for none
   * @return the merged settings
   */
  public ClientRetry orElse(@Nullable ClientRetry fallback) {
    if (fallback == null) {
      return this;
    }
    return ClientRetry.builder()
        .maxRetries(Optional.ofNullable(maxRetries).orElse(fallback.maxRetries))
        .backoffPolicy(Optional.ofNullable(backoffPolicy).orElse(fallback.backoffPolicy))
        .delay(Optional.ofNullable(delay).orElse(fallback.delay))
        .multiplier(Optional.ofNullable(multiplier).orElse(fallback.multiplier))
        .maxDelay(Optional.ofNullable(maxDelay).orElse(fallback.maxDelay))
        .build();
  }

  /**
   * Returns whether {@code max-delay}, when both waits are set, is at least {@code delay}.
   *
   * @return whether the longest wait is not shorter than the first
   */
  boolean isMaxDelayAtLeastDelay() {
    return delay == null || maxDelay == null || maxDelay.compareTo(delay) >= 0;
  }

  /**
   * Returns the waits, each of which may be unset.
   *
   * @return {@code delay} and {@code max-delay}
   */
  Stream<@Nullable Duration> durations() {
    return Stream.of(delay, maxDelay);
  }
}
