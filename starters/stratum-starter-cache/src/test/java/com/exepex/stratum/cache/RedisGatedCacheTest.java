package com.exepex.stratum.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.exepex.stratum.cache.testsupport.AdjustableClock;
import com.exepex.stratum.cache.testsupport.RecordingCacheCounters;
import com.exepex.stratum.cache.testsupport.RedisFailureTestData;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.Cache.ValueRetrievalException;
import org.springframework.dao.DataAccessException;

@DisplayName("RedisGatedCache calls Redis only when the gate lets it")
class RedisGatedCacheTest {

  /** The key the tests use. */
  private static final String KEY = "sku-1";

  /** The value the loaders return. */
  private static final String VALUE = "price-of-sku-1";

  /** How long Redis is skipped. */
  private static final Duration RETRY_INTERVAL = Duration.ofSeconds(5);

  private final AdjustableClock clock = new AdjustableClock(Instant.parse("2026-11-04T08:00:00Z"));

  private final RecordingCacheCounters counters = new RecordingCacheCounters();

  private final RedisRetryGate gate =
      new RedisRetryGate(
          RETRY_INTERVAL, clock, new WarningThrottle(Duration.ofMinutes(1), clock, 1), counters);

  /** The Redis cache behind the gate. */
  private final Cache redis = mock(Cache.class);

  private final RedisGatedCache cache = new RedisGatedCache(redis, gate);

  /** Gives the Redis cache its name, as every cache has one. */
  @BeforeEach
  void nameTheCache() {
    when(redis.getName()).thenReturn("products");
  }

  @Nested
  @DisplayName("while Redis is in use")
  class UsingRedis {

    @Test
    @DisplayName("every call reaches Redis")
    void callsReachRedis() {
      cache.get(KEY);
      cache.put(KEY, VALUE);
      cache.evict(KEY);
      cache.clear();

      verify(redis).get(KEY);
      verify(redis).put(KEY, VALUE);
      verify(redis).evict(KEY);
      verify(redis).clear();
    }

    @Test
    @DisplayName("a Redis failure reaches the caller unchanged and makes the next calls skip Redis")
    void failureIsRethrownAndSkips() {
      when(redis.get(KEY)).thenThrow(RedisFailureTestData.CONNECT_FAILED);

      assertThatExceptionOfType(DataAccessException.class)
          .isThrownBy(() -> cache.get(KEY))
          .isSameAs(RedisFailureTestData.CONNECT_FAILED);
      assertThat(gate.usesRedis()).isFalse();
      assertThat(counters.redisConnectFailures()).isOne();
    }

    @Test
    @DisplayName("a background read that fails in Redis makes the next calls skip Redis")
    void backgroundFailureSkips() {
      cache.get(KEY);
      when(redis.retrieve(KEY))
          .thenReturn(
              CompletableFuture.failedFuture(
                  new CompletionException(RedisFailureTestData.CONNECT_FAILED)));

      cache.retrieve(KEY);

      assertThat(gate.usesRedis()).isFalse();
      assertThat(counters.redisConnectFailures()).isOne();
    }
  }

  @Nested
  @DisplayName("while Redis is skipped")
  class SkippingRedis {

    /** Makes the calls skip Redis, as after a failed connect. */
    private void skipRedis() {
      gate.admits();
      gate.failed(RedisFailureTestData.CONNECT_FAILED);
    }

    @Test
    @DisplayName("a read misses and a write, evict or clear is dropped, all without Redis")
    void callsSkipRedis() {
      skipRedis();

      assertThat(cache.get(KEY)).isNull();
      assertThat(cache.get(KEY, String.class)).isNull();
      assertThat(cache.putIfAbsent(KEY, VALUE)).isNull();
      assertThat(cache.evictIfPresent(KEY)).isFalse();
      assertThat(cache.invalidate()).isFalse();
      assertThat(cache.retrieve(KEY)).isNull();
      cache.put(KEY, VALUE);
      cache.evict(KEY);
      cache.clear();

      verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("a loading read runs the loader once without Redis")
    void loaderRunsWithoutRedis() {
      skipRedis();

      assertThat(cache.get(KEY, () -> VALUE)).isEqualTo(VALUE);
      assertThat(cache.retrieve(KEY, () -> CompletableFuture.completedFuture(VALUE)))
          .isCompletedWithValue(VALUE);
      verify(redis, atLeastOnce()).getName();
      verifyNoMoreInteractions(redis);
    }

    @Test
    @DisplayName("a loader's error reaches the caller wrapped, as a Redis cache wraps it")
    void loaderErrorIsWrapped() {
      skipRedis();
      var loaderFailure = new IllegalStateException("price service down");
      Callable<String> failingLoader =
          () -> {
            throw loaderFailure;
          };

      assertThatExceptionOfType(ValueRetrievalException.class)
          .isThrownBy(() -> cache.get(KEY, failingLoader))
          .withCause(loaderFailure);
    }

    @Test
    @DisplayName(
        "after the retry interval a write tries Redis with an immediate evict, then writes")
    void writeTriesWithImmediateEvict() {
      skipRedis();
      clock.advance(RETRY_INTERVAL);

      cache.put(KEY, VALUE);

      verify(redis).evictIfPresent(KEY);
      verify(redis).put(KEY, VALUE);
      assertThat(gate.usesRedis()).isTrue();
    }

    @Test
    @DisplayName("an evict or clear that tries Redis is sent in its immediate form")
    void evictAndClearTryImmediately() {
      skipRedis();
      clock.advance(RETRY_INTERVAL);
      cache.evict(KEY);
      skipRedis();
      clock.advance(RETRY_INTERVAL);
      cache.clear();

      verify(redis).evictIfPresent(KEY);
      verify(redis).invalidate();
      verify(redis, never()).evict(any());
      verify(redis, never()).clear();
    }

    @Test
    @DisplayName("a write whose trial fails is not sent and Redis stays skipped")
    void failedWriteTrialDropsWrite() {
      skipRedis();
      clock.advance(RETRY_INTERVAL);
      when(redis.evictIfPresent(KEY)).thenThrow(RedisFailureTestData.CONNECT_FAILED);

      assertThatExceptionOfType(DataAccessException.class).isThrownBy(() -> cache.put(KEY, VALUE));
      verify(redis, never()).put(any(), any());
      assertThat(gate.usesRedis()).isFalse();
    }

    @Test
    @DisplayName(
        "a loader's error during a trial releases it, so the next call tries again at once")
    void loaderErrorReleasesTrial() {
      skipRedis();
      clock.advance(RETRY_INTERVAL);
      var loaderFailure =
          new ValueRetrievalException(KEY, () -> VALUE, new IllegalStateException());
      Callable<String> loader = () -> VALUE;
      when(redis.get(KEY, loader)).thenThrow(loaderFailure);

      assertThatExceptionOfType(ValueRetrievalException.class)
          .isThrownBy(() -> cache.get(KEY, loader))
          .isSameAs(loaderFailure);
      assertThat(gate.usesRedis()).isFalse();
      cache.get(KEY);
      verify(redis).get(KEY);
      assertThat(gate.usesRedis()).isTrue();
    }

    @Test
    @DisplayName("a loader's error from an older call never puts Redis back in use during a trial")
    void olderLoaderErrorDoesNotOpen() {
      skipRedis();
      clock.advance(RETRY_INTERVAL);
      gate.admits();

      gate.released();

      assertThat(gate.usesRedis()).isFalse();
      assertThat(gate.isAvailable()).isFalse();
    }
  }

  @Nested
  @DisplayName("when the service starts")
  class Starting {

    @Test
    @DisplayName("a first write reaches Redis, so it is not lost while Redis is healthy")
    void firstWriteReachesRedis() {
      cache.put(KEY, VALUE);

      verify(redis).evictIfPresent(KEY);
      verify(redis).put(KEY, VALUE);
      assertThat(gate.usesRedis()).isTrue();
    }

    @Test
    @DisplayName("a first background read tries Redis and puts it in use once Redis answers")
    void firstBackgroundReadReachesRedis() {
      var answer = new CompletableFuture<Object>();
      when(redis.retrieve(KEY)).thenAnswer(invocation -> answer);

      cache.retrieve(KEY);
      assertThat(gate.usesRedis()).isFalse();
      answer.complete(null);

      assertThat(gate.usesRedis()).isTrue();
    }

    @Test
    @DisplayName("a background loader's error releases the trial, so the next call tries at once")
    void backgroundLoaderErrorReleasesTrial() {
      Supplier<CompletableFuture<String>> loader = () -> CompletableFuture.completedFuture(VALUE);
      when(redis.retrieve(KEY, loader))
          .thenReturn(
              CompletableFuture.failedFuture(new CompletionException(new IllegalStateException())));

      cache.retrieve(KEY, loader);

      assertThat(gate.usesRedis()).isFalse();
      assertThat(gate.admits()).isTrue();
    }
  }
}
