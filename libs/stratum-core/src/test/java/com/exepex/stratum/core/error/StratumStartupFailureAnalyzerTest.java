package com.exepex.stratum.core.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.orders.OrderErrorCode;
import com.exepex.stratum.core.error.testsupport.ErrorTestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName(
    "StratumStartupFailureAnalyzer turns a Stratum startup error into Spring Boot's report")
class StratumStartupFailureAnalyzerTest {

  /** The bean whose creation failed in these tests. */
  private static final String BEAN_NAME = "greeter";

  /** The broken rule these tests report. */
  private static final String SETTINGS_DETAIL = "stratum.greeting.message breaks @NotBlank";

  private final StratumStartupFailureAnalyzer analyzer = new StratumStartupFailureAnalyzer();

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
  @DisplayName("a service that fails to start shows the report, so Spring Boot uses the analyzer")
  void springBootShowsTheReport(CapturedOutput output) {
    var application = new SpringApplication(BrokenSettingsConfiguration.class);
    application.setWebApplicationType(WebApplicationType.NONE);

    assertThatExceptionOfType(BeanCreationException.class).isThrownBy(() -> application.run());

    assertThat(output)
        .contains("APPLICATION FAILED TO START")
        .contains(SETTINGS_DETAIL)
        .contains("Correct the named properties. See SX-CORE-1003 in");
  }

  /** A service whose settings break their rules when its bean is created. */
  @Configuration(proxyBeanMethods = false)
  static class BrokenSettingsConfiguration {

    /**
     * Fails as a module does when its settings break their rules.
     *
     * @return never
     */
    @Bean
    String greeter() {
      throw new StratumException(CoreErrorCode.INVALID_SETTINGS, SETTINGS_DETAIL);
    }
  }
}
