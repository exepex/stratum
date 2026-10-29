package com.exepex.stratum.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.condition.StratumModuleConstants;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("ObservabilityEnvironmentPostProcessor sets Spring Boot's defaults before startup")
class ObservabilityEnvironmentPostProcessorTest {

  private static final String APPLICATION_TAG =
      ObservabilityConstants.METRIC_TAGS + ObservabilityConstants.APPLICATION_TAG;

  private static final String ENVIRONMENT_TAG =
      ObservabilityConstants.METRIC_TAGS + ObservabilityConstants.ENVIRONMENT_TAG;

  private static final String HOST_TAG =
      ObservabilityConstants.METRIC_TAGS + ObservabilityConstants.HOST_TAG;

  private static final String PROPAGATE_TASK_CONTEXT = "spring.task.execution.propagate-context";

  private static final String KAFKA_LISTENER_SWITCH = "spring.kafka.listener.observation-enabled";

  /**
   * Spring Boot's Kafka and RabbitMQ observation switches other than the Kafka listener's, spelled
   * out here so a misspelt key in the module fails the test.
   */
  private static final List<String> OTHER_MESSAGING_SWITCHES =
      List.of(
          "spring.kafka.template.observation-enabled",
          "spring.rabbitmq.template.observation-enabled",
          "spring.rabbitmq.listener.simple.observation-enabled",
          "spring.rabbitmq.listener.direct.observation-enabled",
          "spring.rabbitmq.listener.stream.observation-enabled");

  private static final String SERVICE_EXPOSURE = "health,loggers";

  private static final String ON = "true";

  private static final String OFF = "false";

  private static final String PROD = "prod";

  private static final String SERVICE_NAME = "orders";

  private static final String POD_NAME = "orders-7d9f-x2k";

  private final ObservabilityEnvironmentPostProcessor postProcessor =
      new ObservabilityEnvironmentPostProcessor();

  /** Returns an environment with the module on and no export address set. */
  private MockEnvironment environmentWithModuleOn() {
    return new MockEnvironment().withProperty(ObservabilityConstants.ENABLED, ON);
  }

  /** Runs the post-processor on the environment and returns it. */
  private MockEnvironment runPostProcessor(MockEnvironment environment) {
    postProcessor.postProcessEnvironment(environment, new SpringApplication());
    return environment;
  }

  @Nested
  @DisplayName("with the module unset or off")
  class WithModuleOff {

    @Test
    @DisplayName("it turns off sending metrics and spans")
    void turnsExportOff() {
      var unset = runPostProcessor(new MockEnvironment());
      var off =
          runPostProcessor(new MockEnvironment().withProperty(ObservabilityConstants.ENABLED, OFF));

      assertThat(unset.getProperty(ObservabilityConstants.METRICS_EXPORT_ENABLED)).isEqualTo(OFF);
      assertThat(off.getProperty(ObservabilityConstants.TRACING_EXPORT_ENABLED)).isEqualTo(OFF);
      assertThat(unset.getProperty(ObservabilityConstants.WEB_EXPOSURE)).isNull();
      assertThat(unset.getProperty(PROPAGATE_TASK_CONTEXT)).isNull();
      assertThat(
              ObservabilityConstants.MESSAGING_OBSERVATION_SWITCHES.stream()
                  .map(unset::getProperty))
          .containsOnlyNulls();
    }

    @Test
    @DisplayName("a service that sets up export itself keeps its own switch")
    void serviceSwitchWins() {
      var environment =
          runPostProcessor(
              new MockEnvironment()
                  .withProperty(ObservabilityConstants.METRICS_EXPORT_ENABLED, ON));

      assertThat(environment.getProperty(ObservabilityConstants.METRICS_EXPORT_ENABLED))
          .isEqualTo(ON);
    }
  }

  @Nested
  @DisplayName("with the module on")
  class WithModuleOn {

    @Test
    @DisplayName("only health and info are exposed, and export stays Spring Boot's default")
    void exposesSafeEndpoints() {
      var environment = runPostProcessor(environmentWithModuleOn());

      assertThat(environment.getProperty(ObservabilityConstants.WEB_EXPOSURE))
          .isEqualTo(ObservabilityConstants.SAFE_ENDPOINTS);
      assertThat(environment.getProperty(ObservabilityConstants.METRICS_EXPORT_ENABLED)).isNull();
    }

    @Test
    @DisplayName("Spring Boot's executors carry the trace, so @Async work joins the caller's trace")
    void executorsCarryTrace() {
      var environment = runPostProcessor(environmentWithModuleOn());

      assertThat(environment.getProperty(PROPAGATE_TASK_CONTEXT)).isEqualTo(ON);
    }

    @Test
    @DisplayName("every metric is tagged with the service, its environment and its host")
    void tagsEveryMetric() {
      var environment =
          runPostProcessor(
              environmentWithModuleOn()
                  .withProperty(ObservabilityConstants.APPLICATION_NAME, SERVICE_NAME)
                  .withProperty(StratumModuleConstants.SERVICE_ENVIRONMENT, PROD)
                  .withProperty(ObservabilityConstants.HOSTNAME, POD_NAME));

      assertThat(environment.getProperty(APPLICATION_TAG)).isEqualTo(SERVICE_NAME);
      assertThat(environment.getProperty(ENVIRONMENT_TAG)).isEqualTo(PROD);
      assertThat(environment.getProperty(HOST_TAG)).isEqualTo(POD_NAME);
      assertThat(environment.getProperty(ObservabilityConstants.ENVIRONMENT_RESOURCE_ATTRIBUTE))
          .isEqualTo(PROD);
    }

    @Test
    @DisplayName("every timing is stored as a base-2 exponential histogram")
    void storesTimingsAsExponentialHistograms() {
      var environment = runPostProcessor(environmentWithModuleOn());

      assertThat(environment.getProperty(ObservabilityConstants.HISTOGRAM_FOR_EVERY_TIMING))
          .isEqualTo(ON);
      assertThat(environment.getProperty(ObservabilityConstants.HISTOGRAM_FLAVOR))
          .isEqualTo(ObservabilityConstants.EXPONENTIAL_HISTOGRAM);
    }

    @Test
    @DisplayName("Kafka and RabbitMQ messages are traced and timed, unless the service says no")
    void observesMessaging() {
      var environment =
          runPostProcessor(environmentWithModuleOn().withProperty(KAFKA_LISTENER_SWITCH, OFF));

      assertThat(OTHER_MESSAGING_SWITCHES.stream().map(environment::getProperty)).containsOnly(ON);
      assertThat(environment.getProperty(KAFKA_LISTENER_SWITCH)).isEqualTo(OFF);
    }

    @Test
    @DisplayName("a tag whose value is unset or blank is left out")
    void leavesOutUnsetTags() {
      var environment =
          runPostProcessor(
              environmentWithModuleOn()
                  .withProperty(StratumModuleConstants.SERVICE_ENVIRONMENT, " "));

      assertThat(environment.getProperty(APPLICATION_TAG)).isNull();
      assertThat(environment.getProperty(ENVIRONMENT_TAG)).isNull();
      assertThat(environment.getProperty(HOST_TAG)).isNull();
    }

    @Test
    @DisplayName("the service's own exposure wins over Stratum's")
    void serviceExposureWins() {
      var environment =
          runPostProcessor(
              environmentWithModuleOn()
                  .withProperty(ObservabilityConstants.WEB_EXPOSURE, SERVICE_EXPOSURE));

      assertThat(environment.getProperty(ObservabilityConstants.WEB_EXPOSURE))
          .isEqualTo(SERVICE_EXPOSURE);
    }

    @Test
    @DisplayName("an exposure the service sets in Java wins over Stratum's")
    void serviceJavaExposureWins() {
      var environment = environmentWithModuleOn();
      environment
          .getPropertySources()
          .addLast(
              new MapPropertySource(
                  DefaultPropertiesPropertySource.NAME,
                  Map.of(ObservabilityConstants.WEB_EXPOSURE, SERVICE_EXPOSURE)));

      runPostProcessor(environment);
      // Spring Boot moves the default properties last once every post-processor has run.
      DefaultPropertiesPropertySource.moveToEnd(environment);

      assertThat(environment.getProperty(ObservabilityConstants.WEB_EXPOSURE))
          .isEqualTo(SERVICE_EXPOSURE);
      assertThat(environment.getProperty(KAFKA_LISTENER_SWITCH)).isEqualTo(ON);
    }
  }
}
