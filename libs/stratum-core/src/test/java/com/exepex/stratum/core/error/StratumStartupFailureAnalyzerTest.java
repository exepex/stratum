package com.exepex.stratum.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.orders.OrderErrorCode;
import com.exepex.stratum.core.error.testsupport.CallerCodeFailure;
import com.exepex.stratum.core.error.testsupport.ErrorTestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;

@DisplayName(
    "StratumStartupFailureAnalyzer turns a Stratum startup error into Spring Boot's report")
class StratumStartupFailureAnalyzerTest {

  /** The bean whose creation failed in these tests. */
  private static final String BEAN_NAME = "greeter";

  /** The broken rule these tests report. */
  private static final String SETTINGS_DETAIL = "stratum.greeting.message breaks @NotBlank";

  private final StratumStartupFailureAnalyzer analyzer = new StratumStartupFailureAnalyzer();

  @Nested
  @DisplayName("a Stratum code that stops startup")
  class StartupCode {

    @Test
    @DisplayName(
        "a Stratum error inside the bean failure gives what went wrong, the fix and the page")
    void describesStratumError() {
      var settingsFailure = new StratumException(CoreErrorCode.INVALID_SETTINGS, SETTINGS_DETAIL);
      var startupFailure = new BeanCreationException(BEAN_NAME, "failed", settingsFailure);

      var analysis = analyzer.analyze(startupFailure);

      assertThat(analysis).isNotNull();
      assertThat(analysis.getDescription())
          .isEqualTo("SX-CORE-1003: Invalid settings: " + SETTINGS_DETAIL);
      assertThat(analysis.getAction())
          .isEqualTo(
              "Correct the named properties. See SX-CORE-1003 in "
                  + StratumStartupFailureAnalyzer.ERROR_CODES_PAGE
                  + ".");
      assertThat(analysis.getCause()).isSameAs(startupFailure);
    }

    @Test
    @DisplayName("a startup error beneath a code a caller gets is still reported")
    void findsStartupCodeBeneathCallerCode() {
      var settingsFailure = new StratumException(CoreErrorCode.INVALID_SETTINGS, SETTINGS_DETAIL);
      var startupFailure = new CallerCodeFailure(settingsFailure);

      var analysis = analyzer.analyze(startupFailure);

      assertThat(analysis).isNotNull();
      assertThat(analysis.getDescription()).startsWith("SX-CORE-1003: ");
    }
  }

  @Nested
  @DisplayName("a failure left to Spring Boot")
  class LeftToSpringBoot {

    @Test
    @DisplayName("a failure without a code is left to Spring Boot")
    void leavesUncodedFailure() {
      assertThat(analyzer.analyze(new BeanCreationException(BEAN_NAME, "failed"))).isNull();
    }

    @Test
    @DisplayName("a failure with the service's own code is left to Spring Boot")
    void leavesServiceCode() {
      var serviceFailure = new StratumException(OrderErrorCode.OUT_OF_STOCK, ErrorTestData.SKU);

      assertThat(analyzer.analyze(new BeanCreationException(BEAN_NAME, "failed", serviceFailure)))
          .isNull();
    }

    @Test
    @DisplayName("a Stratum code with no fix, which a caller gets, is left to Spring Boot")
    void leavesCallerCode() {
      assertThat(analyzer.analyze(new IllegalStateException(new CallerCodeFailure(null)))).isNull();
    }
  }
}
