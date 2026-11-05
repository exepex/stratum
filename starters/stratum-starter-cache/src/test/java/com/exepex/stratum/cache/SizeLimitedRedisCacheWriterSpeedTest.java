package com.exepex.stratum.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.exepex.stratum.cache.testsupport.RecordingCacheCounters;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.mock.env.MockEnvironment;

@Slf4j
@DisplayName("Hashing a hostile 10 MB key stays inside its CPU time limit")
class SizeLimitedRedisCacheWriterSpeedTest {

  /** Rounds run first and not counted, so the JIT compiler has compiled the hashing code. */
  private static final int WARM_UP_ROUNDS = 3;

  /** Rounds timed; the fastest counts, so one slow round on a busy build machine is ignored. */
  private static final int MEASURED_ROUNDS = 5;

  /** Nanoseconds in a millisecond, the unit the limit and the logged time are in. */
  private static final double NANOS_PER_MILLISECOND = 1_000_000.0;

  /**
   * CPU time one 10 MB key may take, prefix search and SHA-256 included: 10 times M = 8.752 ms, the
   * largest best value of 3 runs on a 4-CPU build machine (8.744, 8.752 and 8.610 ms), on JDK 25,
   * 2026-11-04.
   */
  private static final Duration HOSTILE_KEY_LIMIT = Duration.of(88, ChronoUnit.MILLIS);

  /** The size of the hostile key. */
  private static final int TEN_MEGABYTES = 10 * 1024 * 1024;

  /** What the hostile key repeats: the cache's name and one colon. */
  private static final String HOSTILE_UNIT = "products:";

  /**
   * A 10 MB key made of the cache's name without its {@code ::}, so the prefix search reads as far
   * as it may and finds nothing: the slowest key the writer can be given.
   */
  private static final byte[] HOSTILE_KEY =
      HOSTILE_UNIT.repeat(TEN_MEGABYTES / HOSTILE_UNIT.length()).getBytes(StandardCharsets.UTF_8);

  /** Times the CPU this test's thread uses, so time spent waiting is not counted. */
  private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();

  /** The writer the decorator wraps; a mock, so only the decorator's own work is timed. */
  private final RedisCacheWriter delegate = mock(RedisCacheWriter.class);

  /** The decorator with the default limits. */
  private final SizeLimitedRedisCacheWriter writer =
      new SizeLimitedRedisCacheWriter(
          delegate,
          StratumCacheProperties.resolve(new MockEnvironment()),
          new RecordingCacheCounters());

  @Test
  @DisplayName("a 10 MB key that holds the cache's name but no prefix is hashed within the limit")
  void hashesHostileKeyWithinLimit() {
    assertThat(threads.isCurrentThreadCpuTimeSupported())
        .as("CPU time of the current thread can be measured")
        .isTrue();

    IntStream.range(0, WARM_UP_ROUNDS).forEach(round -> cpuNanosPerKey());
    var bestMillis =
        IntStream.range(0, MEASURED_ROUNDS).mapToLong(round -> cpuNanosPerKey()).min().orElseThrow()
            / NANOS_PER_MILLISECOND;
    log.info("Best CPU milliseconds per hostile 10 MB key: {}", bestMillis);

    assertThat(bestMillis)
        .as("CPU milliseconds per key")
        .isLessThanOrEqualTo(HOSTILE_KEY_LIMIT.toNanos() / NANOS_PER_MILLISECOND);
  }

  /** Returns the CPU nanoseconds one read of the hostile key takes. */
  private long cpuNanosPerKey() {
    var start = threads.getCurrentThreadCpuTime();
    writer.get("products", HOSTILE_KEY);
    return threads.getCurrentThreadCpuTime() - start;
  }
}
