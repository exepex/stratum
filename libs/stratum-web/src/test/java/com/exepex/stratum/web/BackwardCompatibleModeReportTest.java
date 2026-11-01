package com.exepex.stratum.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.web.testsupport.WebTestData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("BackwardCompatibleModeReport explains the mode and warns about stack traces")
@ExtendWith(OutputCaptureExtension.class)
class BackwardCompatibleModeReportTest {

  /** The start of the INFO line. */
  private static final String MODE_ON = WebConstants.BACKWARD_COMPATIBLE + " is true";

  /** The start of the warning. */
  private static final String WARNING = "Security leak";

  /** The value of a setting that always shows its part. */
  private static final String ALWAYS = "always";

  /** Settings that show stack traces or exception classes in Spring Boot's body. */
  static final List<Map<String, String>> SHOWING =
      List.of(
          Map.of(BackwardCompatibleModeReport.INCLUDE_STACKTRACE, ALWAYS),
          Map.of(BackwardCompatibleModeReport.INCLUDE_STACKTRACE, "on-param"),
          Map.of(BackwardCompatibleModeReport.INCLUDE_EXCEPTION, "true"));

  /** Settings, with whether backward-compatible is on, that need no warning. */
  static final List<Arguments> QUIET =
      List.of(
          Arguments.of(Map.of(), true),
          Arguments.of(Map.of(BackwardCompatibleModeReport.INCLUDE_STACKTRACE, "never"), true),
          Arguments.of(Map.of("spring.web.error.include-message", ALWAYS), true),
          Arguments.of(Map.of("spring.web.error.include-binding-errors", ALWAYS), true),
          Arguments.of(Map.of(BackwardCompatibleModeReport.INCLUDE_STACKTRACE, ALWAYS), false));

  @Test
  @DisplayName("tells the team, in one INFO line, what the mode does and which counter to watch")
  void explainsMode(CapturedOutput output) {
    reportFor(Map.of(), true).afterSingletonsInstantiated();

    assertThat(output)
        .contains("INFO", MODE_ON, WebConstants.BOOT_ERROR_BODY_COUNTER)
        .doesNotContain(WARNING);
  }

  @Test
  @DisplayName("says nothing in standard mode")
  void quietInStandardMode(CapturedOutput output) {
    reportFor(Map.of(), false).afterSingletonsInstantiated();

    assertThat(output).doesNotContain(MODE_ON);
  }

  @ParameterizedTest
  @FieldSource("SHOWING")
  @DisplayName("warns, naming the setting, in backward-compatible mode")
  void warns(Map<String, String> settings, CapturedOutput output) {
    reportFor(settings, true).afterSingletonsInstantiated();

    assertThat(output).contains("WARN", WARNING, settings.values().iterator().next());
  }

  @ParameterizedTest
  @FieldSource("QUIET")
  @DisplayName("stays quiet when nothing shows them, or in standard mode")
  void staysQuiet(Map<String, String> settings, boolean backwardCompatible, CapturedOutput output) {
    reportFor(settings, backwardCompatible).afterSingletonsInstantiated();

    assertThat(output).doesNotContain(WARNING);
  }

  /** Returns the report for Spring Boot's settings, with backward-compatible on or off. */
  private BackwardCompatibleModeReport reportFor(
      Map<String, String> settings, boolean backwardCompatible) {
    var environment = new MockEnvironment();
    settings.forEach(environment::setProperty);
    return new BackwardCompatibleModeReport(
        WebTestData.defaults().backwardCompatible(backwardCompatible).build(), environment);
  }
}
