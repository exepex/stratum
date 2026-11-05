package com.exepex.stratum.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.exepex.stratum.cache.testsupport.AdjustableClock;
import com.exepex.stratum.cache.testsupport.RecordingCacheCounters;
import com.exepex.stratum.cache.testsupport.RedisFailureTestData;
import com.exepex.stratum.test.LogLineCapture;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.cache.RedisCache;

@DisplayName("RedisRetryGate makes Redis caches skip Redis for a while after it fails")
class RedisRetryGateTest {

  /** How long Redis is skipped. */
  private static final Duration RETRY_INTERVAL = Duration.ofSeconds(5);

  /** How often the warning repeats at most. */
  private static final Duration WARNING_INTERVAL = Duration.ofMinutes(1);

  /** When the tests start. */
  private static final Instant START = Instant.parse("2026-11-04T08:00:00Z");

  private final AdjustableClock clock = new AdjustableClock(START);

  private final RecordingCacheCounters counters = new RecordingCacheCounters();

  private final RedisRetryGate gate =
      new RedisRetryGate(
          RETRY_INTERVAL,
          clock,
          new WarningThrottle(WARNING_INTERVAL, clock, 1),
          counters,
          Set.of());

  private final LogLineCapture warnings = new LogLineCapture(RedisRetryGate.class.getName());

  /** Captures the gate's warnings. */
  @BeforeEach
  void startCapture() {
    warnings.start();
  }

  /** Stops capturing, so other tests' lines are not kept. */
  @AfterEach
  void stopCapture() {
    warnings.stop();
  }

  /** Lets the first call reach Redis, so every call uses it. */
  private void reachRedis() {
    assertThat(gate.admits()).isTrue();
    gate.reached();
  }

  @Nested
  @DisplayName("before the first call reached Redis")
  class Starting {

    @Test
    @DisplayName("only the first call tries Redis; once it is reached, every call uses Redis")
    void oneCallTriesFirst() {
      assertThat(gate.admits()).isTrue();
      assertThat(gate.admits()).isFalse();
      assertThat(gate.isAvailable()).isTrue();

      gate.reached();

      assertThat(gate.admits()).isTrue();
      assertThat(gate.admits()).isTrue();
      assertThat(gate.usesRedis()).isTrue();
    }

    @Test
    @DisplayName("a trial that never reports is replaced after the retry interval")
    void lostTrialIsReplaced() {
      assertThat(gate.admits()).isTrue();

      clock.advance(RETRY_INTERVAL);

      assertThat(gate.admits()).isTrue();
      assertThat(gate.admits()).isFalse();
    }
  }

  @Nested
  @DisplayName("when Redis cannot be reached")
  class Unreachable {

    /** The failures that say Redis cannot be reached, so the calls skip it and it is counted. */
    private static final List<RuntimeException> UNREACHABLE_FAILURES =
        List.of(
            RedisFailureTestData.CONNECT_FAILED,
            RedisFailureTestData.REJECTED,
            RedisFailureTestData.DISCONNECTED);

    @ParameterizedTest(name = "{0}")
    @FieldSource("UNREACHABLE_FAILURES")
    @DisplayName("calls skip Redis, the gauge reads 0, it is counted and warned with SX-CACHE-1006")
    void callsSkipRedis(RuntimeException failure) {
      reachRedis();

      gate.failed(failure, true);

      assertThat(gate.admits()).isFalse();
      assertThat(gate.usesRedis()).isFalse();
      assertThat(gate.isAvailable()).isFalse();
      assertThat(counters.redisConnectFailures()).isOne();
      assertThat(warnings.onlyLine().getFormattedMessage())
          .startsWith("SX-CACHE-1006")
          .contains("next try in PT5S");
    }

    @Test
    @DisplayName("after the retry interval one call tries again, and a success puts Redis back")
    void oneCallRetries() {
      reachRedis();
      gate.failed(RedisFailureTestData.CONNECT_FAILED, true);

      clock.advance(RETRY_INTERVAL.minusMillis(1));
      assertThat(gate.admits()).isFalse();
      clock.advance(Duration.ofMillis(1));
      assertThat(gate.admits()).isTrue();
      assertThat(gate.admits()).isFalse();

      gate.reached();

      assertThat(gate.usesRedis()).isTrue();
      assertThat(gate.isAvailable()).isTrue();
    }

    @Test
    @DisplayName("a failed retry skips Redis again and counts, but warns only once per interval")
    void failedRetryCountsAgain() {
      reachRedis();
      gate.failed(RedisFailureTestData.CONNECT_FAILED, true);
      clock.advance(RETRY_INTERVAL);
      assertThat(gate.admits()).isTrue();

      gate.failed(RedisFailureTestData.REJECTED, true);

      assertThat(gate.admits()).isFalse();
      assertThat(counters.redisConnectFailures()).isEqualTo(2);
      assertThat(warnings.lines()).hasSize(1);
    }

    @Test
    @DisplayName("a late failure while calls skip Redis neither extends the wait nor counts")
    void lateFailureChangesNothing() {
      reachRedis();
      gate.failed(RedisFailureTestData.CONNECT_FAILED, true);
      clock.advance(RETRY_INTERVAL.minusSeconds(1));

      gate.failed(RedisFailureTestData.DISCONNECTED, true);
      clock.advance(Duration.ofSeconds(1));

      assertThat(gate.admits()).isTrue();
      assertThat(counters.redisConnectFailures()).isOne();
    }
  }

  @Nested
  @DisplayName("when a command times out")
  class TimedOut {

    @Test
    @DisplayName("calls skip Redis and it is warned, but not counted as a connect failure")
    void callsSkipWithoutConnectFailure() {
      reachRedis();

      gate.failed(RedisFailureTestData.TIMED_OUT, true);

      assertThat(gate.admits()).isFalse();
      assertThat(gate.isAvailable()).isFalse();
      assertThat(counters.redisConnectFailures()).isZero();
      assertThat(warnings.lines()).hasSize(1);
    }

    @Test
    @DisplayName(
        "a cache whose own timeout is below the global one times out, and calls keep using Redis")
    void shortTimeoutKeepsRedis() {
      reachRedis();

      gate.failed(RedisFailureTestData.TIMED_OUT, false);

      assertThat(gate.usesRedis()).isTrue();
      assertThat(gate.isAvailable()).isTrue();
      assertThat(warnings.lines()).isEmpty();
    }

    @Test
    @DisplayName(
        "a trial ended by such a timeout neither puts Redis back nor waits: the next call tries")
    void shortTimeoutEndsTrial() {
      reachRedis();
      gate.failed(RedisFailureTestData.CONNECT_FAILED, true);
      clock.advance(RETRY_INTERVAL);
      assertThat(gate.admits()).isTrue();

      gate.failed(RedisFailureTestData.TIMED_OUT, false);

      assertThat(gate.usesRedis()).isFalse();
      assertThat(gate.isAvailable()).isFalse();
      assertThat(gate.admits()).isTrue();
    }
  }

  @Nested
  @DisplayName("when Redis answered with an error")
  class Answered {

    @Test
    @DisplayName("calls keep using Redis, and a trial with that answer puts Redis back")
    void redisWasReached() {
      reachRedis();
      gate.failed(RedisFailureTestData.ANSWERED, true);
      assertThat(gate.usesRedis()).isTrue();

      gate.failed(RedisFailureTestData.CONNECT_FAILED, true);
      clock.advance(RETRY_INTERVAL);
      assertThat(gate.admits()).isTrue();
      gate.failed(RedisFailureTestData.ANSWERED, true);

      assertThat(gate.usesRedis()).isTrue();
      assertThat(warnings.lines()).hasSize(1);
    }
  }

  @Nested
  @DisplayName("put in front of a cache")
  class Gated {

    @Test
    @DisplayName("a Redis cache is gated")
    void redisCacheIsGated() {
      var redisCache = mock(RedisCache.class);
      when(redisCache.getName()).thenReturn("products");

      assertThat(gate.gated(redisCache)).isInstanceOf(RedisGatedCache.class);
    }

    @Test
    @DisplayName(
        "a cache whose own command timeout is below the global one never makes Redis skipped")
    void shortTimeoutCacheLeavesGateShut() {
      var shortTimeoutGate =
          new RedisRetryGate(
              RETRY_INTERVAL,
              clock,
              new WarningThrottle(WARNING_INTERVAL, clock, 1),
              counters,
              Set.of("quotes"));
      var redisQuotes = mock(RedisCache.class);
      when(redisQuotes.getName()).thenReturn("quotes");
      when(redisQuotes.get("sku-1")).thenThrow(RedisFailureTestData.TIMED_OUT);
      var gated = shortTimeoutGate.gated(redisQuotes);
      gated.get("sku-2");

      assertThatExceptionOfType(DataAccessException.class).isThrownBy(() -> gated.get("sku-1"));

      assertThat(shortTimeoutGate.usesRedis()).isTrue();
    }

    @Test
    @DisplayName("any other cache is returned unchanged, so it never skips because Redis is down")
    void otherCacheIsUnchanged() {
      var localCache = new ConcurrentMapCache("products");

      assertThat(gate.gated(localCache)).isSameAs(localCache);
    }
  }
}
