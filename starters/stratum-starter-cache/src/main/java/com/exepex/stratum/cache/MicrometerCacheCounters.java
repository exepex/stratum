package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.cache.metrics.CacheMetricsRegistrar;
import org.springframework.cache.Cache;
import org.springframework.util.StringUtils;

/**
 * Counts the cache layer's failures and refused cache names in the service's {@link MeterRegistry}:
 * {@code stratum.cache.failures} with the tags {@code cache} and {@code operation}, and {@code
 * stratum.cache.skips} with {@code cache} and {@code reason}. The registry keeps one meter per tag
 * set, so counting adds no allocation after the first call. It also has Spring Boot's {@link
 * CacheMetricsRegistrar} measure each cache made on its first use, with the same {@code
 * cache.manager} tag as Spring Boot's own, when the service has that registrar.
 */
@StratumInternal
@RequiredArgsConstructor
public class MicrometerCacheCounters implements CacheCounters {

  /** The service's metrics. */
  private final MeterRegistry registry;

  /** Spring Boot's registrar of cache metrics, present when Spring Boot measures caches. */
  private final ObjectProvider<CacheMetricsRegistrar> registrar;

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

  @Override
  public void bindCacheMetrics(Cache cache, String cacheManagerName) {
    var managerTag = managerTag(cacheManagerName);
    // Spring Boot already measures the caches it knew at startup; binding them again would make
    // Micrometer warn and keep the first meters anyway.
    var measured =
        registry
            .find(CacheConstants.METRIC_CACHE_GETS)
            .tag(CacheConstants.TAG_CACHE, cache.getName())
            .tag(CacheConstants.TAG_CACHE_MANAGER, managerTag)
            .meter();
    if (measured == null) {
      registrar.ifAvailable(
          bootRegistrar ->
              bootRegistrar.bindCacheToRegistry(
                  cache, Tag.of(CacheConstants.TAG_CACHE_MANAGER, managerTag)));
    }
  }

  /** Returns Spring Boot's tag value for a cache manager: its bean name without the suffix. */
  private String managerTag(String beanName) {
    var suffix = CacheConstants.CACHE_MANAGER_SUFFIX;
    // Spring Boot's own rule, private in CacheMetricsRegistrarConfiguration, so the tags match.
    return beanName.length() > suffix.length() && StringUtils.endsWithIgnoreCase(beanName, suffix)
        ? beanName.substring(0, beanName.length() - suffix.length())
        : beanName;
  }
}
