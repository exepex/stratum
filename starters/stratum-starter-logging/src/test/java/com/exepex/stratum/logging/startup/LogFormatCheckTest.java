package com.exepex.stratum.logging.startup;

import static com.exepex.stratum.logging.testsupport.LoggingTestData.environmentWithModuleOnAndServiceName;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.logging.LoggingConstants;
import com.exepex.stratum.logging.format.Log4j2MaskedTextFormatter;
import com.exepex.stratum.logging.format.LogbackMaskedTextFormatter;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("Only Stratum's log formats let the service start")
class LogFormatCheckTest {

  /** Stratum's text format for Logback, the backend of the test class path. */
  private static final String LOGBACK_TEXT = LogbackMaskedTextFormatter.class.getName();

  /** Stratum's text format for Log4j2. */
  private static final String LOG4J2_TEXT = Log4j2MaskedTextFormatter.class.getName();

  /** The application's class loader on the test class path, which holds no Logback file. */
  private static final ClassLoader TEST_CLASS_LOADER = new SpringApplication().getClassLoader();

  /** Returns an environment whose console and log file both write the format. */
  private MockEnvironment environmentWithFormat(String format) {
    return environmentWithModuleOnAndServiceName()
        .withProperty(LoggingConstants.CONSOLE_FORMAT, format)
        .withProperty(LoggingConstants.FILE_FORMAT, format);
  }

  /** Runs the check with Logback's text format on the test class path. */
  private void check(MockEnvironment environment) {
    LogFormatCheck.requireStratumFormats(environment, TEST_CLASS_LOADER, LOGBACK_TEXT);
  }

  /** Checks that the call stops startup with SX-LOGGING-1003 and the detail. */
  private void assertStopsWith(ThrowingCallable check, String detail) {
    assertThatExceptionOfType(StratumException.class)
        .isThrownBy(check)
        .withMessageStartingWith("SX-LOGGING-1003: ")
        .withMessageContaining(detail);
  }

  @Nested
  @DisplayName("with one of Stratum's formats")
  class WithStratumFormats {

    /** Spring Boot's JSON formats, in any casing, and Stratum's Logback text format. */
    private static final List<String> STRATUM_FORMATS =
        List.of("ecs", "ECS", "logstash", "gelf", LOGBACK_TEXT);

    @ParameterizedTest
    @FieldSource("STRATUM_FORMATS")
    @DisplayName("the format on both outputs lets the service start")
    void acceptsStratumFormat(String format) {
      assertThatCode(() -> check(environmentWithFormat(format))).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("with another format")
  class WithAnotherFormat {

    /** Formats Stratum does not mask: empty, unknown, a service's own and the other backend's. */
    private static final List<String> OTHER_FORMATS =
        List.of("", "json", "com.acme.orders.logging.OrdersFormat", LOG4J2_TEXT);

    @ParameterizedTest
    @FieldSource("OTHER_FORMATS")
    @DisplayName("on the console, it stops startup and names the console key")
    void rejectsOtherConsoleFormat(String format) {
      var environment =
          environmentWithFormat(LOGBACK_TEXT).withProperty(LoggingConstants.CONSOLE_FORMAT, format);

      assertStopsWith(() -> check(environment), LoggingConstants.CONSOLE_FORMAT + " is not");
    }

    @ParameterizedTest
    @FieldSource("OTHER_FORMATS")
    @DisplayName("on the log file, it stops startup and names the file key")
    void rejectsOtherFileFormat(String format) {
      var environment =
          environmentWithFormat(LOGBACK_TEXT).withProperty(LoggingConstants.FILE_FORMAT, format);

      assertStopsWith(() -> check(environment), LoggingConstants.FILE_FORMAT + " is not");
    }

    @Test
    @DisplayName("an unset log file format stops startup")
    void rejectsUnsetFileFormat() {
      var environment =
          environmentWithModuleOnAndServiceName()
              .withProperty(LoggingConstants.CONSOLE_FORMAT, LOGBACK_TEXT);

      assertStopsWith(() -> check(environment), LoggingConstants.FILE_FORMAT + " is not");
    }

    @Test
    @DisplayName("the message names the key, never the value")
    void messageNamesKeyNotValue() {
      var environment = environmentWithFormat("com.acme.orders.logging.OrdersFormat");

      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(() -> check(environment))
          .withMessageNotContaining("com.acme");
    }
  }

  @Nested
  @DisplayName("with a logging file of the service's own")
  class WithOwnLoggingFile {

    /** Every Logback file Spring Boot loads from the class path on its own. */
    private static final List<String> LOGBACK_FILE_NAMES =
        List.of(
            "logback-test.groovy",
            "logback-test.xml",
            "logback.groovy",
            "logback.xml",
            "logback-test-spring.groovy",
            "logback-test-spring.xml",
            "logback-spring.groovy",
            "logback-spring.xml");

    /** The directory that holds the service's own file, added to the class path. */
    @TempDir Path classPathDirectory;

    /** Returns a class loader that also finds the file, written in the class path directory. */
    private URLClassLoader classLoaderWith(String fileName, ClassLoader parent) throws IOException {
      Files.writeString(classPathDirectory.resolve(fileName), "<configuration/>");
      return new URLClassLoader(new URL[] {classPathDirectory.toUri().toURL()}, parent);
    }

    @Test
    @DisplayName("logging.config stops startup")
    void rejectsLoggingConfig() {
      var environment =
          environmentWithFormat(LOGBACK_TEXT)
              .withProperty(LoggingConstants.LOGGING_CONFIG, "classpath:orders-logback.xml");

      assertStopsWith(() -> check(environment), "logging.config is set");
    }

    @Test
    @DisplayName("a logging.config that starts with -D is ignored, as Spring Boot ignores it")
    void ignoresLoggingConfigStartingWithDashD() {
      var environment =
          environmentWithFormat(LOGBACK_TEXT)
              .withProperty(LoggingConstants.LOGGING_CONFIG, "-Dlogging.config=x");

      assertThatCode(() -> check(environment)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an empty logging.config is ignored, as Spring Boot ignores it")
    void ignoresEmptyLoggingConfig() {
      var environment =
          environmentWithFormat(LOGBACK_TEXT).withProperty(LoggingConstants.LOGGING_CONFIG, "");

      assertThatCode(() -> check(environment)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @FieldSource("LOGBACK_FILE_NAMES")
    @DisplayName("a Logback file on the class path stops startup and is named")
    void rejectsLogbackFile(String fileName) throws IOException {
      var environment = environmentWithFormat(LOGBACK_TEXT);
      try (var classLoader = classLoaderWith(fileName, TEST_CLASS_LOADER)) {
        assertStopsWith(
            () -> LogFormatCheck.requireStratumFormats(environment, classLoader, LOGBACK_TEXT),
            fileName + " is on the classpath");
      }
    }

    @Test
    @DisplayName("with no Logback file on the class path, the service starts")
    void acceptsNoFile() {
      assertThatCode(() -> check(environmentWithFormat(LOGBACK_TEXT))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("without Logback, a Logback file is not looked for")
    void ignoresLogbackFileWithoutLogback() throws IOException {
      var environment = environmentWithFormat(LOG4J2_TEXT);
      try (var classLoader =
          classLoaderWith("logback-spring.xml", new FilteredClassLoader(ILoggingEvent.class))) {
        assertThatCode(
                () -> LogFormatCheck.requireStratumFormats(environment, classLoader, LOG4J2_TEXT))
            .doesNotThrowAnyException();
      }
    }
  }

  @Nested
  @DisplayName("without a logging backend")
  class WithoutBackend {

    @Test
    @DisplayName("neither Logback nor Log4j2 stops startup")
    void rejectsMissingBackend() {
      var environment = environmentWithFormat(LOGBACK_TEXT);

      assertStopsWith(
          () -> LogFormatCheck.requireStratumFormats(environment, TEST_CLASS_LOADER, null),
          "no Logback or Log4j2 on the classpath");
    }
  }
}
