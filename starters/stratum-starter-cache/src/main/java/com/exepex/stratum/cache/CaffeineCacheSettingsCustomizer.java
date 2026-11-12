package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.StratumException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Policy;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.cache.autoconfigure.CacheManagerCustomizer;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;

/**
 * Gives each Caffeine cache that sets {@code stratum.cache.caches.<name>.max-entries} or {@code
 * time-to-live} its own bound or expiry. Only that value changes: the cache keeps the rest of the
 * Caffeine builder, from Stratum's default, {@code spring.cache.caffeine.spec} or the service's own
 * builder. Such a cache is created at startup.
 */
@StratumInternal
@RequiredArgsConstructor
public class CaffeineCacheSettingsCustomizer
    implements CacheManagerCustomizer<CaffeineCacheManager> {

  /** Each cache's own most entries. */
  private final Map<String, Long> maxEntries;

  /** Each cache's own time to live. */
  private final Map<String, Duration> timesToLive;

  /**
   * Sets each cache's own bound and expiry on the cache the manager creates for it.
   *
   * @param cacheManager Spring Boot's Caffeine cache manager
   * @throws StratumException {@code SX-CACHE-1008} if a cache's own setting cannot apply: the
   *     builder has no {@code maximumSize} or no {@code expireAfterWrite}, or the cache manager has
   *     a fixed list of names without the cache
   */
  @Override
  public void customize(CaffeineCacheManager cacheManager) {
    maxEntries.forEach(
        (name, entries) -> sizeBound(nativeCache(cacheManager, name), name).setMaximum(entries));
    timesToLive.forEach(
        (name, timeToLive) ->
            expiry(nativeCache(cacheManager, name), name).setExpiresAfter(timeToLive));
  }

  /** Returns the Caffeine cache behind a name, which the manager creates if it is new. */
  private Cache<Object, Object> nativeCache(CaffeineCacheManager cacheManager, String name) {
    if (cacheManager.getCache(name) instanceof CaffeineCache cache) {
      return cache.getNativeCache();
    }
    throw unusable(name, "the cache manager has no such cache, for it lists its cache names");
  }

  /** Returns a cache's bound on its number of entries. */
  private Policy.Eviction<Object, Object> sizeBound(Cache<Object, Object> cache, String name) {
    return cache
        .policy()
        .eviction()
        .filter(eviction -> !eviction.isWeighted())
        .orElseThrow(() -> unusable(name, CacheConstants.MAX_ENTRIES + " needs a maximumSize"));
  }

  /** Returns a cache's expiry after each write. */
  private Policy.FixedExpiration<Object, Object> expiry(Cache<Object, Object> cache, String name) {
    return cache
        .policy()
        .expireAfterWrite()
        .orElseThrow(
            () -> unusable(name, CacheConstants.OWN_TIME_TO_LIVE + " needs an expireAfterWrite"));
  }

  /** Returns the error that stops startup for a setting that cannot apply. */
  private StratumException unusable(String name, String reason) {
    return new StratumException(
        CacheErrorCode.CAFFEINE_SETTING_UNUSABLE, "cache " + name + ": " + reason);
  }
}
