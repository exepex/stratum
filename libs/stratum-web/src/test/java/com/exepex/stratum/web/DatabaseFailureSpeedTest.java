package com.exepex.stratum.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.test.CpuCostMeter;
import com.exepex.stratum.web.testsupport.DatabaseFailureTestData;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Catches a blow-up in reading a failure's causes, such as a cost that grows with the square of
 * their number. CI runners differ about 2.3 times in speed, so with a limit of 5 times the slowest
 * CI run it does not catch a constant-factor slowdown.
 */
@DisplayName("Reading a hostile chain of causes stays inside its CPU time limit")
class DatabaseFailureSpeedTest {

  /** Wrappers above the database error, far more than any real failure has. */
  private static final int LINKS = 10_000;

  /** Reads in each timed batch: a few milliseconds of work, which averages out noise. */
  private static final int CALLS_PER_BATCH = 20;

  /**
   * CPU time one read of the chain may take: 5 times M, where M = 5,598 µs is the best value of a
   * local run with coverage on, JDK 25, 2026-11-13; to be replaced by the largest best value of 3
   * CI runs.
   */
  private static final Duration LIMIT = Duration.of(27_990, ChronoUnit.MICROS);

  @Test
  @DisplayName("a duplicate key beneath 10,000 wrappers is found within the limit")
  void deepChainIsReadWithinLimit() {
    var failure =
        DatabaseFailureTestData.wrapped(LINKS, DatabaseFailureTestData.sqlFailure("23505"));
    assertThat(DatabaseFailure.of(failure)).hasValue(DatabaseFailure.CONFLICT);

    new CpuCostMeter(CALLS_PER_BATCH)
        .assertCpuCostWithin("10,000 wrappers", LIMIT, () -> DatabaseFailure.of(failure));
  }
}
