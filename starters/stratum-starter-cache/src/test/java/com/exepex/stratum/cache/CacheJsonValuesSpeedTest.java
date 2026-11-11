package com.exepex.stratum.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.cache.testsupport.values.CachedValuesTestData;
import com.exepex.stratum.cache.testsupport.values.Order;
import com.exepex.stratum.test.CpuCostMeter;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import tools.jackson.databind.JavaType;

@DisplayName("Reading hostile 1 MiB JSON values stays inside its CPU time limit")
class CacheJsonValuesSpeedTest {

  /** The size of each hostile value, within a limit of 2 MiB. */
  private static final int ONE_MEBIBYTE = 1024 * 1024;

  /** The JSON values, which allow only the test values' package. */
  private static final CacheJsonValues JSON = CachedValuesTestData.jsonValues();

  /** An order whose identifier is a 1 MiB string. */
  private static final CachedJson LONG_STRING =
      CachedValuesTestData.stored("{\"id\":\"" + "i".repeat(ONE_MEBIBYTE) + "\",\"quantity\":3}");

  /** An array of 1 MiB, of half a million one-digit numbers. */
  private static final CachedJson WIDE_ARRAY =
      CachedValuesTestData.stored("[" + "1,".repeat(ONE_MEBIBYTE / 2) + "1]");

  /**
   * CPU time one read of the 1 MiB string may take: 2 times M, where M = 4.525 ms is the largest
   * best value of 46 CI runs with coverage on (smallest 1.828 ms, median 2.670 ms), CI runs
   * 42809147469 to 42974791362 on JDK 25, 2026-11-10 and 2026-11-11. The runners differ
   * several-fold, so 2 rather than 5 times M keeps a 10-fold slowdown failing on every one of them.
   */
  private static final Duration LONG_STRING_LIMIT = Duration.of(9_050, ChronoUnit.MICROS);

  /**
   * CPU time one read of the 1 MiB array may take: 2 times M, where M = 42.615 ms is the largest
   * best value of 46 CI runs with coverage on (smallest 15.642 ms, median 29.361 ms), CI runs
   * 42809147469 to 42974791362 on JDK 25, 2026-11-10 and 2026-11-11. The runners differ
   * several-fold, so 2 rather than 5 times M keeps a 10-fold slowdown failing on every one of them.
   */
  private static final Duration WIDE_ARRAY_LIMIT = Duration.of(85_229, ChronoUnit.MICROS);

  /** The hostile values, each with its type and its CPU time limit. */
  private static final List<Arguments> HOSTILE_VALUES =
      List.of(
          Arguments.of(
              "a 1 MiB string",
              LONG_STRING,
              JSON.typeFactory().constructType(Order.class),
              LONG_STRING_LIMIT),
          Arguments.of(
              "an array of 1 MiB",
              WIDE_ARRAY,
              JSON.typeFactory().constructCollectionType(List.class, Long.class),
              WIDE_ARRAY_LIMIT));

  /** Times one read per batch, after the default warm-up. */
  private final CpuCostMeter meter = new CpuCostMeter(1);

  @ParameterizedTest(name = "{0}")
  @FieldSource("HOSTILE_VALUES")
  @DisplayName("each hostile value is read within its limit")
  void readsWithinLimit(String name, CachedJson value, JavaType type, Duration limit) {
    assertThat(JSON.read(value, type)).as("the value is read").isPresent();

    meter.assertCpuCostWithin("a read of " + name, limit, () -> JSON.read(value, type));
  }
}
