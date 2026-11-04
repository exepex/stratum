package com.exepex.stratum.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.test.CpuCostMeter;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import java.time.Duration;
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
                  Duration.ofSeconds(1)),
              Arguments.of(
                  "a 1MB query",
                  "https://user:secret@partner.com/rates?currency=" + "a".repeat(LARGE_LENGTH),
                  "/rates?currency=" + "a".repeat(LARGE_LENGTH),
                  Duration.ofSeconds(1)),
              Arguments.of(
                  "a 1MB user info",
                  "https://user:" + "a".repeat(LARGE_LENGTH) + "@partner.com/rates",
                  "/rates",
                  Duration.ofSeconds(1)));

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
