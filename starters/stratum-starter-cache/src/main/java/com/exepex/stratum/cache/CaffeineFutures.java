package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCache;

/**
 * Lets Caffeine caches serve cached methods that return a {@code CompletableFuture}, a {@code Mono}
 * or a {@code Flux}. Spring Boot builds Caffeine caches without Caffeine's async mode, and such a
 * cache cannot answer these methods; it is given a {@link CaffeineFutureCache}. A cache in async
 * mode, which a service may turn on itself, keeps Caffeine's own answers.
 */
@StratumInternal
public class CaffeineFutures {

  /**
   * Returns a Caffeine cache without async mode able to serve future and reactive methods; returns
   * any other cache unchanged.
   *
   * @param cache a cache the cache manager made
   * @return the cache that serves them, or the same cache
   */
  public Cache servingFutures(Cache cache) {
    return cache instanceof CaffeineCache caffeine && !inAsyncMode(caffeine)
        ? new CaffeineFutureCache(caffeine)
        : cache;
  }

  /** Returns whether a Caffeine cache was built in Caffeine's async mode. */
  private boolean inAsyncMode(CaffeineCache cache) {
    try {
      cache.getAsyncCache();
      return true;
    } catch (IllegalStateException noAsyncCache) {
      // Spring's documented answer for a cache built without async mode; asked once per cache.
      return false;
    }
  }
}
