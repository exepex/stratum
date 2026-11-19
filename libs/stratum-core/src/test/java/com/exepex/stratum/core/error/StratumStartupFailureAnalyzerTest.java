package com.exepex.stratum.core.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.orders.OrderErrorCode;
import com.exepex.stratum.core.error.testsupport.ErrorTestData;
import lombok.Getter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.CommandLineRunner;
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

  /** The heading of Spring Boot's failure report. */
  private static final String REPORT_HEADING = "APPLICATION FAILED TO START";

  private final StratumStartupFailureAnalyzer analyzer = new StratumStartupFailureAnalyzer();

  /** Runs the application, which fails, as Spring Boot runs a service. */
  private void runFailing(Class<?> configuration) {
    var application = new SpringApplication(configuration);
    application.setWebApplicationType(WebApplicationType.NONE);

    assertThatExceptionOfType(RuntimeException.class).isThrownBy(() -> application.run());
  }

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

    @Test
    @DisplayName("a service that fails to start shows the report, so Spring Boot uses the analyzer")
    void springBootShowsTheReport(CapturedOutput output) {
      runFailing(BrokenSettingsConfiguration.class);

      assertThat(output)
          .contains(REPORT_HEADING)
          .contains(SETTINGS_DETAIL)
          .contains("Correct the named properties. See SX-CORE-1003 in");
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

    @Test
    @DisplayName(
        "a CommandLineRunner that fails with a code a caller gets logs Spring Boot's run failure"
            + " with the stack trace, not the startup report")
    void runnerFailureKeepsStackTrace(CapturedOutput output) {
      runFailing(FailingRunnerConfiguration.class);

      assertThat(output)
          .contains("Application run failed")
          .contains(CallerCodeFailure.class.getName())
          .contains("\tat ")
          .doesNotContain(REPORT_HEADING);
    }
  }

  /**
   * A failure with a Stratum code that has no fix, as a failed call or a timed-out statement has.
   * The code is a warning, which a {@code StratumException} refuses, so this failure carries it.
   */
  @Getter
  static class CallerCodeFailure extends RuntimeException implements FailureWithErrorCode {

    private static final long serialVersionUID = 1L;

    /** The code, a Stratum one with no fix. */
    private final ErrorCode errorCode = CoreErrorCode.SPRING_BOOT_VERSION_UNSUPPORTED;

    /** No detail. */
    private final String errorDetail = "";

    /**
     * Makes the failure.
     *
     * @param cause what it wraps, or {@code null}
     */
    CallerCodeFailure(Throwable cause) {
      super("a call failed", cause);
    }
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

  /** A command-line tool whose work fails after startup with a code a caller gets. */
  @Configuration(proxyBeanMethods = false)
  static class FailingRunnerConfiguration {

    /**
     * Fails as a call to another service does when it is down.
     *
     * @return the runner
     */
    @Bean
    CommandLineRunner failingWork() {
      return args -> {
        throw new CallerCodeFailure(null);
      };
    }
  }
}
