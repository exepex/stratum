package com.exepex.stratum.cache.testsupport;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.exepex.stratum.cache.RedisRetryGate;
import com.exepex.stratum.cache.WarningThrottle;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.cache.Cache;

/**
 * The set-up the tests of a gated Redis cache share: a mocked Redis cache named {@code products}
 * behind a real gate, on a clock the tests move.
 */
public class GatedCacheTestBase {

  /** The key the tests use. */
  protected static final String KEY = "sku-1";

  /** How long Redis is skipped. */
  protected static final Duration RETRY_INTERVAL = Duration.ofSeconds(5);

  /** The clock of the gate. */
  protected final AdjustableClock clock =
      new AdjustableClock(Instant.parse("2026-11-04T08:00:00Z"));

  /** The counters the gate reports to. */
  protected final RecordingCacheCounters counters = new RecordingCacheCounters();

  /** The gate in front of Redis. */
  protected final RedisRetryGate gate =
      new RedisRetryGate(
          RETRY_INTERVAL,
          clock,
          new WarningThrottle(Duration.ofMinutes(1), clock, 1),
          counters,
          Set.of());

  /** The Redis cache behind the gate. */
  protected final Cache redis = mock(Cache.class);

  /** Gives the Redis cache its name, as every cache has one. */
  @BeforeEach
  protected void nameTheCache() {
    when(redis.getName()).thenReturn("products");
  }
}
