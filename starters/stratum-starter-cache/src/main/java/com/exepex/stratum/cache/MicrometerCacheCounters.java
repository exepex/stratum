package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;

/**
 * Counts the cache layer's failures and skips in the service's {@link MeterRegistry}: {@code
 * stratum.cache.failures} with the tags {@code cache} and {@code operation}, and {@code
 * stratum.cache.skips} with {@code cache} and {@code reason}. The registry keeps one meter per tag
 * set, so counting adds no allocation after the first call.
 */
@StratumInternal
@RequiredArgsConstructor
public class MicrometerCacheCounters implements CacheCounters {

  /** The service's metrics. */
  private final MeterRegistry registry;

  @Override
  public void failure(String cache, String operation) {
    registry
        .counter(
            CacheConstants.METRIC_FAILURES,
            CacheConstants.TAG_CACHE,
            cache,
            CacheConstants.TAG_OPERATION,
            operation)
        .increment();
  }

  @Override
  public void skip(String cache, String reason) {
    registry
        .counter(
            CacheConstants.METRIC_SKIPS,
            CacheConstants.TAG_CACHE,
            cache,
            CacheConstants.TAG_REASON,
            reason)
        .increment();
  }
}
