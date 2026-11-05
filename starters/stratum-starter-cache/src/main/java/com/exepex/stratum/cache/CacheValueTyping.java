package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheOperationInvocationContext;

/**
 * Gives a cached method's reads its own return type, for caches that store values untyped, such as
 * Redis caches with JSON values. Its signatures name no Jackson type, so the cache layer loads
 * without Jackson.
 */
@StratumInternal
public interface CacheValueTyping {

  /**
   * Returns whether a cache's values need the method's type to be read.
   *
   * @param cache a cache the cache manager made
   * @return whether reads of it are typed
   */
  boolean appliesTo(Cache cache);

  /**
   * Returns the cache as one call of a method sees it: its reads return the method's type, or, for
   * a type that cannot be read, a cache that never uses Redis.
   *
   * @param cache the layer's cache, for which {@link #appliesTo} was true
   * @param context the call
   * @return the cache for this call
   */
  Cache typed(Cache cache, CacheOperationInvocationContext<?> context);

  /**
   * Returns the typing of a service whose caches need none.
   *
   * @return the typing that applies to no cache
   */
  static CacheValueTyping none() {
    return None.INSTANCE;
  }

  /** The typing that applies to no cache. */
  @StratumInternal
  enum None implements CacheValueTyping {

    /** The one instance. */
    INSTANCE;

    @Override
    public boolean appliesTo(Cache cache) {
      return false;
    }

    @Override
    public Cache typed(Cache cache, CacheOperationInvocationContext<?> context) {
      return cache;
    }
  }
}
