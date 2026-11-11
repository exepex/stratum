package com.exepex.stratum.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.test.CpuCostMeter;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.protocol.Command;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@DisplayName("Finding the timeout of a command with a hostile 10 MB key stays inside its CPU limit")
class CacheCommandTimeoutsSpeedTest {

  /**
   * Seconds after which the test stops and fails, so a search that reads the whole key fails
   * instead of running for hours.
   */
  private static final int STOP_AFTER_SECONDS = 60;

  /** Lookups per round, so a round lasts long enough to be timed. */
  private static final int LOOKUPS_PER_ROUND = 100_000;

  /**
   * CPU time 100,000 lookups of the hostile key may take: 5 times M, where M = 12.105 ms is the
   * largest best value of 44 CI runs with coverage on (smallest 3.829 ms, median 8.223 ms), CI runs
   * 42809147469 to 42918242941 on JDK 25, 2026-11-10.
   */
  private static final Duration HOSTILE_KEY_LIMIT = Duration.of(60_523, ChronoUnit.MICROS);

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

  /** The long-named cache's own timeout, above the global one. */
  private static final Duration CACHE_TIMEOUT = Duration.ofSeconds(3);

  /** Timeouts for one cache with a long name, the most the search may read. */
  private final CacheCommandTimeouts timeouts =
      new CacheCommandTimeouts(
          CacheTimeoutSettings.builder()
              .global(GLOBAL)
              .cacheTimeouts(Map.of("monthly-sales-reports-by-region", CACHE_TIMEOUT))
              .keyPrefix(PREFIX)
              .build());

  /** A GET of the hostile key, as Spring Data sends it. */
  private final Command<byte[], byte[], String> hostileGet =
      new Command<>(
          CommandType.GET,
          new StatusOutput<>(ByteArrayCodec.INSTANCE),
          new CommandArgs<>(ByteArrayCodec.INSTANCE).addKey(HOSTILE_KEY));

  /** Times one round of lookups per batch, after the default warm-up. */
  private final CpuCostMeter meter = new CpuCostMeter(1);

  @Test
  @Timeout(value = STOP_AFTER_SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  @DisplayName("a 10 MB key of the service's own gets the global timeout within the limit")
  void findsTimeoutWithinLimit() {
    assertThat(timeouts.getTimeout(hostileGet)).isEqualTo(GLOBAL.toMillis());

    meter.assertCpuCostWithin(
        LOOKUPS_PER_ROUND + " hostile lookups", HOSTILE_KEY_LIMIT, this::lookUpRound);
  }

  /** Looks up the hostile key's timeout once per round; the sum keeps the JIT from dropping it. */
  private void lookUpRound() {
    var sum =
        IntStream.range(0, LOOKUPS_PER_ROUND)
            .mapToLong(lookup -> timeouts.getTimeout(hostileGet))
            .sum();
    assertThat(sum).isPositive();
  }
}
