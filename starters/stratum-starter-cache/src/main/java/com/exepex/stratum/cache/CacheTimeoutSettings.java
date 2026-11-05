package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.CoreErrorCode;
import com.exepex.stratum.core.error.StratumException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Builder;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * The settings the command timeouts of single caches depend on, and every fact derived from them:
 * Spring Boot's global timeout, each cache's own value and the key prefix their keys start with.
 *
 * @param global {@code spring.data.redis.timeout}, which Stratum sets to 1s unless the service sets
 *     it, or Lettuce's own 60 seconds when it is unset
 * @param cacheTimeouts the caches that set their own {@code command-timeout}, each with its value
 * @param keyPrefix the text before the cache name in a key: {@code spring.cache.redis.key-prefix},
 *     empty when unset, or {@code null} with {@code spring.cache.redis.use-key-prefix=false}
 */
@StratumInternal
@Builder(access = AccessLevel.PACKAGE)
public record CacheTimeoutSettings(
    Duration global, Map<String, Duration> cacheTimeouts, @Nullable String keyPrefix) {

  /** The cap on a cache's own timeout while the global timeout is lower. */
  static final Duration MAX_CACHE_TIMEOUT = Duration.ofSeconds(10);

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
    return CacheTimeoutSettings.builder()
        .global(
            binder
                .bind(CacheConstants.REDIS_TIMEOUT, Duration.class)
                .orElse(CacheConstants.LETTUCE_DEFAULT_TIMEOUT))
        .cacheTimeouts(StratumCacheProperties.resolve(environment).commandTimeouts())
        // Spring's Boolean return is nullable for SpotBugs, so the comparison avoids unboxing it.
        .keyPrefix(
            Boolean.TRUE.equals(
                    binder.bind(CacheConstants.USE_KEY_PREFIX, Boolean.class).orElse(true))
                ? binder.bind(CacheConstants.KEY_PREFIX, String.class).orElse("")
                : null)
        .build();
  }

  /**
   * Stops startup when a cache's own timeout is above the larger of 10 seconds and the global one,
   * because the largest value also bounds every connect handshake. The message names the key
   * without the cache name, which may be secret.
   *
   * @throws StratumException {@code SX-CORE-1003} if a value is above the cap
   */
  public void rejectAboveCap() {
    var cap = global.compareTo(MAX_CACHE_TIMEOUT) > 0 ? global : MAX_CACHE_TIMEOUT;
    if (cacheTimeouts.values().stream().anyMatch(timeout -> timeout.compareTo(cap) > 0)) {
      throw new StratumException(
          CoreErrorCode.INVALID_SETTINGS,
          CacheConstants.CACHES
              + "[*].command-timeout is above "
              + cap
              + ", the larger of 10s and "
              + CacheConstants.REDIS_TIMEOUT);
    }
  }

  /**
   * Returns the timeout Lettuce and Spring Data wait for: the largest of all values.
   *
   * @return the largest cache value above the global one, else the global one
   */
  public Duration sharedTimeout() {
    return cacheTimeouts.values().stream()
        .filter(timeout -> timeout.compareTo(global) > 0)
        .max(Duration::compareTo)
        .orElse(global);
  }

  /**
   * Returns the timeout of every command that belongs to no cache with its own value, as Lettuce
   * reads it.
   *
   * @return the global timeout in whole ms, at least 1, or 0 (no timeout) when it is 0
   */
  public long globalMillis() {
    return lettuceMillis(global);
  }

  /**
   * Returns each cache's own timeout as Lettuce reads it.
   *
   * @return each cache's value in whole ms, at least 1
   */
  public Map<String, Long> millisByCache() {
    return cacheTimeouts.entrySet().stream()
        .collect(
            Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> lettuceMillis(entry.getValue())));
  }

  /**
   * Returns the caches whose own value is below the global one, or every cache with its own value
   * when the global one is 0 (no timeout): their timeout says nothing about Redis as a whole. None
   * when keys carry no cache name, because then every command gets the global timeout.
   *
   * @return the names of those caches
   */
  public Set<String> shortTimeoutCaches() {
    if (keyPrefix == null) {
      return Set.of();
    }
    var noGlobalTimeout = globalMillis() == 0;
    return cacheTimeouts.entrySet().stream()
        .filter(entry -> noGlobalTimeout || entry.getValue().compareTo(global) < 0)
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

  /** Returns a timeout in whole ms; never 0 above 0, because Lettuce reads 0 as no timeout. */
  private long lettuceMillis(Duration timeout) {
    return timeout.isPositive() ? Math.max(1, timeout.toMillis()) : 0;
  }
}
