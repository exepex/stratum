package com.exepex.stratum.restclient.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.core.IntervalFunction;
import java.time.Duration;
import java.util.stream.IntStream;
import lombok.experimental.UtilityClass;

/**
 * Checks of a back-off's waits, which are spread at random: each wait is drawn many times and must
 * stay within the bounds of its policy, so that a right back-off always passes and a wrong one
 * fails on almost every run.
 */
@UtilityClass
public class BackOffChecks {

  /** How many times each attempt's wait is drawn. */
  private static final int DRAWS = 1000;

  /** The attempts whose waits are checked, past the growth of every back-off the tests use. */
  private static final int ATTEMPTS = 8;

  /** How far each wait is spread at random: up to half of it either way. */
  private static final double SPREAD = 0.5;

  /**
   * Checks that every wait grows from {@code delay} by {@code multiplier}, spread by up to half,
   * and stays at most {@code longest}.
   *
   * @param backOff the back-off
   * @param delay the first wait before its spread
   * @param multiplier how much each wait grows
   * @param longest the longest wait
   */
  public static void assertExponentialWaits(
      IntervalFunction backOff, Duration delay, double multiplier, Duration longest) {
    IntStream.rangeClosed(1, ATTEMPTS)
        .forEach(
            attempt ->
                assertWaitsWithin(
                    backOff,
                    attempt,
                    delay.toMillis() * Math.pow(multiplier, attempt - 1.0),
                    longest.toMillis()));
  }

  /**
   * Checks that every wait is {@code delay}, spread by up to half, however many attempts came
   * before.
   *
   * @param backOff the back-off
   * @param delay the wait before its spread
   */
  public static void assertFixedWaits(IntervalFunction backOff, Duration delay) {
    IntStream.rangeClosed(1, ATTEMPTS)
        .forEach(attempt -> assertWaitsWithin(backOff, attempt, delay.toMillis(), Long.MAX_VALUE));
  }

  /**
   * Checks that one attempt's waits stay within half of {@code base} either way, Resilience4j's
   * rounding included, and at most {@code longest}.
   */
  private static void assertWaitsWithin(
      IntervalFunction backOff, int attempt, double base, long longest) {
    var least = (long) Math.min(base * (1 - SPREAD), longest);
    var most = (long) Math.min(base * (1 + SPREAD) + 1, longest);

    assertThat(IntStream.range(0, DRAWS).mapToLong(draw -> backOff.apply(attempt)))
        .allSatisfy(wait -> assertThat(wait).isBetween(least, most));
  }
}
