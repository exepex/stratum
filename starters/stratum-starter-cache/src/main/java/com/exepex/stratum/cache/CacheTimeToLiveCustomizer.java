package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.data.redis.cache.RedisCacheManager.RedisCacheManagerBuilder;

/**
 * Gives each Redis cache that sets {@code stratum.cache.caches.<name>.time-to-live} its own time to
 * live. Only the time to live changes: the cache keeps the rest of its configuration, from Spring
 * Boot's defaults, the service's own Redis cache configuration or its own customizers, which run
 * before this one. Such a cache is created at startup. Caffeine caches get theirs from {@link
 * CaffeineCacheSettingsCustomizer}.
 */
@StratumInternal
@RequiredArgsConstructor
public class CacheTimeToLiveCustomizer implements RedisCacheManagerBuilderCustomizer {

  /** Each cache's own time to live. */
  private final Map<String, Duration> timesToLive;

  /**
   * Sets each cache's own time to live on its final configuration.
   *
   * @param builder Spring Boot's builder of the Redis cache manager
   */
  @Override
  public void customize(RedisCacheManagerBuilder builder) {
    timesToLive.forEach(
        (name, timeToLive) ->
            builder.withCacheConfiguration(
                name,
                builder
                    .getCacheConfigurationFor(name)
                    .orElseGet(builder::cacheDefaults)
                    .entryTtl(timeToLive)));
  }
}
