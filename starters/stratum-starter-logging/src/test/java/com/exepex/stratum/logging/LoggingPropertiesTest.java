package com.exepex.stratum.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.logging.LoggingProperties.LogFormat;
import com.exepex.stratum.logging.testsupport.LockerCodeLoggingSettings;
import com.exepex.stratum.logging.testsupport.LoggingTestData;
import com.exepex.stratum.logging.testsupport.OversizedFieldLoggingSettings;
import com.exepex.stratum.test.StarterTestSupport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("LoggingProperties come from properties, changed by Java configuration, which wins")
class LoggingPropertiesTest {

  /** The element limit the files set. */
  private static final int FILE_MAX_ELEMENTS = 15;

  /** The element limit's key. */
  private static final String MAX_ELEMENTS = LoggingConstants.PREFIX + ".max-elements";

  /** Element limits outside 1 to 20, each with the rule it breaks. */
  private static final List<Arguments> OUT_OF_RANGE_ELEMENTS =
      List.of(Arguments.of("21", "@Max(value=20)"), Arguments.of("0", "@Min(value=1)"));

  /** Settings from a file, which the Java configuration changes. */
  private final MockEnvironment environment =
      new MockEnvironment()
          .withProperty(LoggingConstants.FORMAT, "logstash")
          .withProperty(LoggingConstants.PREFIX + ".redaction.extra-keys[0]", "badge-no")
          .withProperty(MAX_ELEMENTS, String.valueOf(FILE_MAX_ELEMENTS));

  @Test
  @DisplayName("a customizer listed in spring.factories wins over the files")
  void javaConfigurationWins(@TempDir Path folder) throws IOException {
    var properties =
        StarterTestSupport.withCustomizers(
            folder,
            LoggingPropertiesCustomizer.class,
            List.of(LockerCodeLoggingSettings.class),
            () -> LoggingProperties.resolve(environment));

    assertThat(properties.format()).isEqualTo(LogFormat.ECS);
    assertThat(properties.maxElements()).isEqualTo(LockerCodeLoggingSettings.FEW_ELEMENTS);
    assertThat(properties.redaction().extraKeys())
        .containsExactly(LockerCodeLoggingSettings.LOCKER_CODE);
  }

  @Test
  @DisplayName("without a customizer, the files' values are used")
  void filesAloneAreUsed() {
    var properties = LoggingProperties.resolve(environment);

    assertThat(properties.format()).isEqualTo(LogFormat.LOGSTASH);
    assertThat(properties.maxElements()).isEqualTo(FILE_MAX_ELEMENTS);
    assertThat(properties.redaction().extraKeys()).containsExactly("badge-no");
  }

  @Test
  @DisplayName("a value set in Java is checked like one from a file, with SX-CORE-1003")
  void javaValueIsValidated(@TempDir Path folder) {
    assertThatExceptionOfType(StratumException.class)
        .isThrownBy(
            () ->
                StarterTestSupport.withCustomizers(
                    folder,
                    LoggingPropertiesCustomizer.class,
                    List.of(OversizedFieldLoggingSettings.class),
                    () -> LoggingProperties.resolve(environment)))
        .withMessage(
            "SX-CORE-1003: Invalid settings: stratum.logging.max-field-length-in-range breaks"
                + " @AssertTrue");
  }

  @Test
  @DisplayName("with max-elements unset, ten elements are kept")
  void maxElementsDefaultsToTen() {
    assertThat(LoggingProperties.resolve(new MockEnvironment()).maxElements())
        .isEqualTo(LoggingTestData.DEFAULT_MAX_ELEMENTS);
  }

  @ParameterizedTest
  @FieldSource("OUT_OF_RANGE_ELEMENTS")
  @DisplayName("max-elements above 20 or below 1 stops startup with SX-CORE-1003")
  void maxElementsOutOfRangeIsRejected(String maxElements, String rule) {
    var outOfRange = new MockEnvironment().withProperty(MAX_ELEMENTS, maxElements);

    assertThatExceptionOfType(StratumException.class)
        .isThrownBy(() -> LoggingProperties.resolve(outOfRange))
        .withMessage("SX-CORE-1003: Invalid settings: " + MAX_ELEMENTS + " breaks " + rule);
  }
}
