package com.exepex.stratum.logging.it;

import static com.exepex.stratum.logging.testsupport.LoggingTestData.CARD_IN_GROUPS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.logging.LoggingConstants;
import com.exepex.stratum.logging.LoggingPropertiesCustomizer;
import com.exepex.stratum.logging.testsupport.ElementsTestData;
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
import org.junit.jupiter.params.provider.Arguments;
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

  /** Each format that adds the instance ID as a field of its own, with that field's name. */
  private static final List<Arguments> ADDED_INSTANCE_ID_FIELDS =
      List.of(arguments("logstash", "instanceId"), arguments("gelf", "_instanceId"));

  /** The element limit both configuration files set. */
  private static final int MAX_ELEMENTS_IN_FILES = 3;

  /** Gives the test application its service name. */
  private static final String SERVICE_NAME = "--spring.application.name=orders";

  /**
   * The length of the message before the card, so that its first digit sits at character 1,010 of
   * the printed trace and a cut at 1KB falls inside it.
   */
  private static final int TEXT_BEFORE_THE_CARD = 971;

  /** Starts the test application with the given command-line arguments. */
  private ConfigurableApplicationContext start(String... args) {
    return new SpringApplicationBuilder(OrdersApplication.class)
        .web(WebApplicationType.NONE)
        .run(args);
  }

  @Test
  @DisplayName("with the module off, logs stay Spring Boot's plain text")
  void moduleOffKeepsPlainText(CapturedOutput output) {
    // Spring Boot keeps the console format as a JVM system property once any application in this
    // JVM has set it, so an earlier test would make this one see JSON.
    System.clearProperty(
        LoggingSystemProperty.CONSOLE_STRUCTURED_FORMAT.getEnvironmentVariableName());
    try (var _ = start(SERVICE_NAME, "--" + LoggingConstants.ENABLED + "=false")) {
      log.info("plain line password=fake-unmasked");
    }

    assertThat(output).contains("plain line password=fake-unmasked").doesNotContain("\"message\"");
  }

  @Test
  @DisplayName("with no service name, startup stops with SX-LOGGING-1001")
  void missingNameStopsStartup() {
    assertThatExceptionOfType(StratumException.class)
        .isThrownBy(() -> start())
        .withMessageStartingWith("SX-LOGGING-1001: ");
  }

  @ParameterizedTest(name = "{0}")
  @FieldSource("CONFIGURATION_NAMES")
  @DisplayName("the same settings work from application.yml and application.properties")
  void settingsBindFromBothFormats(String configName, CapturedOutput output) {
    try (var _ = start("--spring.config.name=" + configName)) {
      log.atInfo()
          .addKeyValue("badge_no", "B-77")
          .addKeyValue("orderIds", ElementsTestData.numbers(MAX_ELEMENTS_IN_FILES + 2))
          .log("badge checked");
    }

    assertThat(output)
        .contains("\"message\":\"badge checked\"")
        .contains("\"environment\":\"staging\"")
        .contains("\"node\":{\"name\":\"orders-file-1\"}")
        .contains("\"badge_no\":\"****\"")
        .contains("\"orderIds\":[0,1,2,\"...[2 more]\"]");
  }

  @Test
  @DisplayName(
      "an ECS line names its instance in service.node.name, with no empty node and no instanceId")
  void ecsLineNamesInstance(CapturedOutput output) {
    try (var _ = start(SERVICE_NAME)) {
      log.info("ecs instance line");
    }

    assertThat(output)
        .containsPattern("\"node\":\\{\"name\":\"[a-z0-9]{10}\"}")
        .doesNotContain("\"node\":{}")
        .doesNotContain("\"instanceId\"");
  }

  @ParameterizedTest(name = "{0}")
  @FieldSource("ADDED_INSTANCE_ID_FIELDS")
  @DisplayName("a Logstash or GELF line names its instance in its own added field")
  void addedFieldNamesInstance(String format, String field, CapturedOutput output) {
    try (var _ = start(SERVICE_NAME, "--stratum.logging.format=" + format)) {
      log.info("added field instance line");
    }

    assertThat(output).containsPattern("\"" + field + "\":\"[a-z0-9]{10}\"");
  }

  @Test
  @DisplayName("a log file gets the same masked JSON lines as the console")
  void logFileIsMaskedJson(@TempDir Path folder) throws IOException {
    var file = folder.resolve("orders.log");
    try (var _ = start(SERVICE_NAME, "--logging.file.name=" + file)) {
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
        start(SERVICE_NAME, "--stratum.logging.format=text", "--spring.profiles.active=local")) {
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
  @DisplayName("a card number where a long stack trace is cut is not written, not even in part")
  void cardNumberAtTheStackTraceCutIsNotWritten(CapturedOutput output) {
    var failure =
        new IllegalStateException(
            "x".repeat(TEXT_BEFORE_THE_CARD) + " card " + CARD_IN_GROUPS + " declined");
    try (var _ = start(SERVICE_NAME, "--stratum.logging.max-field-length=1KB")) {
      log.error("payment failed", failure);
    }

    assertThat(output).doesNotContain("4111").contains("...[truncated]");
  }

  @Test
  @DisplayName("a service's own Logback configuration stops startup with SX-LOGGING-1003")
  void ownLogbackConfigurationStopsStartup() {
    assertThatExceptionOfType(StratumException.class)
        .isThrownBy(() -> start(SERVICE_NAME, "--logging.config=classpath:logback-own-pattern.xml"))
        .withMessageStartingWith("SX-LOGGING-1003: ");
  }

  @Test
  @DisplayName("an empty console format stops startup with SX-LOGGING-1003")
  void emptyFormatStopsStartup() {
    assertThatExceptionOfType(StratumException.class)
        .isThrownBy(() -> start(SERVICE_NAME, "--logging.structured.format.console="))
        .withMessageStartingWith("SX-LOGGING-1003: ");
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
    try (var context = start(SERVICE_NAME, "--stratum.logging.format=text")) {
      log.atInfo().addKeyValue("locker_code", "L-1234").log("locker opened");
      return context.getEnvironment().getProperty("logging.structured.format.console");
    }
  }
}
