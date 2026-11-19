package com.exepex.stratum.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.CoreErrorCode;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.core.error.testsupport.CallerCodeFailure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Spring Boot's log of an application that fails while it starts or runs")
class StartupFailureReportIntegrationTest {

  /** The broken rule the startup failure reports. */
  private static final String SETTINGS_DETAIL = "stratum.greeting.message breaks @NotBlank";

  /** The heading of Spring Boot's failure report. */
  private static final String REPORT_HEADING = "APPLICATION FAILED TO START";

  /** Runs the application, which fails, as Spring Boot runs a service. */
  private void runFailing(Class<?> configuration) {
    var application = new SpringApplication(configuration);
    application.setWebApplicationType(WebApplicationType.NONE);

    assertThatExceptionOfType(RuntimeException.class).isThrownBy(application::run);
  }

  @Nested
  @DisplayName("with a Stratum code that stops startup")
  class StartupCode {

    @Test
    @DisplayName("shows the report, so Spring Boot uses the analyzer")
    void springBootShowsTheReport(CapturedOutput output) {
      runFailing(BrokenSettingsConfiguration.class);

      assertThat(output)
          .contains(REPORT_HEADING)
          .contains(SETTINGS_DETAIL)
          .contains("Correct the named properties. See SX-CORE-1003 in");
    }
  }

  @Nested
  @DisplayName("with a code a caller gets")
  class CallerCode {

    @Test
    @DisplayName(
        "a CommandLineRunner that fails with it logs Spring Boot's run failure with the stack"
            + " trace, not the startup report")
    void runnerFailureKeepsStackTrace(CapturedOutput output) {
      runFailing(FailingRunnerConfiguration.class);

      assertThat(output)
          .contains("Application run failed")
          .contains(CallerCodeFailure.class.getName())
          .contains("\tat ")
          .doesNotContain(REPORT_HEADING);
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
     * Returns the tool's work, which fails as a call to another service does when it is down.
     *
     * @return the runner
     */
    @Bean
    CommandLineRunner failingWork() {
      return args -> work();
    }

    /** Fails as a call to another service does when it is down. */
    private void work() {
      throw new CallerCodeFailure(null);
    }
  }
}
