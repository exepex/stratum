package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * The cache names that Redis command spans can carry: those in {@code spring.cache.cache-names},
 * and each name Stratum's cache layer admits, at most {@code stratum.cache.max-caches}. A name
 * nobody declared or used, such as part of a service's own Redis key, is never found.
 */
@StratumInternal
public class RedisCacheNames {

  /** The text before the cache name in a key, or {@code null} when keys carry no cache name. */
  private final @Nullable String keyPrefix;

  /** The known names; changed only under {@link #adding}. */
  private final Set<String> names;

  /** Lets one name be added at a time, so no added name is lost. */
  private final ReentrantLock adding = new ReentrantLock();

  /** Finds the known names in a key; replaced whole when a name is added. */
  private final AtomicReference<CacheKeyMatcher> matcher;

  /**
   * Creates the names, starting with the given ones.
   *
   * @param keyPrefix the text before the cache name in a key, or {@code null} with {@code
   *     spring.cache.redis.use-key-prefix=false}; then no key is matched
   * @param configuredNames the names known from the start
   */
  RedisCacheNames(@Nullable String keyPrefix, List<String> configuredNames) {
    this.keyPrefix = keyPrefix;
    this.names = new HashSet<>(configuredNames);
    this.matcher = new AtomicReference<>(new CacheKeyMatcher(keyPrefix, names));
  }

  /**
   * Returns the names of the service: its key prefix and {@code spring.cache.cache-names}.
   *
   * @param environment the service's environment
   * @return the names
   */
  public static RedisCacheNames resolve(Environment environment) {
    return new RedisCacheNames(
        CacheTimeoutSettings.resolve(environment).keyPrefix(),
        Binder.get(environment)
            .bind(CacheConstants.CACHE_NAMES, Bindable.listOf(String.class))
            .orElseGet(List::of));
  }

  /**
   * Returns names that match no key, for a service without Redis spans.
   *
   * @return the names
   */
  public static RedisCacheNames none() {
    return new RedisCacheNames(null, List.of());
  }

  /**
   * Adds a name the cache layer admitted, so the spans of its commands carry it.
   *
   * @param name the cache name
   */
  public void add(String name) {
    adding.lock();
    try {
      if (names.add(name)) {
        matcher.set(new CacheKeyMatcher(keyPrefix, names));
      }
    } finally {
      adding.unlock();
    }
  }

  /**
   * Returns the known cache a key belongs to.
   *
   * @param key the key's bytes, read without moving the buffer's position; may be {@code null}
   * @return the cache name, or {@code null} when the key belongs to no known cache
   */
  public @Nullable String cacheNameOf(@Nullable ByteBuffer key) {
    return matcher.get().cacheNameOf(key);
  }
}
