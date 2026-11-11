package com.exepex.stratum.cache;

import static org.mockito.Mockito.mock;

import com.exepex.stratum.cache.testsupport.RecordingCacheCounters;
import com.exepex.stratum.test.CpuCostMeter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("Hashing a hostile 10 MB key stays inside its CPU time limit")
class SizeLimitedRedisCacheWriterSpeedTest {

  /**
   * Batches run first and not counted, so the JIT compiler has compiled the hashing code, also
   * while it is still busy with the tests run before: with 3, a full module run measured SHA-256
   * alone at 35 ms instead of 5 ms, because it was not compiled yet.
   */
  private static final int HASHING_WARM_UP_BATCHES = 20;

  /**
   * CPU time one 10 MB key may take, prefix search and SHA-256 included: 2 times M, where M =
   * 11.652 ms is the largest best value of 46 CI runs with coverage on (smallest 5.996 ms, median
   * 7.203 ms), CI runs 42809147469 to 42974791362 on JDK 25, 2026-11-10 and 2026-11-11. The runners
   * differ several-fold, so 2 rather than 5 times M keeps a 10-fold slowdown failing on every one
   * of them.
   */
  private static final Duration HOSTILE_KEY_LIMIT = Duration.of(23_303, ChronoUnit.MICROS);

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

  /** The writer the decorator wraps; a mock, so only the decorator's own work is timed. */
  private final RedisCacheWriter delegate = mock(RedisCacheWriter.class);

  /** The decorator with the default limits. */
  private final SizeLimitedRedisCacheWriter writer =
      new SizeLimitedRedisCacheWriter(
          delegate,
          StratumCacheProperties.resolve(new MockEnvironment()),
          new RecordingCacheCounters());

  /** Times one key per batch, after the hashing code's longer warm-up. */
  private final CpuCostMeter meter = new CpuCostMeter(1, HASHING_WARM_UP_BATCHES);

  @Test
  @DisplayName("a 10 MB key that holds the cache's name but no prefix is hashed within the limit")
  void hashesHostileKeyWithinLimit() {
    meter.assertCpuCostWithin(
        "a hostile 10 MB key", HOSTILE_KEY_LIMIT, () -> writer.get("products", HOSTILE_KEY));
  }
}
