package com.exepex.stratum.cache.testsupport;

import com.exepex.stratum.cache.CacheCounters;
import com.exepex.stratum.cache.RedisRetryGate;
import com.exepex.stratum.cache.WarningThrottle;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import lombok.experimental.UtilityClass;

/** Builds the Redis retry gates the tests use, with the module's default intervals. */
@UtilityClass
public class RedisRetryGates {

  /** How long Redis is skipped: the default {@code redis-retry-interval}. */
  public static final Duration RETRY_INTERVAL = Duration.ofSeconds(5);

  /** How often the gate's warning repeats at most: the default {@code warning-interval}. */
  public static final Duration WARNING_INTERVAL = Duration.ofMinutes(1);

  /**
   * Returns a gate that skips Redis after the given failures in a row.
   *
   * @param failureThreshold how many failures in a row make the calls skip Redis
   * @param clock the clock the test moves
   * @param counters the counters the gate reports to
   * @param shortTimeoutCaches the caches whose own command timeout is below the global one
   * @return the gate
   */
  public static RedisRetryGate gate(
      int failureThreshold, Clock clock, CacheCounters counters, String... shortTimeoutCaches) {
    return new RedisRetryGate(
        RETRY_INTERVAL,
        failureThreshold,
        clock,
        new WarningThrottle(WARNING_INTERVAL, clock, 1),
        counters,
        Set.of(shortTimeoutCaches));
  }
}
