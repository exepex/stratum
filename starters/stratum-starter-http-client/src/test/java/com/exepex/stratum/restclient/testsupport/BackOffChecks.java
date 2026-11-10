package com.exepex.stratum.restclient.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.core.IntervalFunction;
import java.time.Duration;
import java.util.function.IntToLongFunction;
import java.util.stream.IntStream;
import lombok.experimental.UtilityClass;

/**
 * Checks of a back-off's waits, which are spread at random: each wait is drawn many times and must
 * stay within the bounds of its policy, and the first wait must not come out the same every time,
 * so that a right back-off always passes and a wrong one fails on almost every run.
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
   * Returns the waits of a Resilience4j back-off, by attempt number.
   *
   * @param backOff the back-off
   * @return the wait, in milliseconds, before each attempt number from 1
   */
  public static IntToLongFunction waitsOf(IntervalFunction backOff) {
    return backOff::apply;
  }

  /**
   * Checks that every wait grows from {@code delay} by {@code multiplier}, spread by up to half,
   * and stays at most {@code longest}, and that the first wait is spread at random.
   *
   * @param backOff the back-off: the wait, in milliseconds, before each attempt number from 1
   * @param delay the first wait before its spread
   * @param multiplier how much each wait grows
   * @param longest the longest wait
   */
  public static void assertExponentialWaits(
      IntToLongFunction backOff, Duration delay, double multiplier, Duration longest) {
    IntStream.rangeClosed(1, ATTEMPTS)
        .forEach(
            attempt ->
                assertWaitsWithin(
                    backOff,
                    attempt,
                    (double) delay.toMillis() * Math.pow(multiplier, attempt - 1.0),
                    longest.toMillis()));
    assertFirstWaitSpread(backOff);
  }

  /**
   * Checks that every wait is {@code delay}, spread by up to half, however many attempts came
   * before, and that the first wait is spread at random.
   *
   * @param backOff the back-off: the wait, in milliseconds, before each attempt number from 1
   * @param delay the wait before its spread
   */
  public static void assertFixedWaits(IntToLongFunction backOff, Duration delay) {
    IntStream.rangeClosed(1, ATTEMPTS)
        .forEach(
            attempt ->
                assertWaitsWithin(backOff, attempt, (double) delay.toMillis(), Long.MAX_VALUE));
    assertFirstWaitSpread(backOff);
  }

  /**
   * Checks that one attempt's waits stay within half of {@code base} either way, Resilience4j's
   * rounding included, and at most {@code longest}.
   */
  private static void assertWaitsWithin(
      IntToLongFunction backOff, int attempt, double base, long longest) {
    var least = (long) Math.min(base * (1 - SPREAD), (double) longest);
    var most = (long) Math.min(base * (1 + SPREAD) + 1, (double) longest);

    assertThat(IntStream.range(0, DRAWS).mapToLong(draw -> backOff.applyAsLong(attempt)))
        .allSatisfy(wait -> assertThat(wait).isBetween(least, most));
  }

  /**
   * Checks that the first wait, drawn many times, is not always the same. Only the first, because a
   * later exponential wait may reach the longest wait, where every draw is equal. A spread of half
   * a 200ms or longer wait gives at least 200 possible values, so a right back-off draws one value
   * 1000 times in a row with a chance below one in 10^2000.
   */
  private static void assertFirstWaitSpread(IntToLongFunction backOff) {
    assertThat(IntStream.range(0, DRAWS).mapToLong(draw -> backOff.applyAsLong(1)).distinct())
        .hasSizeGreaterThan(1);
  }
}
