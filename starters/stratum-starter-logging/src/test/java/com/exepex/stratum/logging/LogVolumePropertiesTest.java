package com.exepex.stratum.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.logging.testsupport.LimitsOffLoggingSettings;
import com.exepex.stratum.test.StarterTestSupport;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.mock.env.MockEnvironment;

@DisplayName(
    "The volume settings have safe defaults, are checked at startup and can be set in Java")
class LogVolumePropertiesTest {

  /** The default limit of TRACE, DEBUG and INFO lines per second. */
  private static final int DEFAULT_LIMIT = 1000;

  /** The default limit of WARN and ERROR lines per second. */
  private static final int DEFAULT_WARN_ERROR_LIMIT = 5000;

  /** The default number of loggers and levels counted on their own. */
  private static final int DEFAULT_MAX_KEYS = 1024;

  /** The default summary interval. */
  private static final Duration DEFAULT_REPORT_INTERVAL = Duration.ofSeconds(10);

  /**
   * Values outside their range, each with its key and the rule the startup error names. The error
   * names the key and the rule, never the value.
   */
  private static final List<Arguments> OUT_OF_RANGE =
      List.of(
          Arguments.of("max-per-second", "0", "max-per-second breaks @Min(value=1)"),
          Arguments.of(
              "warn-error-max-per-second",
              "1000001",
              "warn-error-max-per-second breaks @Max(value=1000000)"),
          Arguments.of("debug-keep-one-in", "0", "debug-keep-one-in breaks @Min(value=1)"),
          Arguments.of("info-keep-one-in", "0", "info-keep-one-in breaks @Min(value=1)"),
          Arguments.of("max-keys", "63", "max-keys breaks @Min(value=64)"),
          Arguments.of("max-keys", "16385", "max-keys breaks @Max(value=16384)"),
          Arguments.of("report-interval", "500ms", "report-interval-in-range breaks @AssertTrue"),
          Arguments.of("report-interval", "2h", "report-interval-in-range breaks @AssertTrue"));

  @Test
  @DisplayName("unset, the limits are on at 1000 and 5000 lines per second, and nothing is sampled")
  void defaultsAreSafe() {
    var volume = LoggingProperties.resolve(new MockEnvironment()).volume();

    assertThat(volume.enabled()).isTrue();
    assertThat(volume.maxPerSecond()).isEqualTo(DEFAULT_LIMIT);
    assertThat(volume.warnErrorMaxPerSecond()).isEqualTo(DEFAULT_WARN_ERROR_LIMIT);
    assertThat(volume.debugKeepOneIn()).isEqualTo(1);
    assertThat(volume.infoKeepOneIn()).isEqualTo(1);
    assertThat(volume.neverLimitLoggers()).containsExactly("org.springframework.security");
    assertThat(volume.maxKeys()).isEqualTo(DEFAULT_MAX_KEYS);
    assertThat(volume.reportInterval()).isEqualTo(DEFAULT_REPORT_INTERVAL);
  }

  @ParameterizedTest(name = "{0}={1}")
  @FieldSource("OUT_OF_RANGE")
  @DisplayName("a value out of range stops startup with SX-CORE-1003, naming the key and the rule")
  void outOfRangeIsRejected(String key, String value, String rule) {
    var outOfRange = new MockEnvironment().withProperty(LoggingConstants.VOLUME + "." + key, value);

    assertThatExceptionOfType(StratumException.class)
        .isThrownBy(() -> LoggingProperties.resolve(outOfRange))
        .withMessage("SX-CORE-1003: Invalid settings: " + LoggingConstants.VOLUME + "." + rule);
  }

  @Test
  @DisplayName("a customizer listed in spring.factories turns the limits off over the files")
  void javaConfigurationWins(@TempDir Path folder) throws IOException {
    var filesOn = new MockEnvironment().withProperty(LoggingConstants.VOLUME + ".enabled", "true");

    var volume =
        StarterTestSupport.withCustomizers(
            folder,
            LoggingPropertiesCustomizer.class,
            List.of(LimitsOffLoggingSettings.class),
            () -> LoggingProperties.resolve(filesOn).volume());

    assertThat(volume.enabled()).isFalse();
  }
}
