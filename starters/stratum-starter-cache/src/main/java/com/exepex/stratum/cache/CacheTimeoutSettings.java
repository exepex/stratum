package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * The settings the command timeouts of single caches depend on: Spring Boot's global timeout, each
 * cache's own value and the key prefix their keys start with.
 *
 * @param global {@code spring.data.redis.timeout}, which Stratum sets to 1s unless the service sets
 *     it, or Lettuce's own 60 seconds when it is unset
 * @param cacheTimeouts the caches that set their own {@code command-timeout}, each with its value
 * @param keyPrefix the text before the cache name in a key: {@code spring.cache.redis.key-prefix},
 *     empty when unset, or {@code null} with {@code spring.cache.redis.use-key-prefix=false}
 */
@StratumInternal
public record CacheTimeoutSettings(
    Duration global, Map<String, Duration> cacheTimeouts, @Nullable String keyPrefix) {

  /** Keeps each cache's value a fixed map. */
  public CacheTimeoutSettings {
    cacheTimeouts = Map.copyOf(cacheTimeouts);
  }

  /**
   * Returns the settings of the service.
   *
   * @param environment the service's environment
   * @return the settings
   */
  public static CacheTimeoutSettings resolve(Environment environment) {
    var binder = Binder.get(environment);
    return new CacheTimeoutSettings(
        binder
            .bind(CacheConstants.REDIS_TIMEOUT, Duration.class)
            .orElse(CacheConstants.LETTUCE_DEFAULT_TIMEOUT),
        StratumCacheProperties.resolve(environment).commandTimeouts(),
        // Spring's Boolean return is nullable for SpotBugs, so the comparison avoids unboxing it.
        Boolean.TRUE.equals(binder.bind(CacheConstants.USE_KEY_PREFIX, Boolean.class).orElse(true))
            ? binder.bind(CacheConstants.KEY_PREFIX, String.class).orElse("")
            : null);
  }

  /**
   * Returns the caches whose own value is below the global one: their timeout says nothing about
   * Redis as a whole.
   *
   * @return the names of those caches
   */
  public Set<String> shortTimeoutCaches() {
    return cacheTimeouts.entrySet().stream()
        .filter(entry -> entry.getValue().compareTo(global) < 0)
        .map(Map.Entry::getKey)
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Returns a matcher that finds the caches with their own value in a key.
   *
   * @return the matcher
   */
  public CacheKeyMatcher matcher() {
    return new CacheKeyMatcher(keyPrefix, cacheTimeouts.keySet());
  }
}
