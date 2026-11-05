package com.exepex.stratum.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.protocol.Command;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandType;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Slf4j
@DisplayName("Finding the timeout of a command with a hostile 10 MB key stays inside its CPU limit")
class CacheCommandTimeoutsSpeedTest {

  /**
   * Seconds after which the test stops and fails, so a search that reads the whole key fails
   * instead of running for hours.
   */
  private static final int STOP_AFTER_SECONDS = 60;

  /** Rounds run first and not counted, so the JIT compiler has compiled the lookup. */
  private static final int WARM_UP_ROUNDS = 3;

  /** Rounds timed; the fastest counts, so one slow round on a busy build machine is ignored. */
  private static final int MEASURED_ROUNDS = 5;

  /** Lookups per round, so a round lasts long enough to be timed. */
  private static final int LOOKUPS_PER_ROUND = 100_000;

  /** Nanoseconds in a millisecond, the unit the limit and the logged time are in. */
  private static final double NANOS_PER_MILLISECOND = 1_000_000.0;

  /**
   * CPU time 100,000 lookups of the hostile key may take: 10 times M = 3.077 ms, the largest best
   * value of 3 runs on a 4-CPU build machine (3.077, 2.244 and 2.994 ms), on JDK 25, 2026-11-05.
   */
  private static final Duration HOSTILE_KEY_LIMIT = Duration.of(31, ChronoUnit.MILLIS);

  /** The size of the hostile key. */
  private static final int TEN_MEGABYTES = 10 * 1024 * 1024;

  /** The global {@code spring.data.redis.timeout}. */
  private static final Duration GLOBAL = Duration.ofSeconds(1);

  /** The service's key prefix. */
  private static final String PREFIX = "orders:";

  /**
   * A 10 MB key of the service's own, not a cache's: the prefix, then single colons, so the search
   * for {@code ::} after a cache name reads as far as it may and finds nothing.
   */
  private static final byte[] HOSTILE_KEY =
      (PREFIX + "x:".repeat((TEN_MEGABYTES - PREFIX.length()) / 2))
          .getBytes(StandardCharsets.UTF_8);

  /** Times the CPU this test's thread uses, so time spent waiting is not counted. */
  private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();

  /** Timeouts for one cache with a long name, the most the search may read. */
  private final CacheCommandTimeouts timeouts =
      new CacheCommandTimeouts(
          GLOBAL,
          Map.of("monthly-sales-reports-by-region", Duration.ofSeconds(3)),
          new CacheKeyMatcher(PREFIX, Set.of("monthly-sales-reports-by-region")));

  /** A GET of the hostile key, as Spring Data sends it. */
  private final Command<byte[], byte[], String> hostileGet =
      new Command<>(
          CommandType.GET,
          new StatusOutput<>(ByteArrayCodec.INSTANCE),
          new CommandArgs<>(ByteArrayCodec.INSTANCE).addKey(HOSTILE_KEY));

  @Test
  @Timeout(value = STOP_AFTER_SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  @DisplayName("a 10 MB key of the service's own gets the global timeout within the limit")
  void findsTimeoutWithinLimit() {
    assertThat(threads.isCurrentThreadCpuTimeSupported())
        .as("CPU time of the current thread can be measured")
        .isTrue();
    assertThat(timeouts.getTimeout(hostileGet)).isEqualTo(GLOBAL.toMillis());

    IntStream.range(0, WARM_UP_ROUNDS).forEach(round -> cpuNanosPerRound());
    var bestMillis =
        IntStream.range(0, MEASURED_ROUNDS)
                .mapToLong(round -> cpuNanosPerRound())
                .min()
                .orElseThrow()
            / NANOS_PER_MILLISECOND;
    log.info("Best CPU milliseconds per {} hostile lookups: {}", LOOKUPS_PER_ROUND, bestMillis);

    assertThat(bestMillis)
        .as("CPU milliseconds per round")
        .isLessThanOrEqualTo(HOSTILE_KEY_LIMIT.toNanos() / NANOS_PER_MILLISECOND);
  }

  /** Returns the CPU nanoseconds one round of lookups takes. */
  private long cpuNanosPerRound() {
    var start = threads.getCurrentThreadCpuTime();
    var sum =
        IntStream.range(0, LOOKUPS_PER_ROUND).mapToLong(i -> timeouts.getTimeout(hostileGet)).sum();
    assertThat(sum).isPositive();
    return threads.getCurrentThreadCpuTime() - start;
  }
}
