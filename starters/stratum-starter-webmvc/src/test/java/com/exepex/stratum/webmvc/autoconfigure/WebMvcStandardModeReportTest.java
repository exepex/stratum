package com.exepex.stratum.webmvc.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.web.RequestBodyLimits;
import com.exepex.stratum.web.ResponseTimeouts;
import com.exepex.stratum.web.StandardModeReport;
import com.exepex.stratum.webmvc.testsupport.WebMvcRequestFieldsRunners;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

// The bean comes from the shared web library, so this one stack's test covers both starters.
@DisplayName(
    "The service or a company framework can replace Stratum's standard-mode startup report")
class WebMvcStandardModeReportTest {

  /** The name of the replacement report's bean. */
  private static final String OWN_REPORT = "ownStandardModeReport";

  /** A servlet application with Stratum's request fields. */
  private final WebApplicationContextRunner contextRunner =
      WebMvcRequestFieldsRunners.requestFieldsRunner();

  @Test
  @DisplayName("the service's own report replaces Stratum's")
  void serviceReportWins() {
    contextRunner
        .withUserConfiguration(OwnReportAutoConfiguration.class)
        .run(
            context ->
                assertThat(context).hasSingleBean(StandardModeReport.class).hasBean(OWN_REPORT));
  }

  @Test
  @DisplayName("a company framework's auto-configuration with no order wins over Stratum's")
  void companyReportWins() {
    contextRunner
        .withConfiguration(AutoConfigurations.of(OwnReportAutoConfiguration.class))
        .run(
            context ->
                assertThat(context).hasSingleBean(StandardModeReport.class).hasBean(OWN_REPORT));
  }

  /** A service's or a company framework's own report, declared with no order. */
  @AutoConfiguration
  static class OwnReportAutoConfiguration {

    /**
     * Returns the replacement report.
     *
     * @param environment the application's environment
     * @param responseTimeouts the time limits
     * @param requestBodyLimits the body size limits
     * @return the report
     */
    @Bean
    StandardModeReport ownStandardModeReport(
        Environment environment,
        ResponseTimeouts responseTimeouts,
        RequestBodyLimits requestBodyLimits) {
      return new StandardModeReport(environment, responseTimeouts, requestBodyLimits);
    }
  }
}
