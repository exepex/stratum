package com.exepex.stratum.logging.startup;

import static com.exepex.stratum.logging.testsupport.LoggingTestData.environmentWithServiceName;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.params.provider.Arguments.arguments;

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
import org.junit.jupiter.params.provider.Arguments;
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

  /**
   * The application's class loader on the test class path, which holds Logback and Log4j2 and no
   * logging file.
   */
  private static final ClassLoader TEST_CLASS_LOADER = new SpringApplication().getClassLoader();

  /** A class loader without Logback, so that Spring Boot would log with Log4j2. */
  private static final ClassLoader LOG4J2_CLASS_LOADER =
      new FilteredClassLoader(ILoggingEvent.class);

  /** The directory that holds the service's own file, added to the class path. */
  @TempDir Path classPathDirectory;

  /** Returns an environment whose console and log file both write the format. */
  private MockEnvironment environmentWithFormat(String format) {
    return environmentWithServiceName()
        .withProperty(LoggingConstants.CONSOLE_FORMAT, format)
        .withProperty(LoggingConstants.FILE_FORMAT, format);
  }

  /** Runs the check with Logback's text format on the test class path. */
  private void check(MockEnvironment environment) {
    LogFormatCheck.requireStratumFormats(environment, TEST_CLASS_LOADER, LOGBACK_TEXT);
  }

  /** Runs the check with Log4j2's text format and the class loader. */
  private void checkLog4j2(MockEnvironment environment, ClassLoader classLoader) {
    LogFormatCheck.requireStratumFormats(environment, classLoader, LOG4J2_TEXT);
  }

  /** Returns a class loader that also finds the file, written in the class path directory. */
  private URLClassLoader classLoaderWith(String fileName, ClassLoader parent) throws IOException {
    Files.writeString(classPathDirectory.resolve(fileName), "<configuration/>");
    return new URLClassLoader(new URL[] {classPathDirectory.toUri().toURL()}, parent);
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
          environmentWithServiceName().withProperty(LoggingConstants.CONSOLE_FORMAT, LOGBACK_TEXT);

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
      try (var classLoader = classLoaderWith("logback-spring.xml", LOG4J2_CLASS_LOADER)) {
        assertThatCode(() -> checkLog4j2(environment, classLoader)).doesNotThrowAnyException();
      }
    }
  }

  @Nested
  @DisplayName("with a Log4j2 configuration of the service's own")
  class WithOwnLog4j2Configuration {

    /** Every Log4j2 file Spring Boot loads from the class path on its own, JSON and YAML too. */
    private static final List<String> LOG4J2_FILE_NAMES =
        List.of(
            "log4j2-test.properties",
            "log4j2-test.yml",
            "log4j2-test.yaml",
            "log4j2-test.json",
            "log4j2-test.jsn",
            "log4j2-test.xml",
            "log4j2.properties",
            "log4j2.yml",
            "log4j2.yaml",
            "log4j2.json",
            "log4j2.jsn",
            "log4j2.xml",
            "log4j2-test-spring.properties",
            "log4j2-test-spring.yml",
            "log4j2-test-spring.yaml",
            "log4j2-test-spring.json",
            "log4j2-test-spring.jsn",
            "log4j2-test-spring.xml",
            "log4j2-spring.properties",
            "log4j2-spring.yml",
            "log4j2-spring.yaml",
            "log4j2-spring.json",
            "log4j2-spring.jsn",
            "log4j2-spring.xml");

    /** Files whose names start like a Log4j2 configuration but that Log4j2 never loads as one. */
    private static final List<String> NOT_LOG4J2_CONFIGURATIONS =
        List.of("log4j2.component.properties", "log4j2-orders.xml");

    /** Each way to set the override key that Spring Boot binds: one value, a list, an index. */
    private static final List<Arguments> OVERRIDE_FORMS =
        List.of(
            arguments(LoggingConstants.LOG4J2_CONFIG_OVERRIDE, "classpath:own.xml"),
            arguments(LoggingConstants.LOG4J2_CONFIG_OVERRIDE, "classpath:a.xml,classpath:b.xml"),
            arguments(LoggingConstants.LOG4J2_CONFIG_OVERRIDE + "[0]", "classpath:own.xml"));

    /** Returns a Log4j2 environment with the override key set to the value. */
    private MockEnvironment environmentWithOverride(String key, String value) {
      return environmentWithFormat(LOG4J2_TEXT).withProperty(key, value);
    }

    @ParameterizedTest
    @FieldSource("LOG4J2_FILE_NAMES")
    @DisplayName("a Log4j2 file on the class path stops startup and is named")
    void rejectsLog4j2File(String fileName) throws IOException {
      var environment = environmentWithFormat(LOG4J2_TEXT);
      try (var classLoader = classLoaderWith(fileName, LOG4J2_CLASS_LOADER)) {
        assertStopsWith(
            () -> checkLog4j2(environment, classLoader), fileName + " is on the classpath");
      }
    }

    @ParameterizedTest
    @FieldSource("NOT_LOG4J2_CONFIGURATIONS")
    @DisplayName("a file that is no Log4j2 configuration lets the service start")
    void acceptsFileThatIsNoConfiguration(String fileName) throws IOException {
      var environment = environmentWithFormat(LOG4J2_TEXT);
      try (var classLoader = classLoaderWith(fileName, LOG4J2_CLASS_LOADER)) {
        assertThatCode(() -> checkLog4j2(environment, classLoader)).doesNotThrowAnyException();
      }
    }

    @Test
    @DisplayName("with no file and no override, the service starts")
    void acceptsNoOwnConfiguration() {
      var environment = environmentWithFormat(LOG4J2_TEXT);

      assertThatCode(() -> checkLog4j2(environment, LOG4J2_CLASS_LOADER))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("with Logback next to Log4j2, a Log4j2 file is ignored, as Spring Boot ignores it")
    void ignoresLog4j2FileWithLogback() throws IOException {
      var environment = environmentWithFormat(LOGBACK_TEXT);
      try (var classLoader = classLoaderWith("log4j2.xml", TEST_CLASS_LOADER)) {
        assertThatCode(
                () -> LogFormatCheck.requireStratumFormats(environment, classLoader, LOGBACK_TEXT))
            .doesNotThrowAnyException();
      }
    }

    @ParameterizedTest
    @FieldSource("OVERRIDE_FORMS")
    @DisplayName("logging.log4j2.config.override stops startup and is named")
    void rejectsOverride(String key, String value) {
      var environment = environmentWithOverride(key, value);

      assertStopsWith(
          () -> checkLog4j2(environment, LOG4J2_CLASS_LOADER),
          LoggingConstants.LOG4J2_CONFIG_OVERRIDE + " is set");
    }

    @Test
    @DisplayName("with Logback next to Log4j2, the override is ignored, as Spring Boot ignores it")
    void ignoresOverrideWithLogback() {
      var environment =
          environmentWithFormat(LOGBACK_TEXT)
              .withProperty(LoggingConstants.LOG4J2_CONFIG_OVERRIDE, "classpath:own.xml");

      assertThatCode(() -> check(environment)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the message names the override key, never its value")
    void messageNamesOverrideKeyNotValue() {
      var environment =
          environmentWithOverride(
              LoggingConstants.LOG4J2_CONFIG_OVERRIDE,
              "https://user:secret@config.example/own.xml");

      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(() -> checkLog4j2(environment, LOG4J2_CLASS_LOADER))
          .withMessageContaining(LoggingConstants.LOG4J2_CONFIG_OVERRIDE)
          .withMessageNotContaining("secret")
          .withMessageNotContaining("config.example");
    }

    @Test
    @DisplayName("an empty override lets the service start, as Spring Boot loads nothing for it")
    void acceptsEmptyOverride() {
      var environment = environmentWithOverride(LoggingConstants.LOG4J2_CONFIG_OVERRIDE, "");

      assertThatCode(() -> checkLog4j2(environment, LOG4J2_CLASS_LOADER))
          .doesNotThrowAnyException();
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
