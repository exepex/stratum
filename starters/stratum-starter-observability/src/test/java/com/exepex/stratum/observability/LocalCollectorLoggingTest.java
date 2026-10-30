package com.exepex.stratum.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.observability.testsupport.ObservabilityTestData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.boot.support.EnvironmentPostProcessorApplicationListener;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

@DisplayName(
    "LocalCollectorLogging drops metrics and spans quietly when the Collector is on this machine")
class LocalCollectorLoggingTest {

  private static final String OFF = "off";

  /** The level the service chose. */
  private static final String SERVICE_LEVEL = "warn";

  /** Metrics addresses on this machine. */
  private static final List<String> LOCAL_ADDRESSES =
      List.of(
          "http://localhost:4318/v1/metrics",
          "http://127.0.0.1:4318/v1/metrics",
          "http://127.0.0.2:4318/v1/metrics",
          "http://[::1]:4318/v1/metrics");

  /** Metrics addresses that are not on this machine, or cannot be read. */
  private static final List<String> OTHER_ADDRESSES =
      List.of(
          "http://otel-collector:4318/v1/metrics",
          "http://10.0.0.7:4318/v1/metrics",
          "http://localhost.example.com:4318/v1/metrics",
          "http://local host/v1/metrics",
          "");

  /**
   * The loggers OpenTelemetry uses for failed span sends over HTTP and gRPC, spelled out here so a
   * misspelt logger in the module fails the test.
   */
  private static final String SPAN_HTTP_EXPORT_LOG_LEVEL =
      "logging.level.io.opentelemetry.exporter.internal.http.HttpExporter";

  private static final String SPAN_GRPC_EXPORT_LOG_LEVEL =
      "logging.level.io.opentelemetry.exporter.internal.grpc.GrpcExporter";

  /** A span address on this machine. */
  private static final String LOCAL_SPAN_ADDRESS = "http://localhost:4318/v1/traces";

  /** A span address that is not on this machine. */
  private static final String OTHER_SPAN_ADDRESS = "http://otel-collector:4318/v1/traces";

  private final LocalCollectorLogging localCollectorLogging = new LocalCollectorLogging();

  /**
   * Returns an environment with the module on and the given metrics address.
   *
   * @param metricsAddress the metrics address
   * @return the environment
   */
  private MockEnvironment environmentWithModuleOn(String metricsAddress) {
    return ObservabilityTestData.environmentWithModuleOn()
        .withProperty(ObservabilityConstants.METRICS_EXPORT_URL, metricsAddress);
  }

  /**
   * Runs the listener on the environment, as Spring Boot does once the environment is ready.
   *
   * @param environment the application's environment
   */
  private void run(MockEnvironment environment) {
    localCollectorLogging.onApplicationEvent(
        new ApplicationEnvironmentPreparedEvent(
            new DefaultBootstrapContext(), new SpringApplication(), new String[0], environment));
  }

  @Test
  @DisplayName("it runs after OTEL_* addresses are mapped and before Spring Boot sets log levels")
  void runsBetweenPostProcessorsAndLogging() {
    assertThat(localCollectorLogging.getOrder())
        .isGreaterThan(EnvironmentPostProcessorApplicationListener.DEFAULT_ORDER)
        .isLessThan(LoggingApplicationListener.DEFAULT_ORDER);
  }

  @ParameterizedTest
  @FieldSource("LOCAL_ADDRESSES")
  @DisplayName("a metrics address on this machine turns Micrometer's push warning off")
  void localAddressTurnsWarningOff(String metricsAddress) {
    var environment = environmentWithModuleOn(metricsAddress);

    run(environment);

    assertThat(environment.getProperty(LocalCollectorLogging.METRICS_EXPORT_LOG_LEVEL))
        .isEqualTo(OFF);
  }

  @ParameterizedTest
  @FieldSource("OTHER_ADDRESSES")
  @DisplayName("any other metrics address keeps the warning")
  void otherAddressKeepsWarning(String metricsAddress) {
    var environment = environmentWithModuleOn(metricsAddress);

    run(environment);

    assertThat(environment.getProperty(LocalCollectorLogging.METRICS_EXPORT_LOG_LEVEL)).isNull();
  }

  @Test
  @DisplayName("with no metrics address, the warning is kept")
  void missingAddressKeepsWarning() {
    var environment = ObservabilityTestData.environmentWithModuleOn();

    run(environment);

    assertThat(environment.getProperty(LocalCollectorLogging.METRICS_EXPORT_LOG_LEVEL)).isNull();
  }

  @Test
  @DisplayName("a span address on this machine turns OpenTelemetry's send error off")
  void localSpanAddressTurnsErrorOff() {
    var environment =
        ObservabilityTestData.environmentWithModuleOn()
            .withProperty(ObservabilityConstants.TRACING_EXPORT_ENDPOINT, LOCAL_SPAN_ADDRESS);

    run(environment);

    assertThat(environment.getProperty(SPAN_HTTP_EXPORT_LOG_LEVEL)).isEqualTo(OFF);
    assertThat(environment.getProperty(SPAN_GRPC_EXPORT_LOG_LEVEL)).isEqualTo(OFF);
    assertThat(environment.getProperty(LocalCollectorLogging.METRICS_EXPORT_LOG_LEVEL)).isNull();
  }

  @Test
  @DisplayName("each address is checked on its own")
  void eachAddressIsCheckedOnItsOwn() {
    var environment =
        environmentWithModuleOn(LOCAL_ADDRESSES.getFirst())
            .withProperty(ObservabilityConstants.TRACING_EXPORT_ENDPOINT, OTHER_SPAN_ADDRESS);

    run(environment);

    assertThat(environment.getProperty(LocalCollectorLogging.METRICS_EXPORT_LOG_LEVEL))
        .isEqualTo(OFF);
    assertThat(environment.getProperty(SPAN_HTTP_EXPORT_LOG_LEVEL)).isNull();
    assertThat(environment.getProperty(SPAN_GRPC_EXPORT_LOG_LEVEL)).isNull();
  }

  @Test
  @DisplayName("with the module unset, nothing changes")
  void moduleOffChangesNothing() {
    var environment =
        new MockEnvironment()
            .withProperty(ObservabilityConstants.METRICS_EXPORT_URL, LOCAL_ADDRESSES.getFirst());

    run(environment);

    assertThat(environment.getProperty(LocalCollectorLogging.METRICS_EXPORT_LOG_LEVEL)).isNull();
  }

  @Test
  @DisplayName("a log level the service sets wins")
  void serviceLogLevelWins() {
    var environment =
        environmentWithModuleOn(LOCAL_ADDRESSES.getFirst())
            .withProperty(LocalCollectorLogging.METRICS_EXPORT_LOG_LEVEL, SERVICE_LEVEL);

    run(environment);

    assertThat(environment.getProperty(LocalCollectorLogging.METRICS_EXPORT_LOG_LEVEL))
        .isEqualTo(SERVICE_LEVEL);
  }

  @Test
  @DisplayName("a log level the service sets in Java default properties wins")
  void serviceJavaLogLevelWins() {
    var environment = environmentWithModuleOn(LOCAL_ADDRESSES.getFirst());
    environment
        .getPropertySources()
        .addLast(
            new MapPropertySource(
                DefaultPropertiesPropertySource.NAME,
                Map.of(LocalCollectorLogging.METRICS_EXPORT_LOG_LEVEL, SERVICE_LEVEL)));

    run(environment);
    // Spring Boot moves the default properties last once every listener has run.
    DefaultPropertiesPropertySource.moveToEnd(environment);

    assertThat(environment.getProperty(LocalCollectorLogging.METRICS_EXPORT_LOG_LEVEL))
        .isEqualTo(SERVICE_LEVEL);
  }
}
