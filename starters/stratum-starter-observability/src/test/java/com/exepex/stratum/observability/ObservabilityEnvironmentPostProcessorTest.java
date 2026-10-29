package com.exepex.stratum.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.core.error.StratumException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("ObservabilityEnvironmentPostProcessor sets Spring Boot's defaults before startup")
class ObservabilityEnvironmentPostProcessorTest {

  private static final String APPLICATION_TAG =
      ObservabilityConstants.METRIC_TAGS + ObservabilityConstants.APPLICATION_TAG;

  private static final String ENVIRONMENT_TAG =
      ObservabilityConstants.METRIC_TAGS + ObservabilityConstants.ENVIRONMENT_TAG;

  private static final String HOST_TAG =
      ObservabilityConstants.METRIC_TAGS + ObservabilityConstants.HOST_TAG;

  private static final String ON = "true";

  private static final String OFF = "false";

  private static final String PROD = "prod";

  private static final String SERVICE_NAME = "orders";

  private static final String POD_NAME = "orders-7d9f-x2k";

  private static final String COLLECTOR = "http://otel-collector:4318";

  private final ObservabilityEnvironmentPostProcessor postProcessor =
      new ObservabilityEnvironmentPostProcessor();

  /** Returns an environment with the module on and both export addresses set. */
  private MockEnvironment environmentWithModuleOn() {
    return new MockEnvironment()
        .withProperty(ObservabilityConstants.ENABLED, ON)
        .withProperty(ObservabilityConstants.METRICS_EXPORT_URL, COLLECTOR + "/v1/metrics")
        .withProperty(ObservabilityConstants.TRACING_EXPORT_ENDPOINT, COLLECTOR + "/v1/traces");
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
                  .withProperty(ObservabilityConstants.WEB_EXPOSURE, "health,loggers"));

      assertThat(environment.getProperty(ObservabilityConstants.WEB_EXPOSURE))
          .isEqualTo("health,loggers");
    }
  }

  @Nested
  @DisplayName("with the module on and an export address missing")
  class WithAddressMissing {

    @Test
    @DisplayName("startup stops with SX-OBSERVABILITY-1001, naming both missing properties")
    void bothMissingStopStartup() {
      var environment = new MockEnvironment().withProperty(ObservabilityConstants.ENABLED, ON);

      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(() -> runPostProcessor(environment))
          .withMessage(
              "SX-OBSERVABILITY-1001: An OTLP export address is not set, so metrics or traces"
                  + " would be lost: set management.opentelemetry.tracing.export.otlp.endpoint"
                  + " and management.otlp.metrics.export.url, or turn that export off");
    }

    @Test
    @DisplayName("a blank metrics address stops startup and names only that property")
    void blankMetricsAddressStopsStartup() {
      var environment =
          environmentWithModuleOn().withProperty(ObservabilityConstants.METRICS_EXPORT_URL, " ");

      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(() -> runPostProcessor(environment))
          .withMessageEndingWith(
              ": set management.otlp.metrics.export.url, or turn that export off");
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          ObservabilityConstants.TRACING_EXPORT_ENABLED,
          ObservabilityConstants.TRACING_OTLP_EXPORT_ENABLED
        })
    @DisplayName("an export the service turned off needs no address")
    void exportTurnedOffNeedsNoAddress(String exportSwitch) {
      var environment =
          new MockEnvironment()
              .withProperty(ObservabilityConstants.ENABLED, ON)
              .withProperty(ObservabilityConstants.METRICS_EXPORT_ENABLED, OFF)
              .withProperty(exportSwitch, OFF);

      assertThat(runPostProcessor(environment).getProperty(ObservabilityConstants.WEB_EXPOSURE))
          .isEqualTo(ObservabilityConstants.SAFE_ENDPOINTS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "test", "local,test"})
    @DisplayName("in the local and test profiles no address is needed")
    void localProfilesNeedNoAddress(String profiles) {
      var environment = new MockEnvironment().withProperty(ObservabilityConstants.ENABLED, ON);
      environment.setActiveProfiles(profiles.split(","));

      assertThat(runPostProcessor(environment).getProperty(ObservabilityConstants.WEB_EXPOSURE))
          .isEqualTo(ObservabilityConstants.SAFE_ENDPOINTS);
    }

    @Test
    @DisplayName("a test profile next to another profile still needs the addresses")
    void mixedProfilesNeedAddresses() {
      var environment = new MockEnvironment().withProperty(ObservabilityConstants.ENABLED, ON);
      environment.setActiveProfiles("test", PROD);

      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(() -> runPostProcessor(environment))
          .withMessageStartingWith("SX-OBSERVABILITY-1001: ");
    }
  }
}
