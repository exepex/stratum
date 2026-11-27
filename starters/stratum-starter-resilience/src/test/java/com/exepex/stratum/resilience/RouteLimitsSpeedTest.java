package com.exepex.stratum.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.resilience.testsupport.ResilienceTestData;
import com.exepex.stratum.test.CpuCostMeter;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Catches a blow-up in finding a hostile path's route, such as a cost that grows with the square of
 * its segments. Each request pays this lookup, so a slow one is repeated in every service.
 */
@DisplayName("Finding the route of a hostile path stays inside its CPU time limit")
class RouteLimitsSpeedTest {

  /** Routes, far more than a service sets. */
  private static final int ROUTES = 50;

  /** Segments of the hostile path: 1,000 of 8 characters, about 8 KB, Tomcat's default limit. */
  private static final String HOSTILE_PATH = "/abcdefg".repeat(1_000);

  /** Lookups in each timed batch: a few milliseconds of work, which averages out noise. */
  private static final int CALLS_PER_BATCH = 200;

  /**
   * CPU time one lookup may take: 5 times M, where M = 23.5 µs is the best value of a local run
   * with coverage off, JDK 25, 2026-11-27, so a 10 times slower lookup fails.
   */
  private static final Duration LIMIT = Duration.of(118, ChronoUnit.MICROS);

  @Test
  @DisplayName("an 8 KB path of 1,000 segments is matched against 50 routes within the limit")
  void hostilePathWithinLimit() {
    var routes =
        IntStream.range(0, ROUTES)
            .mapToObj(route -> "/route" + route + "/{id}/**")
            .collect(Collectors.toMap(Function.identity(), route -> 1));
    var limits = new RouteLimits(ResilienceTestData.incoming(routes).build());
    assertThat(limits.find(HOSTILE_PATH)).isEmpty();

    new CpuCostMeter(CALLS_PER_BATCH)
        .assertCpuCostWithin("an 8 KB path", LIMIT, () -> limits.find(HOSTILE_PATH));
  }
}
