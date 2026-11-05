package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import org.springframework.cache.Cache;

/**
 * Records the cache layer's metrics: it counts failures, skips (refused cache names, hashed keys
 * and values too large to store) and Redis connect failures, and has Spring Boot measure each cache
 * made on its first use. The decorator, the handler and the resolver never name a Micrometer type,
 * so they stay loadable without {@code micrometer-core}.
 */
@StratumInternal
public interface CacheCounters {

  /**
   * Counts one failed cache operation.
   *
   * @param cache the cache's name, or {@code _over-limit} past the limit
   * @param operation the operation that failed, such as {@code get}
   */
  void failure(String cache, String operation);

  /**
   * Counts one skip: a refused cache name, a key stored under its hash or a value not stored.
   *
   * @param cache the cache's name, or {@code _over-limit} for a refused name
   * @param reason why, such as {@code cache-limit}, {@code key-hashed} or {@code value-too-large}
   */
  void skip(String cache, String reason);

  /** Counts one time Redis could not be reached and the Redis caches started skipping it. */
  void redisConnectFailure();

  /**
   * Has Spring Boot's cache metrics, such as {@code cache.gets}, measure a cache the layer has just
   * admitted, unless Spring Boot measures it already.
   *
   * @param cache the cache the cache manager made, never the layer's decorator
   * @param cacheManagerName the bean name of the cache manager that made it
   */
  void bindCacheMetrics(Cache cache, String cacheManagerName);

  /**
   * Returns counters that count nothing, for a service without Micrometer.
   *
   * @return the no-op counters
   */
  static CacheCounters noop() {
    return Noop.INSTANCE;
  }

  /** The counters that count and measure nothing. */
  @StratumInternal
  enum Noop implements CacheCounters {

    /** The one instance. */
    INSTANCE;

    @Override
    public void failure(String cache, String operation) {
      // Counts nothing.
    }

    @Override
    public void skip(String cache, String reason) {
      // Counts nothing.
    }

    @Override
    public void bindCacheMetrics(Cache cache, String cacheManagerName) {
      // Measures nothing.
    }

    @Override
    public void redisConnectFailure() {
      // Counts nothing.
    }
  }
}
