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

@DisplayName("Cleaning a 1MB URL stays inside its CPU time limit")
class TelemetryUrlFilterSpeedTest {

  // A 1MB URL takes milliseconds, so a few calls make a batch of a few tens of milliseconds.
  /** Calls in each timed batch. */
  private static final int CALLS_PER_BATCH = 3;

  /**
   * CPU time cleaning a URL with a 1MB path may take: 5 times M, where M = 13442.071 µs is the
   * largest best value of 3 CI runs with coverage on (13442.071 µs, 13412.232 µs, 13373.219 µs), CI
   * run 41755894601 on JDK 25, 2026-11-04.
   */
  private static final Duration PATH_LIMIT = Duration.of(67_210_355, ChronoUnit.NANOS);

  /**
   * CPU time cleaning a URL with a 1MB query may take: 5 times M, where M = 15697.712 µs is the
   * largest best value of 3 CI runs with coverage on (15697.712 µs, 15250.553 µs, 15306.313 µs), CI
   * run 41755894601 on JDK 25, 2026-11-04.
   */
  private static final Duration QUERY_LIMIT = Duration.of(78_488_559, ChronoUnit.NANOS);

  /**
   * CPU time cleaning a URL with a 1MB user info may take: 5 times M, where M = 10932.756 µs is the
   * largest best value of 3 CI runs with coverage on (10830.844 µs, 10677.798 µs, 10932.756 µs), CI
   * run 41755894601 on JDK 25, 2026-11-04.
   */
  private static final Duration USER_INFO_LIMIT = Duration.of(54_663_780, ChronoUnit.NANOS);

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
