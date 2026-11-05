package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import java.util.function.UnaryOperator;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;

/**
 * The parts of the cache layer that only one kind of cache uses: for Redis, the retry gate, the
 * typing of JSON values and the cache names on Redis spans; for Caffeine, the answers to methods
 * that return a future or a reactive type. A part the service lacks leaves a cache as it is.
 */
@StratumInternal
@Builder(toBuilder = true, access = AccessLevel.PACKAGE)
public final class CacheTypeParts {

  /** Puts the Redis retry gate in front of a Redis cache; returns any other cache unchanged. */
  private final UnaryOperator<Cache> gate;

  /** Lets a Caffeine cache without Caffeine's async mode serve future and reactive methods. */
  private final UnaryOperator<Cache> futures;

  /** Gives each call of a method its own value type, for caches that store values untyped. */
  @Getter private final CacheValueTyping typing;

  /** The cache names Redis command spans carry, which each admitted name joins. */
  private final RedisCacheNames spanNames;

  /**
   * Returns the parts of a service, each one it lacks replaced by a part that changes nothing.
   *
   * @param gate the Redis retry gate, or {@code null} when the service has none
   * @param futures the Caffeine future answers, or {@code null} when the service has no Caffeine
   * @param typing the typing of JSON values, or {@code null} when the service has none
   * @param spanNames the cache names on Redis spans, or {@code null} when the service has none
   * @return the parts
   */
  public static CacheTypeParts of(
      @Nullable RedisRetryGate gate,
      @Nullable CaffeineFutures futures,
      @Nullable CacheValueTyping typing,
      @Nullable RedisCacheNames spanNames) {
    return builder()
        .gate(gate == null ? UnaryOperator.identity() : gate::gated)
        .futures(futures == null ? UnaryOperator.identity() : futures::servingFutures)
        .typing(typing == null ? CacheValueTyping.none() : typing)
        .spanNames(spanNames == null ? RedisCacheNames.none() : spanNames)
        .build();
  }

  /**
   * Returns the parts of a service without Redis or Caffeine.
   *
   * @return the parts that change no cache
   */
  public static CacheTypeParts none() {
    return of(null, null, null, null);
  }

  /**
   * Returns a cache with the parts of its kind: a Redis cache behind the retry gate, a Caffeine
   * cache able to serve future and reactive methods, any other cache unchanged.
   *
   * @param cache a cache the cache manager made
   * @return the adapted cache, or the same cache
   */
  public Cache adapted(Cache cache) {
    return futures.apply(gate.apply(cache));
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
