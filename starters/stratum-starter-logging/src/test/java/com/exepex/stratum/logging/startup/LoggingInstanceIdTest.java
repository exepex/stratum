package com.exepex.stratum.logging.startup;

import static com.exepex.stratum.logging.testsupport.LoggingTestData.environmentWithModuleOnAndServiceName;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.runLoggingPostProcessor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.exepex.stratum.core.properties.ServiceInstanceId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("LoggingEnvironmentPostProcessor writes the instance ID in every log format")
class LoggingInstanceIdTest {

  // Spring Boot's keys, spelled out so that a test fails if Stratum's constants change.

  /** ECS's {@code service.node.name}. */
  private static final String ECS_NODE_NAME = "logging.structured.ecs.service.node-name";

  /** The field added to Logstash lines. */
  private static final String LOGSTASH_FIELD = "logging.structured.json.add[instanceId]";

  /** The field added to GELF lines. */
  private static final String GELF_FIELD = "logging.structured.json.add[_instanceId]";

  /** Every key that can carry the instance ID in a log line. */
  private static final List<String> INSTANCE_ID_KEYS =
      List.of(ECS_NODE_NAME, LOGSTASH_FIELD, GELF_FIELD);

  private static final String STRATUM_FORMAT = "stratum.logging.format";

  private static final String CONSOLE_FORMAT = "logging.structured.format.console";

  private static final String FILE_FORMAT = "logging.structured.format.file";

  private static final String INSTANCE_ID_KEY =
      "management.opentelemetry.resource-attributes[service.instance.id]";

  private static final String TEAM_ID = "orders-blue-1";

  private static final String LOGSTASH = "logstash";

  private static final String GELF = "gelf";

  private static final String OWN_FORMATTER = "com.example.OwnFormatter";

  /** Returns an environment with the module on, a service name and the given properties. */
  private MockEnvironment environmentWith(Map<String, String> properties) {
    var environment = environmentWithModuleOnAndServiceName();
    properties.forEach(environment::withProperty);
    return environment;
  }

  /** Returns the keys that carry the instance ID in the environment. */
  private Set<String> instanceIdKeysIn(MockEnvironment environment) {
    return INSTANCE_ID_KEYS.stream()
        .filter(environment::containsProperty)
        .collect(Collectors.toUnmodifiableSet());
  }

  @Nested
  @DisplayName("for each format the console and the log file write")
  class ByFormat {

    /** The settings, and the keys that carry the instance ID with them. */
    private static final List<Arguments> FORMATS =
        List.of(
            argumentSet("ECS, the default", Map.of(), Set.of(ECS_NODE_NAME)),
            argumentSet("Logstash", Map.of(STRATUM_FORMAT, LOGSTASH), Set.of(LOGSTASH_FIELD)),
            argumentSet("GELF", Map.of(STRATUM_FORMAT, GELF), Set.of(GELF_FIELD)),
            argumentSet("text", Map.of(STRATUM_FORMAT, "text"), Set.of()),
            argumentSet(
                "Spring Boot's keys, in capitals",
                Map.of(CONSOLE_FORMAT, "ECS", FILE_FORMAT, "ECS"),
                Set.of(ECS_NODE_NAME)),
            argumentSet(
                "ECS console and Logstash file: both lines carry it (owner, 2026-11-02)",
                Map.of(FILE_FORMAT, LOGSTASH),
                Set.of(ECS_NODE_NAME, LOGSTASH_FIELD)),
            argumentSet(
                "Logstash console and GELF file: the console decides the name",
                Map.of(STRATUM_FORMAT, LOGSTASH, FILE_FORMAT, GELF),
                Set.of(LOGSTASH_FIELD)),
            argumentSet(
                "a service's own formatter class on both outputs",
                Map.of(CONSOLE_FORMAT, OWN_FORMATTER, FILE_FORMAT, OWN_FORMATTER),
                Set.of()));

    @ParameterizedTest
    @FieldSource("FORMATS")
    @DisplayName("the instance ID goes in that format's own field, and in no other")
    void instanceIdGoesInFormatsField(Map<String, String> settings, Set<String> expectedKeys) {
      var environment = runLoggingPostProcessor(environmentWith(settings));

      assertThat(instanceIdKeysIn(environment)).isEqualTo(expectedKeys);
      var instanceId = ServiceInstanceId.of(new MockEnvironment()).value();
      assertThat(expectedKeys)
          .allSatisfy(key -> assertThat(environment.getProperty(key)).isEqualTo(instanceId));
    }
  }

  @Nested
  @DisplayName("with the service's own values")
  class TeamValues {

    @Test
    @DisplayName("the service's instance ID from Spring Boot's key is written")
    void teamInstanceIdIsWritten() {
      var environment = runLoggingPostProcessor(environmentWith(Map.of(INSTANCE_ID_KEY, TEAM_ID)));

      assertThat(environment.getProperty(ECS_NODE_NAME)).isEqualTo(TEAM_ID);
    }

    @Test
    @DisplayName(
        "a blank instance ID from Spring Boot's key is written as it is, as spans carry it")
    void blankTeamInstanceIdIsWritten() {
      var environment = runLoggingPostProcessor(environmentWith(Map.of(INSTANCE_ID_KEY, " ")));

      assertThat(environment.getProperty(ECS_NODE_NAME)).isEqualTo(" ");
    }

    @Test
    @DisplayName("the service's own service.node.name wins")
    void teamNodeNameWins() {
      var environment = runLoggingPostProcessor(environmentWith(Map.of(ECS_NODE_NAME, "node-7")));

      assertThat(environment.getProperty(ECS_NODE_NAME)).isEqualTo("node-7");
    }

    @Test
    @DisplayName("the service's own added fields stay, next to the instance ID")
    void teamAddedFieldsStay() {
      var environment =
          runLoggingPostProcessor(
              environmentWith(
                  Map.of(STRATUM_FORMAT, LOGSTASH, "logging.structured.json.add.team", "pay")));

      assertThat(
              Binder.get(environment)
                  .bind("logging.structured.json.add", Bindable.mapOf(String.class, String.class))
                  .get())
          .containsEntry("team", "pay")
          .containsKey("instanceId");
    }
  }

  @Nested
  @DisplayName("with each module's switch")
  class ModuleSwitch {

    @Test
    @DisplayName("with the logging module off, no log field is set")
    void moduleOffSetsNothing() {
      var environment = runLoggingPostProcessor(new MockEnvironment().withProperty("x", "y"));

      assertThat(instanceIdKeysIn(environment)).isEmpty();
    }

    @Test
    @DisplayName("the logging module sets no OpenTelemetry key: that is the observability module's")
    void setsNoOpenTelemetryKey() {
      var environment = runLoggingPostProcessor(environmentWith(Map.of()));
      var defaults =
          (EnumerablePropertySource<?>)
              environment.getPropertySources().get(DefaultPropertiesPropertySource.NAME);

      assertThat(Stream.of(defaults.getPropertyNames()))
          .noneMatch(key -> key.startsWith("management.opentelemetry"));
    }
  }
}
