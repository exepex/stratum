package com.exepex.stratum.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.StratumException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("LoggingEnvironmentPostProcessor sets up logging before the application starts")
@ExtendWith(OutputCaptureExtension.class)
class LoggingEnvironmentPostProcessorTest {

  private static final String FORMAT = LoggingConstants.PREFIX + ".format";

  private static final String NOT_REDACTED = "plain text, which is not redacted";

  /** Holds the post-processor's log lines until the test writes them out. */
  private final DeferredLogs logs = new DeferredLogs();

  private final LoggingEnvironmentPostProcessor postProcessor =
      new LoggingEnvironmentPostProcessor(logs);

  /** Returns an environment with the module on and a service name. */
  private static MockEnvironment enabled() {
    return new MockEnvironment()
        .withProperty(LoggingConstants.ENABLED, "true")
        .withProperty(LoggingConstants.APPLICATION_NAME, "orders");
  }

  /** Runs the post-processor on the environment and returns it. */
  private MockEnvironment process(MockEnvironment environment) {
    postProcessor.postProcessEnvironment(environment, new SpringApplication());
    logs.switchOverAll();
    return environment;
  }

  @Nested
  @DisplayName("with the module unset or off")
  class Off {

    @Test
    @DisplayName("it adds nothing and does not ask for a service name")
    void addsNothing() {
      var unset = process(new MockEnvironment());
      var off = process(new MockEnvironment().withProperty(LoggingConstants.ENABLED, "false"));

      assertThat(unset.getProperty(LoggingConstants.CONSOLE_FORMAT)).isNull();
      assertThat(off.getPropertySources().contains(LoggingEnvironmentPostProcessor.DEFAULTS_SOURCE))
          .isFalse();
    }

    @Test
    @DisplayName("a switch that is not a boolean stops startup")
    void invalidSwitchStopsStartup() {
      var environment = new MockEnvironment().withProperty(LoggingConstants.ENABLED, "maybe");

      assertThatExceptionOfType(BindException.class).isThrownBy(() -> process(environment));
    }
  }

  @Nested
  @DisplayName("with the module on")
  class On {

    @Test
    @DisplayName(
        "ECS JSON is the default for console and file; stack traces are root first, cut at 16KB")
    void setsDefaults() {
      var environment = process(enabled());

      assertThat(environment.getProperty(LoggingConstants.CONSOLE_FORMAT)).isEqualTo("ecs");
      assertThat(environment.getProperty(LoggingConstants.FILE_FORMAT)).isEqualTo("ecs");
      assertThat(environment.getProperty(LoggingConstants.STACK_TRACE_MAX_LENGTH))
          .isEqualTo("16384");
      assertThat(environment.getProperty(LoggingConstants.STACK_TRACE_ROOT)).isEqualTo("first");
      assertThat(environment.getProperty(LoggingConstants.ECS_ENVIRONMENT)).isNull();
    }

    @Test
    @DisplayName("stratum.environment becomes service.environment")
    void setsServiceEnvironment() {
      var environment = process(enabled().withProperty(LoggingConstants.ENVIRONMENT, "prod"));

      assertThat(environment.getProperty(LoggingConstants.ECS_ENVIRONMENT)).isEqualTo("prod");
    }

    @Test
    @DisplayName("another JSON format is passed to Spring Boot")
    void setsChosenFormat() {
      var environment = process(enabled().withProperty(FORMAT, "logstash"));

      assertThat(environment.getProperty(LoggingConstants.CONSOLE_FORMAT)).isEqualTo("logstash");
      assertThat(environment.getProperty(LoggingConstants.FILE_FORMAT)).isEqualTo("logstash");
    }

    @Test
    @DisplayName("the service's own Spring Boot logging properties win over Stratum's")
    void serviceValuesWin() {
      var environment = process(enabled().withProperty(LoggingConstants.CONSOLE_FORMAT, "gelf"));

      assertThat(environment.getProperty(LoggingConstants.CONSOLE_FORMAT)).isEqualTo("gelf");
    }

    @Test
    @DisplayName("a missing or blank service name stops startup with SX-LOGGING-1001")
    void missingNameStopsStartup() {
      var missing = new MockEnvironment().withProperty(LoggingConstants.ENABLED, "true");
      var blank = enabled().withProperty(LoggingConstants.APPLICATION_NAME, " ");

      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(() -> process(missing))
          .withMessageStartingWith("SX-LOGGING-1001: ");
      assertThatExceptionOfType(StratumException.class).isThrownBy(() -> process(blank));
    }

    @Test
    @DisplayName("an invalid redaction pattern stops startup with SX-LOGGING-1002")
    void invalidPatternStopsStartup() {
      var environment =
          enabled().withProperty("stratum.logging.redaction.extra-patterns[0]", "(unclosed");

      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(() -> process(environment))
          .withMessageStartingWith("SX-LOGGING-1002: ");
    }
  }

  @Nested
  @DisplayName("with plain-text logs")
  class PlainText {

    @Test
    @DisplayName("no JSON format is set, and a warning says the lines are not redacted")
    void warnsOutsideLocalProfiles(CapturedOutput output) {
      var environment = process(enabled().withProperty(FORMAT, "text"));

      assertThat(environment.getProperty(LoggingConstants.CONSOLE_FORMAT)).isNull();
      assertThat(output).contains(NOT_REDACTED);
    }

    @Test
    @DisplayName("in the local or test profile only, there is no warning")
    void noWarningInLocalProfiles(CapturedOutput output) {
      var environment = enabled().withProperty(FORMAT, "text");
      environment.setActiveProfiles("local", "test");

      process(environment);

      assertThat(output).doesNotContain(NOT_REDACTED);
    }

    @Test
    @DisplayName("a local profile next to another profile still warns")
    void warnsWithMixedProfiles(CapturedOutput output) {
      var environment = enabled().withProperty(FORMAT, "text");
      environment.setActiveProfiles("local", "prod");

      process(environment);

      assertThat(output).contains(NOT_REDACTED);
    }
  }
}
