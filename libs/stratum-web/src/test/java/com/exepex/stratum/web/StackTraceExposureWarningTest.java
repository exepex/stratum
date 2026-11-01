package com.exepex.stratum.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.web.testsupport.WebTestData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

@DisplayName(
    "StackTraceExposureWarning warns at startup when Spring Boot's body shows stack traces")
@ExtendWith(OutputCaptureExtension.class)
class StackTraceExposureWarningTest {

  /** The start of the warning. */
  private static final String WARNING = "Security leak";

  /** The value of a setting that always shows its part. */
  private static final String ALWAYS = "always";

  /** Settings that show stack traces or exception classes in Spring Boot's body. */
  static final List<Map<String, String>> SHOWING =
      List.of(
          Map.of(StackTraceExposureWarning.INCLUDE_STACKTRACE, ALWAYS),
          Map.of(StackTraceExposureWarning.INCLUDE_STACKTRACE, "on-param"),
          Map.of(StackTraceExposureWarning.INCLUDE_EXCEPTION, "true"));

  /** Settings, with whether backward-compatible is on, that need no warning. */
  static final List<Arguments> QUIET =
      List.of(
          Arguments.of(Map.of(), true),
          Arguments.of(Map.of(StackTraceExposureWarning.INCLUDE_STACKTRACE, "never"), true),
          Arguments.of(Map.of("spring.web.error.include-message", ALWAYS), true),
          Arguments.of(Map.of("spring.web.error.include-binding-errors", ALWAYS), true),
          Arguments.of(Map.of(StackTraceExposureWarning.INCLUDE_STACKTRACE, ALWAYS), false));

  @ParameterizedTest
  @FieldSource("SHOWING")
  @DisplayName("warns, naming the setting, in backward-compatible mode")
  void warns(Map<String, String> settings, CapturedOutput output) {
    warningFor(settings, true).afterSingletonsInstantiated();

    assertThat(output).contains("WARN", WARNING, settings.values().iterator().next());
  }

  @ParameterizedTest
  @FieldSource("QUIET")
  @DisplayName("stays quiet when nothing shows them, or in standard mode")
  void staysQuiet(Map<String, String> settings, boolean backwardCompatible, CapturedOutput output) {
    warningFor(settings, backwardCompatible).afterSingletonsInstantiated();

    assertThat(output).doesNotContain(WARNING);
  }

  /** Returns the warning for Spring Boot's settings, with backward-compatible on or off. */
  private StackTraceExposureWarning warningFor(
      Map<String, String> settings, boolean backwardCompatible) {
    var environment = new MockEnvironment();
    settings.forEach(environment::setProperty);
    return new StackTraceExposureWarning(
        WebTestData.defaults().backwardCompatible(backwardCompatible).build(), environment);
  }
}
