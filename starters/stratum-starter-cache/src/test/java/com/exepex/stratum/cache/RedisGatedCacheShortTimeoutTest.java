package com.exepex.stratum.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.exepex.stratum.cache.testsupport.GatedCacheTestBase;
import com.exepex.stratum.cache.testsupport.RedisFailureTestData;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

@DisplayName("RedisGatedCache, for a cache whose own command timeout is below the global one")
class RedisGatedCacheShortTimeoutTest extends GatedCacheTestBase {

  /** The same Redis cache, whose timeouts never make the other caches skip Redis. */
  private final RedisGatedCache shortCache = new RedisGatedCache(redis, gate, false);

  /** Makes the calls skip Redis until the next trial is due, then lets the wait pass. */
  private void awaitTrial() {
    gate.admits();
    gate.failed(RedisFailureTestData.CONNECT_FAILED, true);
    clock.advance(RETRY_INTERVAL);
  }

  /** Asserts that the trial ended without putting Redis back, and the next call tries again. */
  private void assertTrialEnded() {
    assertThat(gate.isAvailable()).isFalse();
    assertThat(gate.admits()).isTrue();
  }

  @Test
  @DisplayName("its timeout on a trial ends the trial without putting Redis back")
  void timeoutEndsTrial() {
    awaitTrial();
    when(redis.get(KEY)).thenThrow(RedisFailureTestData.TIMED_OUT);

    assertThatExceptionOfType(DataAccessException.class).isThrownBy(() -> shortCache.get(KEY));

    assertTrialEnded();
  }

  @Test
  @DisplayName("its background timeout on a trial ends the trial without putting Redis back")
  void backgroundTimeoutEndsTrial() {
    awaitTrial();
    when(redis.retrieve(KEY))
        .thenReturn(
            CompletableFuture.failedFuture(
                new CompletionException(RedisFailureTestData.TIMED_OUT)));

    shortCache.retrieve(KEY);

    assertTrialEnded();
  }

  @Test
  @DisplayName("its timeout before a background read starts ends the trial the same way")
  void timeoutBeforeBackgroundReadEndsTrial() {
    awaitTrial();
    when(redis.retrieve(KEY)).thenThrow(RedisFailureTestData.TIMED_OUT);

    assertThatExceptionOfType(DataAccessException.class).isThrownBy(() -> shortCache.retrieve(KEY));

    assertTrialEnded();
  }

  @Test
  @DisplayName("while Redis is in use, its timeout leaves every cache using Redis")
  void timeoutKeepsRedis() {
    shortCache.get(KEY);
    when(redis.get(KEY)).thenThrow(RedisFailureTestData.TIMED_OUT);

    assertThatExceptionOfType(DataAccessException.class).isThrownBy(() -> shortCache.get(KEY));

    assertThat(gate.usesRedis()).isTrue();
  }

  @Test
  @DisplayName(
      "a clear's timeout makes every cache skip Redis, because its SCAN waits the global value")
  void clearTimeoutSkips() {
    shortCache.get(KEY);
    doThrow(RedisFailureTestData.TIMED_OUT).when(redis).clear();

    assertThatExceptionOfType(DataAccessException.class).isThrownBy(shortCache::clear);

    assertThat(gate.usesRedis()).isFalse();
  }

  @Test
  @DisplayName("an immediate clear's timeout makes every cache skip Redis the same way")
  void invalidateTimeoutSkips() {
    shortCache.get(KEY);
    when(redis.invalidate()).thenThrow(RedisFailureTestData.TIMED_OUT);

    assertThatExceptionOfType(DataAccessException.class).isThrownBy(shortCache::invalidate);

    assertThat(gate.usesRedis()).isFalse();
  }
}
