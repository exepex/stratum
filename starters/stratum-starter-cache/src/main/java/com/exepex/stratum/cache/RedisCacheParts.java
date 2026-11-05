package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import java.util.function.UnaryOperator;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;

/**
 * The parts of the cache layer that only Redis caches use: the retry gate, the typing of JSON
 * values and the cache names on Redis spans. Without Redis, each part leaves a cache as it is.
 */
@StratumInternal
@Builder(toBuilder = true, access = AccessLevel.PACKAGE)
public final class RedisCacheParts {

  /** Puts the Redis retry gate in front of a Redis cache; returns any other cache unchanged. */
  private final UnaryOperator<Cache> gate;

  /** Gives each call of a method its own value type, for caches that store values untyped. */
  @Getter private final CacheValueTyping typing;

  /** The cache names Redis command spans carry, which each admitted name joins. */
  private final RedisCacheNames spanNames;

  /**
   * Returns the parts of a service, each one it lacks replaced by a part that changes nothing.
   *
   * @param gate the Redis retry gate, or {@code null} when the service has none
   * @param typing the typing of JSON values, or {@code null} when the service has none
   * @param spanNames the cache names on Redis spans, or {@code null} when the service has none
   * @return the parts
   */
  public static RedisCacheParts of(
      @Nullable RedisRetryGate gate,
      @Nullable CacheValueTyping typing,
      @Nullable RedisCacheNames spanNames) {
    return new RedisCacheParts(
        gate == null ? UnaryOperator.identity() : gate::gated,
        typing == null ? CacheValueTyping.none() : typing,
        spanNames == null ? RedisCacheNames.none() : spanNames);
  }

  /**
   * Returns the parts of a service without Redis.
   *
   * @return the parts that change no cache
   */
  public static RedisCacheParts none() {
    return of(null, null, null);
  }

  /**
   * Returns a cache behind the Redis retry gate, when it is a Redis cache.
   *
   * @param cache a cache the cache manager made
   * @return the gated cache, or the same cache
   */
  public Cache gated(Cache cache) {
    return gate.apply(cache);
  }

  /**
   * Puts an admitted cache's name on the spans of its Redis commands.
   *
   * @param name the admitted name
   */
  public void nameOnSpans(String name) {
    spanNames.add(name);
  }
}
