package com.exepex.stratum.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.core.properties.ServiceInstanceId;
import com.exepex.stratum.observability.testsupport.ObservabilityTestData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Hooks;

@DisplayName("ObservabilityEnvironmentPostProcessor sets Spring Boot's defaults before startup")
class ObservabilityEnvironmentPostProcessorTest {

  private static final String APPLICATION_TAG =
      ObservabilityConstants.METRIC_TAGS + ObservabilityConstants.APPLICATION_TAG;

  private static final String ENVIRONMENT_TAG =
      ObservabilityConstants.METRIC_TAGS + ObservabilityConstants.ENVIRONMENT_TAG;

  private static final String HOST_TAG =
      ObservabilityConstants.METRIC_TAGS + ObservabilityConstants.HOST_TAG;

  private static final String PROPAGATE_TASK_CONTEXT = "spring.task.execution.propagate-context";

  private static final String REACTOR_CONTEXT_PROPAGATION = "spring.reactor.context-propagation";

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
      var environment = runPostProcessor(ObservabilityTestData.environmentWithModuleOn());

      assertThat(environment.getProperty(ObservabilityConstants.WEB_EXPOSURE))
          .isEqualTo("health,info");
      assertThat(environment.getProperty(ObservabilityConstants.METRICS_EXPORT_ENABLED)).isNull();
    }

    @Test
    @DisplayName(
        "Spring Boot's executors and every Reactor operator carry the trace, so @Async work and"
            + " reactive code join the caller's trace")
    void executorsAndReactorCarryTrace() {
      var environment = runPostProcessor(ObservabilityTestData.environmentWithModuleOn());

      assertThat(environment.getProperty(PROPAGATE_TASK_CONTEXT)).isEqualTo(ON);
      assertThat(environment.getProperty(REACTOR_CONTEXT_PROPAGATION)).isEqualTo("auto");
    }

    @Test
    @DisplayName(
        "it leaves Reactor's context propagation to the auto-configuration, so that a service's"
            + " @PropertySource files, loaded later, can still turn it off")
    void leavesReactorContextPropagationForLaterDecision() {
      Hooks.disableAutomaticContextPropagation();

      runPostProcessor(ObservabilityTestData.environmentWithModuleOn());

      assertThat(Hooks.isAutomaticContextPropagationEnabled()).isFalse();
    }

    @Test
    @DisplayName("every metric is tagged with the service, its environment and its host")
    void tagsEveryMetric() {
      var environment =
          runPostProcessor(
              ObservabilityTestData.environmentWithModuleOn()
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
      var environment = runPostProcessor(ObservabilityTestData.environmentWithModuleOn());

      assertThat(environment.getProperty(ObservabilityConstants.HISTOGRAM_FOR_EVERY_TIMING))
          .isEqualTo(ON);
      assertThat(environment.getProperty(ObservabilityConstants.HISTOGRAM_FLAVOR))
          .isEqualTo(ObservabilityConstants.EXPONENTIAL_HISTOGRAM);
    }

    @Test
    @DisplayName("Kafka and RabbitMQ messages are traced and timed, unless the service says no")
    void observesMessaging() {
      var environment =
          runPostProcessor(
              ObservabilityTestData.environmentWithModuleOn()
                  .withProperty(KAFKA_LISTENER_SWITCH, OFF));

      assertThat(OTHER_MESSAGING_SWITCHES.stream().map(environment::getProperty)).containsOnly(ON);
      assertThat(environment.getProperty(KAFKA_LISTENER_SWITCH)).isEqualTo(OFF);
    }

    @Test
    @DisplayName("the service's own Spring Boot keys win over Stratum's defaults")
    void serviceBootKeysWin() {
      var environment =
          runPostProcessor(
              ObservabilityTestData.environmentWithModuleOn()
                  .withProperty(ObservabilityConstants.WEB_EXPOSURE, SERVICE_EXPOSURE)
                  .withProperty(ObservabilityConstants.HISTOGRAM_FOR_EVERY_TIMING, OFF)
                  .withProperty(PROPAGATE_TASK_CONTEXT, OFF));

      assertThat(environment.getProperty(ObservabilityConstants.WEB_EXPOSURE))
          .isEqualTo(SERVICE_EXPOSURE);
      assertThat(environment.getProperty(ObservabilityConstants.HISTOGRAM_FOR_EVERY_TIMING))
          .isEqualTo(OFF);
      assertThat(environment.getProperty(PROPAGATE_TASK_CONTEXT)).isEqualTo(OFF);
    }

    @Test
    @DisplayName("a tag whose value is unset or blank is left out")
    void leavesOutUnsetTags() {
      var environment =
          runPostProcessor(
              ObservabilityTestData.environmentWithModuleOn()
                  .withProperty(StratumModuleConstants.SERVICE_ENVIRONMENT, " "));

      assertThat(environment.getProperty(APPLICATION_TAG)).isNull();
      assertThat(environment.getProperty(ENVIRONMENT_TAG)).isNull();
      assertThat(environment.getProperty(HOST_TAG)).isNull();
    }

    @Test
    @DisplayName("an exposure the service sets in Java wins over Stratum's")
    void serviceJavaExposureWins() {
      var environment = ObservabilityTestData.environmentWithModuleOn();
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

  @Nested
  @DisplayName("the instance ID")
  class InstanceId {

    /** Spring Boot's key, spelled out so that a test fails if Stratum's constant changes. */
    private static final String INSTANCE_ID_KEY =
        "management.opentelemetry.resource-attributes[service.instance.id]";

    @Test
    @DisplayName("with the module on, every span and metric names the instance this JVM generated")
    void namesGeneratedInstance() {
      var environment = runPostProcessor(ObservabilityTestData.environmentWithModuleOn());

      assertThat(environment.getProperty(INSTANCE_ID_KEY))
          .isEqualTo(ServiceInstanceId.of(new MockEnvironment()).value());
    }

    @Test
    @DisplayName("with the module unset, no instance ID is set")
    void moduleOffSetsNoInstanceId() {
      assertThat(runPostProcessor(new MockEnvironment()).getProperty(INSTANCE_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("the service's own instance ID, from OTEL_RESOURCE_ATTRIBUTES, wins")
    void serviceInstanceIdWins() {
      var environment = ObservabilityTestData.environmentWithModuleOn();
      environment
          .getPropertySources()
          .addLast(
              new SystemEnvironmentPropertySource(
                  StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                  Map.of("OTEL_RESOURCE_ATTRIBUTES", "service.instance.id=" + POD_NAME)));

      assertThat(runPostProcessor(environment).getProperty(INSTANCE_ID_KEY)).isEqualTo(POD_NAME);
    }

    @Test
    @DisplayName(
        "a blank instance ID the service sets is what spans carry, and the one its logs use too")
    void blankServiceInstanceIdIsUsedEverywhere() {
      var environment =
          runPostProcessor(
              ObservabilityTestData.environmentWithModuleOn().withProperty(INSTANCE_ID_KEY, " "));
      var spanInstanceId =
          Binder.get(environment)
              .bind(
                  "management.opentelemetry.resource-attributes",
                  Bindable.mapOf(String.class, String.class))
              .get()
              .get("service.instance.id");

      assertThat(spanInstanceId).isEqualTo(" ");
      assertThat(ServiceInstanceId.of(environment).value()).isEqualTo(spanInstanceId);
    }

    @Test
    @DisplayName("the observability module sets no log field: that is the logging module's")
    void setsNoLogField() {
      var environment = runPostProcessor(ObservabilityTestData.environmentWithModuleOn());
      var defaults =
          (EnumerablePropertySource<?>)
              environment.getPropertySources().get(DefaultPropertiesPropertySource.NAME);

      assertThat(defaults.getPropertyNames()).noneMatch(key -> key.startsWith("logging."));
    }
  }
}
