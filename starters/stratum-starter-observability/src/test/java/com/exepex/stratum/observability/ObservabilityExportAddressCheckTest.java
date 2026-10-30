package com.exepex.stratum.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.observability.testsupport.ObservabilityTestData;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.support.EnvironmentPostProcessorApplicationListener;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("ObservabilityExportAddressCheck stops startup when an export has no address")
class ObservabilityExportAddressCheckTest {

  private static final String ON = "true";

  private static final String OFF = "false";

  private static final String COLLECTOR = "http://otel-collector:4318";

  private static final String MISSING_ADDRESS = "SX-OBSERVABILITY-1001: ";

  /** The switches that each turn metrics export off. */
  private static final List<String> METRICS_SWITCHES =
      List.of(
          ObservabilityConstants.METRICS_EXPORT_ENABLED,
          ObservabilityConstants.METRICS_DEFAULT_EXPORT_ENABLED);

  /** The switches that each turn trace export off. */
  private static final List<String> TRACING_SWITCHES =
      List.of(
          ObservabilityConstants.TRACING_EXPORT_ENABLED,
          ObservabilityConstants.TRACING_OTLP_EXPORT_ENABLED);

  /** Profiles that were once exempt from the check, and are no longer. */
  private static final List<String> FORMERLY_EXEMPT_PROFILES = List.of("local", "test");

  private final ObservabilityExportAddressCheck check = new ObservabilityExportAddressCheck();

  /**
   * Runs the check on the environment, as Spring Boot does once the environment is ready.
   *
   * @param environment the application's environment
   */
  private void runCheck(MockEnvironment environment) {
    check.onApplicationEvent(
        new ApplicationEnvironmentPreparedEvent(
            new DefaultBootstrapContext(), new SpringApplication(), new String[0], environment));
  }

  @Test
  @DisplayName("it runs after Spring Boot maps the OTEL_EXPORTER_OTLP_* environment variables")
  void runsAfterEnvironmentPostProcessors() {
    assertThat(check.getOrder())
        .isGreaterThan(EnvironmentPostProcessorApplicationListener.DEFAULT_ORDER);
  }

  @Test
  @DisplayName("with the module unset, no address is needed")
  void moduleOffNeedsNoAddress() {
    assertThatCode(() -> runCheck(new MockEnvironment())).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("with both addresses set, startup goes on")
  void bothAddressesSet() {
    var environment =
        ObservabilityTestData.environmentWithModuleOn()
            .withProperty(ObservabilityConstants.METRICS_EXPORT_URL, COLLECTOR + "/v1/metrics")
            .withProperty(ObservabilityConstants.TRACING_EXPORT_ENDPOINT, COLLECTOR + "/v1/traces");

    assertThatCode(() -> runCheck(environment)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("startup stops with SX-OBSERVABILITY-1001, naming both missing properties")
  void bothMissingStopStartup() {
    assertThatExceptionOfType(StratumException.class)
        .isThrownBy(() -> runCheck(ObservabilityTestData.environmentWithModuleOn()))
        .withMessage(
            "SX-OBSERVABILITY-1001: An OTLP export address is not set, so metrics or traces"
                + " would be lost: set management.opentelemetry.tracing.export.otlp.endpoint"
                + " and management.otlp.metrics.export.url, or turn that export off");
  }

  @Test
  @DisplayName("a blank metrics address stops startup and names only that property")
  void blankMetricsAddressStopsStartup() {
    var environment =
        ObservabilityTestData.environmentWithModuleOn()
            .withProperty(ObservabilityConstants.METRICS_EXPORT_URL, " ")
            .withProperty(ObservabilityConstants.TRACING_EXPORT_ENDPOINT, COLLECTOR);

    assertThatExceptionOfType(StratumException.class)
        .isThrownBy(() -> runCheck(environment))
        .withMessageEndingWith(": set management.otlp.metrics.export.url, or turn that export off");
  }

  @ParameterizedTest
  @FieldSource("METRICS_SWITCHES")
  @DisplayName("metrics turned off, by the OTLP or the default switch, need no address")
  void metricsTurnedOffNeedNoAddress(String metricsSwitch) {
    var environment =
        ObservabilityTestData.environmentWithModuleOn()
            .withProperty(metricsSwitch, OFF)
            .withProperty(ObservabilityConstants.TRACING_EXPORT_ENABLED, OFF);

    assertThatCode(() -> runCheck(environment)).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @FieldSource("TRACING_SWITCHES")
  @DisplayName("traces turned off, by the general or the OTLP switch, need no address")
  void tracesTurnedOffNeedNoAddress(String tracingSwitch) {
    var environment =
        ObservabilityTestData.environmentWithModuleOn()
            .withProperty(ObservabilityConstants.METRICS_EXPORT_ENABLED, OFF)
            .withProperty(tracingSwitch, OFF);

    assertThatCode(() -> runCheck(environment)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("an OTLP switch that is on wins over the general switch, as in Spring Boot")
  void specificSwitchWins() {
    var environment =
        ObservabilityTestData.environmentWithModuleOn()
            .withProperty(ObservabilityConstants.METRICS_DEFAULT_EXPORT_ENABLED, OFF)
            .withProperty(ObservabilityConstants.METRICS_EXPORT_ENABLED, ON)
            .withProperty(ObservabilityConstants.TRACING_EXPORT_ENABLED, OFF)
            .withProperty(ObservabilityConstants.TRACING_OTLP_EXPORT_ENABLED, ON);

    assertThatExceptionOfType(StratumException.class)
        .isThrownBy(() -> runCheck(environment))
        .withMessageContaining(ObservabilityConstants.METRICS_EXPORT_URL)
        .withMessageContaining(ObservabilityConstants.TRACING_EXPORT_ENDPOINT);
  }

  @Test
  @DisplayName("with OpenTelemetry off, spans need no address and metrics still do")
  void openTelemetryOffNeedsOnlyMetricsAddress() {
    var environment =
        ObservabilityTestData.environmentWithModuleOn()
            .withProperty(ObservabilityConstants.OPEN_TELEMETRY_ENABLED, OFF);

    assertThatExceptionOfType(StratumException.class)
        .isThrownBy(() -> runCheck(environment))
        .withMessageEndingWith(": set management.otlp.metrics.export.url, or turn that export off");
  }

  @Test
  @DisplayName("with OpenTelemetry off and a metrics address, startup goes on")
  void openTelemetryOffWithMetricsAddress() {
    var environment =
        ObservabilityTestData.environmentWithModuleOn()
            .withProperty(ObservabilityConstants.OPEN_TELEMETRY_ENABLED, OFF)
            .withProperty(ObservabilityConstants.METRICS_EXPORT_URL, COLLECTOR + "/v1/metrics");

    assertThatCode(() -> runCheck(environment)).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @FieldSource("FORMERLY_EXEMPT_PROFILES")
  @DisplayName("the local and test profiles need the addresses too, as every other profile does")
  void everyProfileNeedsAddresses(String profile) {
    var environment = ObservabilityTestData.environmentWithModuleOn();
    environment.setActiveProfiles(profile);

    assertThatExceptionOfType(StratumException.class)
        .isThrownBy(() -> runCheck(environment))
        .withMessageStartingWith(MISSING_ADDRESS);
  }
}
