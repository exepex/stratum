package com.exepex.stratum.observability;

import static com.exepex.stratum.observability.testsupport.ObservabilityTestData.HOSTILE_INPUT_LENGTH;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.test.CpuCostMeter;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;

/**
 * Catches a blow-up in cleaning a 1MB URL, such as a cost that grows with the square of its length.
 * It does not catch a constant-factor slowdown: CI runners differ about 2.3 times in speed, so with
 * limits of 5 times the slowest CI run, even a URL parsed 10 times can pass on a fast runner.
 */
@DisplayName("Cleaning a 1MB URL stays inside its CPU time limit")
class TelemetryUrlFilterSpeedTest {

  // A 1MB URL takes milliseconds. With 30 calls a batch, the 3 warm-up batches bring the parser to
  // its steady cost, so a slower parser shows; with 3 calls, M was mostly JIT warm-up.
  /** Calls in each timed batch. */
  private static final int CALLS_PER_BATCH = 30;

  /**
   * CPU time cleaning a URL with a 1MB path may take: 5 times M, where M = 18840.079 µs is the
   * largest best value of 3 CI runs with coverage on (14625.357 µs, 11316.040 µs, 18840.079 µs), CI
   * run 42063752215 on JDK 25, 2026-11-05.
   */
  private static final Duration PATH_LIMIT = Duration.of(94_200_396, ChronoUnit.NANOS);

  /**
   * CPU time cleaning a URL with a 1MB query may take: 5 times M, where M = 19597.943 µs is the
   * largest best value of 3 CI runs with coverage on (15465.252 µs, 7782.930 µs, 19597.943 µs), CI
   * run 42063752215 on JDK 25, 2026-11-05.
   */
  private static final Duration QUERY_LIMIT = Duration.of(97_989_714, ChronoUnit.NANOS);

  /**
   * CPU time cleaning a URL with a 1MB user info may take: 5 times M, where M = 14499.435 µs is the
   * largest best value of 3 CI runs with coverage on (10621.095 µs, 7318.338 µs, 14499.435 µs), CI
   * run 42063752215 on JDK 25, 2026-11-05.
   */
  private static final Duration USER_INFO_LIMIT = Duration.of(72_497_174, ChronoUnit.NANOS);

  /** A short key-value pair, repeated to make a 1MB query. */
  private static final String SHORT_PAIR = "a=b&";

  /**
   * CPU time cleaning a URL whose 1MB query is one long run of digits, scanned for card numbers: 5
   * times M, where M = 10539.600 µs is the largest best value of 3 CI runs with coverage on
   * (10539.600 µs, 6568.018 µs, 8800.230 µs), CI runs 42651017565, 42652376396 and 42654113844 on
   * JDK 25, 2026-11-09.
   */
  private static final Duration DIGIT_QUERY_LIMIT = Duration.of(52_698_000, ChronoUnit.NANOS);

  /**
   * CPU time cleaning a URL whose 1MB query is a chain of short key-value pairs: 5 times M, where M
   * = 36640.779 µs is the largest best value of 3 CI runs with coverage on (27978.306 µs, 27392.549
   * µs, 36640.779 µs), CI runs 42651017565, 42652376396 and 42654113844 on JDK 25, 2026-11-09.
   */
  private static final Duration PAIR_CHAIN_QUERY_LIMIT = Duration.of(183_203_894, ChronoUnit.NANOS);

  /**
   * CPU time cleaning a URL whose 1MB query is full of {@code @}, scanned for email addresses: 5
   * times M, where M = 8822.231 µs is the largest best value of 3 CI runs with coverage on
   * (6685.276 µs, 6595.251 µs, 8822.231 µs), CI runs 42651017565, 42652376396 and 42654113844 on
   * JDK 25, 2026-11-09.
   */
  private static final Duration AT_SIGN_QUERY_LIMIT = Duration.of(44_111_158, ChronoUnit.NANOS);

  /**
   * CPU time cleaning a URL whose 1MB query is a sensitive value with an opening quote only: 5
   * times M, where M = 9175.352 µs is the largest best value of 3 CI runs with coverage on
   * (6791.263 µs, 6629.146 µs, 9175.352 µs), CI runs 42651017565, 42652376396 and 42654113844 on
   * JDK 25, 2026-11-09.
   */
  private static final Duration UNCLOSED_QUOTE_QUERY_LIMIT =
      Duration.of(45_876_761, ChronoUnit.NANOS);

  /**
   * CPU time cleaning a URL whose 1MB query starts like a JSON Web Token: 5 times M, where M =
   * 14656.899 µs is the largest best value of 3 CI runs with coverage on (6620.153 µs, 6539.756 µs,
   * 14656.899 µs), CI runs 42651017565, 42652376396 and 42654113844 on JDK 25, 2026-11-09.
   */
  private static final Duration TOKEN_QUERY_LIMIT = Duration.of(73_284_497, ChronoUnit.NANOS);

  /**
   * URLs with a 1MB path, query or user info, each with its URI template and the CPU time cleaning
   * both may take, built only when the test runs.
   */
  private static final Supplier<Stream<Arguments>> HOSTILE_URLS_AND_LIMITS =
      () ->
          Stream.of(
              Arguments.of(
                  "a 1MB path",
                  "https://user:secret@partner.com/rates/" + "a".repeat(HOSTILE_INPUT_LENGTH),
                  "/rates/" + "a".repeat(HOSTILE_INPUT_LENGTH),
                  PATH_LIMIT),
              Arguments.of(
                  "a 1MB query",
                  "https://user:secret@partner.com/rates?currency="
                      + "a".repeat(HOSTILE_INPUT_LENGTH),
                  "/rates?currency=" + "a".repeat(HOSTILE_INPUT_LENGTH),
                  QUERY_LIMIT),
              Arguments.of(
                  "a 1MB user info",
                  "https://user:" + "a".repeat(HOSTILE_INPUT_LENGTH) + "@partner.com/rates",
                  "/rates",
                  USER_INFO_LIMIT),
              Arguments.of(
                  "a 1MB run of digits in the query",
                  "https://partner.com/rates?ref=" + "1".repeat(HOSTILE_INPUT_LENGTH),
                  "/rates?ref=" + "1".repeat(HOSTILE_INPUT_LENGTH),
                  DIGIT_QUERY_LIMIT),
              Arguments.of(
                  "a 1MB chain of key-value pairs in the query",
                  "https://partner.com/rates?"
                      + SHORT_PAIR.repeat(HOSTILE_INPUT_LENGTH / SHORT_PAIR.length()),
                  "/rates?" + SHORT_PAIR.repeat(HOSTILE_INPUT_LENGTH / SHORT_PAIR.length()),
                  PAIR_CHAIN_QUERY_LIMIT),
              Arguments.of(
                  "a 1MB query full of @",
                  "https://partner.com/rates?to=" + "a@".repeat(HOSTILE_INPUT_LENGTH / 2),
                  "/rates?to=" + "a@".repeat(HOSTILE_INPUT_LENGTH / 2),
                  AT_SIGN_QUERY_LIMIT),
              Arguments.of(
                  "a 1MB sensitive value with no closing quote",
                  "https://partner.com/rates?token='" + "a".repeat(HOSTILE_INPUT_LENGTH),
                  "/rates?token='" + "a".repeat(HOSTILE_INPUT_LENGTH),
                  UNCLOSED_QUOTE_QUERY_LIMIT),
              Arguments.of(
                  "a 1MB query that starts like a token",
                  "https://partner.com/rates?q=eyJ" + "a".repeat(HOSTILE_INPUT_LENGTH),
                  "/rates?q=eyJ" + "a".repeat(HOSTILE_INPUT_LENGTH),
                  TOKEN_QUERY_LIMIT));

  private final TelemetryUrlFilter filter = new TelemetryUrlFilter();

  private final CpuCostMeter meter = new CpuCostMeter(CALLS_PER_BATCH);

  @ParameterizedTest(name = "{0}")
  @FieldSource("HOSTILE_URLS_AND_LIMITS")
  @DisplayName("the span's URL and uri tag of a 1MB URL are cleaned within its limit")
  void hostileUrlIsCleanedWithinLimit(
      String urlName, String url, String uriTemplate, Duration limit) {
    var cleaned = filteredContext(url, uriTemplate);
    assertThat(cleaned.getHighCardinalityKeyValue(ObservabilityConstants.HTTP_URL).getValue())
        .startsWith("https://partner.com/rates")
        .doesNotContain("user:");
    assertThat(cleaned.getLowCardinalityKeyValue(ObservabilityConstants.URI).getValue())
        .doesNotContain("?");

    meter.assertCpuCostWithin(urlName, limit, () -> filteredContext(url, uriTemplate));
  }

  /** Returns a new context holding the URL and URI template, after the filter ran on it. */
  private Observation.Context filteredContext(String url, String uriTemplate) {
    var context = new Observation.Context();
    context.addHighCardinalityKeyValue(KeyValue.of(ObservabilityConstants.HTTP_URL, url));
    context.addLowCardinalityKeyValue(KeyValue.of(ObservabilityConstants.URI, uriTemplate));
    return filter.map(context);
  }
}
