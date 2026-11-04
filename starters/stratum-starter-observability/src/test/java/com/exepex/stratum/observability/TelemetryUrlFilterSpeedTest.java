package com.exepex.stratum.observability;

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

@DisplayName("Cleaning a 1MB URL stays inside its CPU time limit")
class TelemetryUrlFilterSpeedTest {

  /** Characters in each hostile part of a URL: 1MB. */
  private static final int LARGE_LENGTH = 1_000_000;

  // A 1MB URL takes milliseconds, so a few calls make a batch of a few tens of milliseconds.
  /** Calls in each timed batch. */
  private static final int CALLS_PER_BATCH = 3;

  /**
   * CPU time cleaning a URL with a 1MB path may take: 5 times M, where M = 16809.378 µs is the
   * largest best value of 3 CI runs with coverage on (9670.897 µs, 13848.134 µs, 16809.378 µs), CI
   * run 41749701016 on JDK 25, 2026-11-04.
   */
  private static final Duration PATH_LIMIT = Duration.of(84_046_890, ChronoUnit.NANOS);

  /**
   * CPU time cleaning a URL with a 1MB query may take: 5 times M, where M = 19351.927 µs is the
   * largest best value of 3 CI runs with coverage on (10196.216 µs, 14624.650 µs, 19351.927 µs), CI
   * run 41749701016 on JDK 25, 2026-11-04.
   */
  private static final Duration QUERY_LIMIT = Duration.of(96_759_635, ChronoUnit.NANOS);

  /**
   * CPU time cleaning a URL with a 1MB user info may take: 5 times M, where M = 11051.443 µs is the
   * largest best value of 3 CI runs with coverage on (6858.519 µs, 10466.982 µs, 11051.443 µs), CI
   * run 41749701016 on JDK 25, 2026-11-04.
   */
  private static final Duration USER_INFO_LIMIT = Duration.of(55_257_217, ChronoUnit.NANOS);

  /**
   * URLs with a 1MB path, query or user info, each with its URI template and the CPU time cleaning
   * both may take, built only when the test runs.
   */
  private static final Supplier<Stream<Arguments>> HOSTILE_URLS_AND_LIMITS =
      () ->
          Stream.of(
              Arguments.of(
                  "a 1MB path",
                  "https://user:secret@partner.com/rates/" + "a".repeat(LARGE_LENGTH),
                  "/rates/" + "a".repeat(LARGE_LENGTH),
                  PATH_LIMIT),
              Arguments.of(
                  "a 1MB query",
                  "https://user:secret@partner.com/rates?currency=" + "a".repeat(LARGE_LENGTH),
                  "/rates?currency=" + "a".repeat(LARGE_LENGTH),
                  QUERY_LIMIT),
              Arguments.of(
                  "a 1MB user info",
                  "https://user:" + "a".repeat(LARGE_LENGTH) + "@partner.com/rates",
                  "/rates",
                  USER_INFO_LIMIT));

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
