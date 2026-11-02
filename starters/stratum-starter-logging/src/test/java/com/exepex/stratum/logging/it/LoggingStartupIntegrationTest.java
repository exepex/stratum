package com.exepex.stratum.logging.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.logging.LoggingPropertiesCustomizer;
import com.exepex.stratum.logging.testsupport.LockerCodeLoggingSettings;
import com.exepex.stratum.test.StarterTestSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.logging.LoggingSystemProperty;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

@ExtendWith(OutputCaptureExtension.class)
@Slf4j
@DisplayName("The logging starter's settings and checks at startup")
class LoggingStartupIntegrationTest {

  /** The same logging settings, written once as YAML and once as properties. */
  private static final List<String> CONFIGURATION_NAMES =
      List.of("logging-yaml", "logging-properties");

  /** Gives the test application its service name. */
  private static final String SERVICE_NAME = "--spring.application.name=orders";

  /** Switches the logging module on. */
  private static final String MODULE_ON = "--stratum.logging.enabled=true";

  /** Starts the test application with the given command-line arguments. */
  private ConfigurableApplicationContext start(String... args) {
    return new SpringApplicationBuilder(OrdersApplication.class)
        .web(WebApplicationType.NONE)
        .run(args);
  }

  @Test
  @DisplayName("with the module unset, logs stay Spring Boot's plain text")
  void moduleOffKeepsPlainText(CapturedOutput output) {
    // Spring Boot keeps the console format as a JVM system property once any application in this
    // JVM has set it, so an earlier test would make this one see JSON.
    System.clearProperty(
        LoggingSystemProperty.CONSOLE_STRUCTURED_FORMAT.getEnvironmentVariableName());
    try (var _ = start(SERVICE_NAME)) {
      log.info("plain line password=fake-unmasked");
    }

    assertThat(output).contains("plain line password=fake-unmasked").doesNotContain("\"message\"");
  }

  @Test
  @DisplayName("with the module on and no service name, startup stops with SX-LOGGING-1001")
  void missingNameStopsStartup() {
    assertThatExceptionOfType(StratumException.class)
        .isThrownBy(() -> start(MODULE_ON))
        .withMessageStartingWith("SX-LOGGING-1001: ");
  }

  @ParameterizedTest(name = "{0}")
  @FieldSource("CONFIGURATION_NAMES")
  @DisplayName("the same settings work from application.yml and application.properties")
  void settingsBindFromBothFormats(String configName, CapturedOutput output) {
    try (var _ = start("--spring.config.name=" + configName)) {
      log.atInfo().addKeyValue("badge_no", "B-77").log("badge checked");
    }

    assertThat(output)
        .contains("\"message\":\"badge checked\"")
        .contains("\"environment\":\"staging\"")
        .contains("\"badge_no\":\"****\"");
  }

  @Test
  @DisplayName("a log file gets the same masked JSON lines as the console")
  void logFileIsMaskedJson(@TempDir Path folder) throws IOException {
    var file = folder.resolve("orders.log");
    try (var _ = start(SERVICE_NAME, MODULE_ON, "--logging.file.name=" + file)) {
      log.info("file line password=fake-unmasked");
    }

    assertThat(Files.readString(file))
        .contains("\"message\":\"file line password=****\"")
        .doesNotContain("fake-unmasked");
  }

  @Test
  @DisplayName("the text format writes one masked line of text per event, in every profile")
  void textFormatIsMasked(CapturedOutput output) {
    try (var _ =
        start(
            SERVICE_NAME,
            MODULE_ON,
            "--stratum.logging.format=text",
            "--spring.profiles.active=local")) {
      log.atInfo().addKeyValue("api_key", "fake-key").log("text line password=fake-unmasked");
    }

    assertThat(output)
        .contains(" INFO [main] ")
        .contains(" : text line password=**** api_key=****")
        .doesNotContain("fake-unmasked")
        .doesNotContain("fake-key")
        .doesNotContain("\"message\"");
  }

  @Test
  @DisplayName(
      "a service's own Logback configuration keeps its own pattern: Stratum changes it not")
  void ownLogbackConfigurationIsKept(CapturedOutput output) {
    try (var _ =
        start(
            SERVICE_NAME,
            MODULE_ON,
            "--stratum.logging.format=text",
            "--logging.config=classpath:logback-own-pattern.xml")) {
      log.info("own line");
    }

    assertThat(output).contains("OWN-PATTERN INFO own line");
  }

  @Test
  @DisplayName("a customizer class in spring.factories wins over the files")
  void javaConfigurationWinsFromTheFirstLine(@TempDir Path folder, CapturedOutput output)
      throws IOException {
    // Spring Boot keeps the console format as a JVM system property, so clear an earlier test's.
    System.clearProperty(
        LoggingSystemProperty.CONSOLE_STRUCTURED_FORMAT.getEnvironmentVariableName());
    var consoleFormat =
        StarterTestSupport.withCustomizers(
            folder,
            LoggingPropertiesCustomizer.class,
            List.of(LockerCodeLoggingSettings.class),
            this::logLockerLineInTextFormat);

    assertThat(consoleFormat).isEqualTo("ecs");
    assertThat(output)
        .contains("\"message\":\"locker opened\"")
        .contains("\"locker_code\":\"****\"")
        .doesNotContain("L-1234");
  }

  /** Starts with the text format in the files, logs one line and returns the console format. */
  private @Nullable String logLockerLineInTextFormat() {
    try (var context = start(SERVICE_NAME, MODULE_ON, "--stratum.logging.format=text")) {
      log.atInfo().addKeyValue("locker_code", "L-1234").log("locker opened");
      return context.getEnvironment().getProperty("logging.structured.format.console");
    }
  }
}
