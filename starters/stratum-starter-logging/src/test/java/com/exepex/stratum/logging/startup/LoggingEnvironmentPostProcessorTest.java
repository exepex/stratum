package com.exepex.stratum.logging.startup;

import static com.exepex.stratum.logging.testsupport.LoggingTestData.environmentWithModuleOnAndServiceName;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.runLoggingPostProcessor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.logging.LoggingConstants;
import com.exepex.stratum.logging.format.Log4j2MaskedTextFormatter;
import com.exepex.stratum.logging.format.LogbackMaskedTextFormatter;
import com.exepex.stratum.logging.testsupport.LoggingTestData;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Hooks;

@DisplayName("LoggingEnvironmentPostProcessor sets up logging before the application starts")
@ExtendWith(OutputCaptureExtension.class)
class LoggingEnvironmentPostProcessorTest {

  private static final String FORMAT = LoggingConstants.PREFIX + ".format";

  private static final String GELF = "gelf";

  private static final String OFF = "false";

  private static final String TEXT = "text";

  @Nested
  @DisplayName("with the module unset or off")
  class WithModuleOff {

    @Test
    @DisplayName("it adds nothing and does not ask for a service name")
    void addsNothing() {
      var unset = runLoggingPostProcessor(new MockEnvironment());
      var off =
          runLoggingPostProcessor(
              new MockEnvironment().withProperty(LoggingConstants.ENABLED, OFF));

      assertThat(unset.getProperty(LoggingConstants.CONSOLE_FORMAT)).isNull();
      assertThat(off.getPropertySources().contains(DefaultPropertiesPropertySource.NAME)).isFalse();
    }

    @Test
    @DisplayName("a switch that is not a boolean stops startup")
    void invalidSwitchStopsStartup() {
      var environment = new MockEnvironment().withProperty(LoggingConstants.ENABLED, "maybe");

      assertThatExceptionOfType(BindException.class)
          .isThrownBy(() -> runLoggingPostProcessor(environment));
    }
  }

  @Nested
  @DisplayName("with the module on")
  class WithModuleOn {

    /** Limits just below 1KB and just above 1MB, which stop startup. */
    private static final List<String> MAX_FIELD_LENGTHS_OUT_OF_RANGE = List.of("1023B", "1025KB");

    /** The smallest and largest allowed limits. */
    private static final List<String> MAX_FIELD_LENGTHS_AT_THE_LIMITS = List.of("1KB", "1MB");

    @Test
    @DisplayName(
        "ECS JSON is the default for console and file; stack traces are root first, cut at 16KB")
    void setsDefaults() {
      var environment = runLoggingPostProcessor(environmentWithModuleOnAndServiceName());

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
      var environment =
          runLoggingPostProcessor(
              environmentWithModuleOnAndServiceName().withProperty("stratum.environment", "prod"));

      assertThat(environment.getProperty(LoggingConstants.ECS_ENVIRONMENT)).isEqualTo("prod");
    }

    @Test
    @DisplayName(
        "Spring's executors and every Reactor operator carry the context, unless the service sets"
            + " Spring Boot's keys")
    void propagatesContext() {
      var defaultEnvironment = runLoggingPostProcessor(environmentWithModuleOnAndServiceName());
      var serviceSettings =
          runLoggingPostProcessor(
              environmentWithModuleOnAndServiceName()
                  .withProperty(StratumModuleConstants.PROPAGATE_TASK_CONTEXT, OFF)
                  .withProperty(StratumModuleConstants.REACTOR_CONTEXT_PROPAGATION, "limited"));

      assertThat(defaultEnvironment.getProperty(StratumModuleConstants.PROPAGATE_TASK_CONTEXT))
          .isEqualTo("true");
      assertThat(defaultEnvironment.getProperty("spring.reactor.context-propagation"))
          .isEqualTo("auto");
      assertThat(serviceSettings.getProperty(StratumModuleConstants.PROPAGATE_TASK_CONTEXT))
          .isEqualTo(OFF);
      assertThat(serviceSettings.getProperty("spring.reactor.context-propagation"))
          .isEqualTo("limited");
    }

    @Test
    @DisplayName(
        "it leaves Reactor's context propagation to the auto-configuration, so that a service's"
            + " @PropertySource files, loaded later, can still turn it off")
    void leavesReactorContextPropagationForLaterDecision() {
      Hooks.disableAutomaticContextPropagation();

      runLoggingPostProcessor(environmentWithModuleOnAndServiceName());

      assertThat(Hooks.isAutomaticContextPropagationEnabled()).isFalse();
    }

    @Test
    @DisplayName("a stack trace can start with the exception the code caught, with Boot's key")
    void outermostExceptionFirst() {
      var environment =
          runLoggingPostProcessor(
              environmentWithModuleOnAndServiceName()
                  .withProperty(LoggingConstants.STACK_TRACE_ROOT, "last"));

      assertThat(environment.getProperty(LoggingConstants.STACK_TRACE_ROOT)).isEqualTo("last");
    }

    @Test
    @DisplayName("another JSON format is passed to Spring Boot")
    void setsChosenFormat() {
      var environment =
          runLoggingPostProcessor(
              environmentWithModuleOnAndServiceName().withProperty(FORMAT, "logstash"));

      assertThat(environment.getProperty(LoggingConstants.CONSOLE_FORMAT)).isEqualTo("logstash");
      assertThat(environment.getProperty(LoggingConstants.FILE_FORMAT)).isEqualTo("logstash");
    }

    @Test
    @DisplayName("the service's own Spring Boot logging properties win over Stratum's")
    void serviceValuesWin() {
      var environment =
          runLoggingPostProcessor(
              environmentWithModuleOnAndServiceName()
                  .withProperty(LoggingConstants.CONSOLE_FORMAT, GELF));

      assertThat(environment.getProperty(LoggingConstants.CONSOLE_FORMAT)).isEqualTo(GELF);
    }

    @Test
    @DisplayName("a Spring Boot logging property the service sets in Java wins over Stratum's")
    void serviceJavaDefaultWins() {
      var environment = environmentWithModuleOnAndServiceName();
      environment
          .getPropertySources()
          .addLast(
              new MapPropertySource(
                  DefaultPropertiesPropertySource.NAME,
                  Map.of(LoggingConstants.CONSOLE_FORMAT, GELF)));

      runLoggingPostProcessor(environment);
      // Spring Boot moves the default properties last once every post-processor has run.
      DefaultPropertiesPropertySource.moveToEnd(environment);

      assertThat(environment.getProperty(LoggingConstants.CONSOLE_FORMAT)).isEqualTo(GELF);
      assertThat(environment.getProperty(LoggingConstants.FILE_FORMAT)).isEqualTo("ecs");
    }

    @Test
    @DisplayName("a missing or blank service name stops startup with SX-LOGGING-1001")
    void missingNameStopsStartup() {
      var missing = LoggingTestData.environmentWithModuleOn();
      var blank =
          environmentWithModuleOnAndServiceName()
              .withProperty(LoggingConstants.APPLICATION_NAME, " ");

      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(() -> runLoggingPostProcessor(missing))
          .withMessageStartingWith("SX-LOGGING-1001: ");
      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(() -> runLoggingPostProcessor(blank));
    }

    @Test
    @DisplayName("an invalid redaction pattern stops startup with SX-LOGGING-1002")
    void invalidPatternStopsStartup() {
      var environment =
          environmentWithModuleOnAndServiceName()
              .withProperty("stratum.logging.redaction.extra-patterns[0]", "(unclosed");

      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(() -> runLoggingPostProcessor(environment))
          .withMessageStartingWith("SX-LOGGING-1002: ");
    }

    @ParameterizedTest
    @FieldSource("MAX_FIELD_LENGTHS_OUT_OF_RANGE")
    @DisplayName("a max-field-length below 1KB or above 1MB stops startup with SX-CORE-1003")
    void maxFieldLengthOutOfRangeStopsStartup(String maxFieldLength) {
      var environment =
          environmentWithModuleOnAndServiceName()
              .withProperty(LoggingConstants.PREFIX + ".max-field-length", maxFieldLength);

      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(() -> runLoggingPostProcessor(environment))
          .withMessage(
              "SX-CORE-1003: Invalid settings: stratum.logging.max-field-length-in-range breaks"
                  + " @AssertTrue");
    }

    @ParameterizedTest
    @FieldSource("MAX_FIELD_LENGTHS_AT_THE_LIMITS")
    @DisplayName("a max-field-length of exactly 1KB or 1MB is allowed")
    void maxFieldLengthAtTheLimitsIsAllowed(String maxFieldLength) {
      var environment =
          runLoggingPostProcessor(
              environmentWithModuleOnAndServiceName()
                  .withProperty(LoggingConstants.PREFIX + ".max-field-length", maxFieldLength));

      assertThat(environment.getPropertySources().contains(DefaultPropertiesPropertySource.NAME))
          .isTrue();
    }
  }

  @Nested
  @DisplayName("with the text format")
  class TextFormat {

    /** Returns an application whose class loader hides the given logging backends' events. */
    private SpringApplication applicationWithout(Class<?>... hiddenEvents) {
      return new SpringApplication(
          new DefaultResourceLoader(new FilteredClassLoader(hiddenEvents)));
    }

    @Test
    @DisplayName(
        "Logback writes Stratum's masked text to the console and the file, with no warning")
    void setsLogbackMaskedText(CapturedOutput output) {
      var environment = environmentWithModuleOnAndServiceName().withProperty(FORMAT, TEXT);
      environment.setActiveProfiles("prod");

      runLoggingPostProcessor(environment);

      assertThat(environment.getProperty(LoggingConstants.CONSOLE_FORMAT))
          .isEqualTo(LogbackMaskedTextFormatter.class.getName());
      assertThat(environment.getProperty(LoggingConstants.FILE_FORMAT))
          .isEqualTo(LogbackMaskedTextFormatter.class.getName());
      assertThat(output).doesNotContain(LoggingConstants.FORMAT);
    }

    @Test
    @DisplayName("the local profile gets the same masked text: no profile turns masking off")
    void localProfileIsMaskedToo() {
      var environment = environmentWithModuleOnAndServiceName().withProperty(FORMAT, TEXT);
      environment.setActiveProfiles("local");

      runLoggingPostProcessor(environment);

      assertThat(environment.getProperty(LoggingConstants.CONSOLE_FORMAT))
          .isEqualTo(LogbackMaskedTextFormatter.class.getName());
    }

    @Test
    @DisplayName("without Logback, Log4j2 writes Stratum's masked text")
    void setsLog4j2MaskedText() {
      var environment =
          runLoggingPostProcessor(
              environmentWithModuleOnAndServiceName().withProperty(FORMAT, TEXT),
              applicationWithout(ILoggingEvent.class));

      assertThat(environment.getProperty(LoggingConstants.CONSOLE_FORMAT))
          .isEqualTo(Log4j2MaskedTextFormatter.class.getName());
      assertThat(environment.getProperty(LoggingConstants.FILE_FORMAT))
          .isEqualTo(Log4j2MaskedTextFormatter.class.getName());
    }

    @Test
    @DisplayName("with neither Logback nor Log4j2, no format is set")
    void setsNoFormatWithoutBackend() {
      var environment =
          runLoggingPostProcessor(
              environmentWithModuleOnAndServiceName().withProperty(FORMAT, TEXT),
              applicationWithout(ILoggingEvent.class, LogEvent.class));

      assertThat(environment.getProperty(LoggingConstants.CONSOLE_FORMAT)).isNull();
    }

    @Test
    @DisplayName("a JSON format is used as it is, with Logback or Log4j2")
    void jsonFormatIgnoresBackend() {
      var environment =
          runLoggingPostProcessor(
              environmentWithModuleOnAndServiceName().withProperty(FORMAT, GELF),
              applicationWithout(ILoggingEvent.class));

      assertThat(environment.getProperty(LoggingConstants.CONSOLE_FORMAT)).isEqualTo(GELF);
    }
  }
}
