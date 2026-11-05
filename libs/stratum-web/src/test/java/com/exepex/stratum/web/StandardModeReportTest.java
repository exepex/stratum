package com.exepex.stratum.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.web.testsupport.WebTestData;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.unit.DataSize;

@DisplayName("StandardModeReport tells the team the default limits of standard mode at startup")
@ExtendWith(OutputCaptureExtension.class)
class StandardModeReportTest {

  /** The start of the INFO line. */
  private static final String STANDARD_MODE = "Standard mode: ";

  /** How the line shows a limit of 0 or less. */
  private static final String NONE = "none";

  /**
   * The service's default time and body size limits, null for none set, with how the line shows
   * each.
   */
  static final List<Arguments> LIMITS =
      List.of(
          Arguments.of(null, null, "10s", "256KB"),
          Arguments.of(Duration.ofSeconds(90), DataSize.ofMegabytes(20), "1m30s", "20480KB"),
          Arguments.of(Duration.ofMillis(1500), DataSize.ofBytes(1500), "1s500ms", "1500B"),
          Arguments.of(Duration.ZERO, DataSize.ofBytes(0), NONE, NONE),
          Arguments.of(Duration.ofSeconds(-1), DataSize.ofBytes(-1), NONE, NONE));

  @ParameterizedTest
  @FieldSource("LIMITS")
  @DisplayName("logs one INFO line with the limits that apply and the compatible-mode hint")
  void logsStandardModeLine(
      @Nullable Duration timeLimit,
      @Nullable DataSize bodyLimit,
      String timeText,
      String bodyText,
      CapturedOutput output) {
    report(timeLimit, bodyLimit, false);

    assertThat(output)
        .contains(
            "INFO",
            STANDARD_MODE
                + "time limit "
                + timeText
                + ", body size limit "
                + bodyText
                + ", errors as Problem Details. For an existing service, set "
                + StratumModuleConstants.BACKWARD_COMPATIBLE
                + "=true.");
  }

  @Test
  @DisplayName("says nothing in backward-compatible mode")
  void quietInCompatibleMode(CapturedOutput output) {
    report(null, null, true);

    assertThat(output).doesNotContain(STANDARD_MODE);
  }

  /** Runs the report for the service's default limits, with backward-compatible on or off. */
  private void report(
      @Nullable Duration timeLimit, @Nullable DataSize bodyLimit, boolean backwardCompatible) {
    var settings =
        WebTestData.defaults()
            .timeout(
                new WebProperties.TimeoutSettings(
                    timeLimit, Map.of(), HttpStatus.SERVICE_UNAVAILABLE.value()))
            .bodyLimit(new WebProperties.BodyLimitSettings(bodyLimit, Map.of()))
            .build();
    var environment =
        new MockEnvironment()
            .withProperty(
                StratumModuleConstants.BACKWARD_COMPATIBLE, String.valueOf(backwardCompatible));
    try (var timeouts = new ResponseTimeouts(settings, backwardCompatible)) {
      new StandardModeReport(
              environment, timeouts, new RequestBodyLimits(settings, backwardCompatible))
          .afterSingletonsInstantiated();
    }
  }
}
