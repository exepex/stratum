package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.CoreErrorCode;
import com.exepex.stratum.core.error.StratumException;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.TimeoutOptions.TimeoutSource;
import io.lettuce.core.protocol.RedisCommand;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.LettuceClientConfigurationBuilder;

/**
 * Gives the Redis commands of each cache that sets {@code
 * stratum.cache.caches.<name>.command-timeout} their own timeout, higher or lower than {@code
 * spring.data.redis.timeout}, which every other command keeps. Lettuce has one timeout per
 * connection, so it is raised to the largest value, and Lettuce asks this source for each command's
 * own timeout. With no cache value set, it changes nothing. The largest value also bounds every
 * connect handshake, so each value is at most the larger of 10 seconds and {@code
 * spring.data.redis.timeout}.
 */
@StratumInternal
@Slf4j
public class CacheCommandTimeouts extends TimeoutSource
    implements LettuceClientConfigurationBuilderCustomizer, LettuceClientOptionsBuilderCustomizer {

  /** The cap on a cache's own timeout while the global timeout is lower. */
  static final Duration MAX_CACHE_TIMEOUT = Duration.ofSeconds(10);

  /**
   * The timeout of every command that belongs to no cache with its own value, in whole ms: at least
   * 1, or 0 (no timeout) when {@code spring.data.redis.timeout} is 0, as Lettuce reads it.
   */
  private final long globalMillis;

  /**
   * Each cache's own timeout, in whole ms and at least 1, because Lettuce reads 0 as no timeout.
   */
  private final Map<String, Long> millisByCache;

  /** Finds the cache of a command's key. */
  private final CacheKeyMatcher matcher;

  /** The timeout Lettuce and Spring Data wait for: the largest of all values. */
  private final Duration sharedTimeout;

  /**
   * Checks each cache's own timeout against the cap and prepares the timeouts.
   *
   * @param global {@code spring.data.redis.timeout}
   * @param cacheTimeouts the caches that set their own timeout, each with its value
   * @param matcher finds the cache of a key; it knows the names of {@code cacheTimeouts}
   * @throws StratumException {@code SX-CORE-1003} if a value is above the cap; the message names
   *     the key without the cache name, which may be secret
   */
  public CacheCommandTimeouts(
      Duration global, Map<String, Duration> cacheTimeouts, CacheKeyMatcher matcher) {
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
    this.globalMillis = global.isPositive() ? Math.max(1, global.toMillis()) : 0;
    this.millisByCache =
        Collections.unmodifiableMap(
            cacheTimeouts.entrySet().stream()
                .collect(
                    Collectors.toMap(
                        Map.Entry::getKey, entry -> Math.max(1, entry.getValue().toMillis()))));
    this.matcher = matcher;
    this.sharedTimeout =
        cacheTimeouts.values().stream()
            .filter(t -> t.compareTo(global) > 0)
            .max(Duration::compareTo)
            .orElse(global);
  }

  /**
   * Raises the connection's timeout to the largest cache value, so Spring Data waits for it, and
   * logs that it now also bounds every connect handshake.
   *
   * @param builder Spring Boot's builder of the Lettuce client configuration
   */
  @Override
  public void customize(LettuceClientConfigurationBuilder builder) {
    if (millisByCache.isEmpty()) {
      return;
    }
    builder.commandTimeout(sharedTimeout);
    if (sharedTimeout.toMillis() > globalMillis && log.isInfoEnabled()) {
      log.info(
          "Redis command timeout raised to {} for caches with a longer command-timeout; this also"
              + " bounds every Redis connect handshake",
          sharedTimeout);
    }
  }

  /**
   * Makes Lettuce ask this source for each command's timeout.
   *
   * @param builder Spring Boot's builder of the Lettuce client options
   */
  @Override
  public void customize(ClientOptions.Builder builder) {
    if (!millisByCache.isEmpty()) {
      builder.timeoutOptions(TimeoutOptions.builder().timeoutSource(this).build());
    }
  }

  /**
   * Returns the command's timeout: its cache's own value, else the global one. Never 0, which
   * Lettuce reads as no timeout, unless the global timeout is 0 itself.
   *
   * @param command the command, or {@code null} before it is known
   * @return the timeout in milliseconds
   */
  @Override
  public long getTimeout(@Nullable RedisCommand<?, ?, ?> command) {
    var args = command == null ? null : command.getArgs();
    var cacheName = args == null ? null : matcher.cacheNameOf(args.getFirstEncodedKey());
    return cacheName == null ? globalMillis : millisByCache.get(cacheName);
  }
}
