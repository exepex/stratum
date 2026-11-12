package com.exepex.stratum.web;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.web.testsupport.WebTestData;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;

@DisplayName("A path pattern in the web settings that is not valid stops startup with SX-WEB-1009")
class PathPatternSettingsTest {

  /** Patterns Spring refuses: a variable not closed, and {@code **} inside the pattern. */
  static final List<String> INVALID_PATTERNS = List.of("/api/{id", "/api/**/orders");

  /** The time limit of the route under test. */
  private static final Duration ROUTE_TIME_LIMIT = Duration.ofSeconds(5);

  @ParameterizedTest
  @FieldSource("INVALID_PATTERNS")
  @DisplayName("in the time limit routes, naming the key and the pattern")
  void timeoutRoute(String pattern) {
    var settings =
        WebTestData.defaults()
            .timeout(
                new WebProperties.TimeoutSettings(
                    null,
                    Map.of(pattern, ROUTE_TIME_LIMIT),
                    HttpStatus.SERVICE_UNAVAILABLE.value()))
            .build();

    assertNamesKeyAndPattern(
        WebConstants.TIMEOUT_ROUTES, pattern, () -> new ResponseTimeouts(settings, false).close());
  }

  @ParameterizedTest
  @FieldSource("INVALID_PATTERNS")
  @DisplayName("in the body size limit routes, naming the key and the pattern")
  void bodyLimitRoute(String pattern) {
    var settings =
        WebTestData.defaults()
            .bodyLimit(
                new WebProperties.BodyLimitSettings(null, Map.of(pattern, DataSize.ofMegabytes(1))))
            .build();

    assertNamesKeyAndPattern(
        WebConstants.BODY_LIMIT_ROUTES, pattern, () -> new RequestBodyLimits(settings, false));
  }

  @ParameterizedTest
  @FieldSource("INVALID_PATTERNS")
  @DisplayName("in the request log's skip paths, naming the key and the pattern")
  void skipPath(String pattern) {
    var defaults = WebTestData.defaults().build();
    var settings =
        WebTestData.defaults()
            .requestLog(
                defaults.requestLog().toBuilder()
                    .skipPaths(List.of("/actuator/**", pattern))
                    .build())
            .build();

    assertNamesKeyAndPattern(
        WebConstants.REQUEST_LOG_SKIP_PATHS,
        pattern,
        () -> new RequestLogWriter(settings, RequestFieldsReader.from(settings)));
  }

  /** Checks that building from the settings fails with the code, the key and the pattern. */
  private void assertNamesKeyAndPattern(String key, String pattern, ThrowingCallable build) {
    assertThatExceptionOfType(StratumException.class)
        .isThrownBy(build)
        .withMessageStartingWith("SX-WEB-1009: A path pattern in the web settings is not valid: ")
        .withMessageContaining(key + " has '" + pattern + "' (");
  }
}
