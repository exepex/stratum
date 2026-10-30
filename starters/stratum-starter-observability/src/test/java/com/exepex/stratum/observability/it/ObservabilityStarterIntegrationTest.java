package com.exepex.stratum.observability.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.observability.MetricTagGuard;
import com.exepex.stratum.observability.testsupport.ObservabilityTestData;
import io.micrometer.core.instrument.config.MeterFilterReply;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.registry.otlp.HistogramFlavor;
import io.micrometer.registry.otlp.OtlpConfig;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.util.ClassUtils;
import org.springframework.web.client.RestClient;

@DisplayName("A short-lived application with the observability starter")
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityStarterIntegrationTest {

  /** The same settings, written once as YAML and once as properties. */
  private static final List<String> CONFIGURATION_NAMES =
      List.of("observability-yaml", "observability-properties");

  /** The path the test application calls, as if it were a partner's service. */
  private static final String PARTNER_PATH = "/rates";

  /** A query string, which the span keeps and the metrics leave out. */
  private static final String QUERY = "?currency=EUR-7f3";

  /** A password in the partner's URL, which must not reach spans or metrics. */
  private static final String PASSWORD = "LEAK-password-7c2";

  /** The argument that turns the module on. */
  private static final String MODULE_ON = "--stratum.observability.enabled=true";

  /** The logger of Micrometer's OTLP registry. */
  private static final String METRICS_EXPORT_LOGGER =
      "io.micrometer.registry.otlp.OtlpMeterRegistry";

  /** Micrometer's warning when it cannot push metrics. */
  private static final String PUSH_FAILED = "Failed to publish metrics";

  /** The loggers of OpenTelemetry's span exporters, over HTTP and gRPC. */
  private static final List<String> SPAN_EXPORT_LOGGERS =
      List.of(
          "io.opentelemetry.exporter.internal.http.HttpExporter",
          "io.opentelemetry.exporter.internal.grpc.GrpcExporter");

  /** OpenTelemetry's error when it cannot send spans. */
  private static final String SPAN_EXPORT_FAILED = "Failed to export spans";

  /** How long to wait for spans to be sent. */
  private static final long FLUSH_TIMEOUT_SECONDS = 15;

  /** The ways Spring Boot can send spans over OTLP. */
  private static final List<String> SPAN_TRANSPORTS = List.of("http", "grpc");

  /** Receives what the application sends, and answers the partner call. */
  private final CollectorStub collector = new CollectorStub();

  /**
   * Starts the collector stub for one test.
   *
   * @throws IOException if no local port is free
   */
  ObservabilityStarterIntegrationTest() throws IOException {}

  /**
   * Stops the collector stub after each test, and clears the log level Stratum may have set,
   * because it outlives the application in the test JVM.
   */
  @AfterEach
  void stopCollectorAndResetLogLevel() {
    collector.close();
    var loggingSystem = LoggingSystem.get(ClassUtils.getDefaultClassLoader());
    Stream.concat(Stream.of(METRICS_EXPORT_LOGGER), SPAN_EXPORT_LOGGERS.stream())
        .forEach(logger -> loggingSystem.setLogLevel(logger, null));
  }

  /** Starts the test application with the given command-line arguments. */
  private ConfigurableApplicationContext start(String... args) {
    return new SpringApplicationBuilder(OrdersApplication.class)
        .web(WebApplicationType.NONE)
        .run(args);
  }

  /** Calls the partner with a password and a query in the URL, through Spring's RestClient. */
  private void callPartner(ConfigurableApplicationContext context) {
    var partnerUrl =
        collector.url(PARTNER_PATH + QUERY).replace("://", "://user:" + PASSWORD + "@");
    RestClient.builder()
        .observationRegistry(context.getBean(ObservationRegistry.class))
        .build()
        .get()
        .uri(partnerUrl)
        .retrieve()
        .toBodilessEntity();
  }

  @Test
  @DisplayName("sends its last metrics, as exponential histograms, and spans when it stops")
  void sendsDataOnExit() {
    try (var context =
        start(
            collector.withExportsSentHere(
                "--spring.application.name=orders", "--stratum.environment=prod", MODULE_ON))) {
      callPartner(context);
      assertThat(context.getBean(OtlpConfig.class).histogramFlavor())
          .isEqualTo(HistogramFlavor.BASE2_EXPONENTIAL_BUCKET_HISTOGRAM);
    }

    assertThat(collector.bodiesOf(CollectorStub.TRACES_PATH))
        .anySatisfy(
            spans ->
                assertThat(spans)
                    .contains(collector.url(PARTNER_PATH + QUERY))
                    .doesNotContain(PASSWORD)
                    .contains("deployment.environment.name"));
    assertThat(collector.bodiesOf(CollectorStub.METRICS_PATH))
        .anySatisfy(
            metrics ->
                assertThat(metrics)
                    .contains("http.client.requests")
                    .contains(PARTNER_PATH)
                    .doesNotContain(QUERY)
                    .doesNotContain(PASSWORD)
                    .contains("application")
                    .contains("orders")
                    .contains("prod"));
  }

  @Test
  @DisplayName("with the module unset, sends nothing even though the starter is present")
  void moduleOffSendsNothing() {
    try (var context = start(collector.withExportsSentHere("--spring.application.name=orders"))) {
      callPartner(context);
    }

    assertThat(collector.bodiesOf(CollectorStub.TRACES_PATH)).isEmpty();
    assertThat(collector.bodiesOf(CollectorStub.METRICS_PATH)).isEmpty();
  }

  @Test
  @DisplayName("with the module on and no export address, startup stops with SX-OBSERVABILITY-1001")
  void missingAddressStopsStartup() {
    assertThatExceptionOfType(StratumException.class)
        .isThrownBy(() -> start(MODULE_ON))
        .withMessageStartingWith("SX-OBSERVABILITY-1001: ");
  }

  @Test
  @DisplayName("with OpenTelemetry off, starts with only a metrics address and still sends metrics")
  void openTelemetryOffNeedsOnlyMetricsAddress() {
    start(
            MODULE_ON,
            "--management.opentelemetry.enabled=false",
            "--management.otlp.metrics.export.url=" + collector.url(CollectorStub.METRICS_PATH))
        .close();

    assertThat(collector.bodiesOf(CollectorStub.METRICS_PATH)).isNotEmpty();
    assertThat(collector.bodiesOf(CollectorStub.TRACES_PATH)).isEmpty();
  }

  @ParameterizedTest
  @FieldSource("CONFIGURATION_NAMES")
  @DisplayName("the settings work from application.yml and application.properties")
  void settingsBindFromBothFormats(String configName) {
    try (var context = start(collector.withExportsSentHere("--spring.config.name=" + configName))) {
      var guard = context.getBean(MetricTagGuard.class);
      assertThat(guard.accept(ObservabilityTestData.counterTaggedWith("tenantId")))
          .isEqualTo(MeterFilterReply.DENY);
      assertThat(guard.accept(ObservabilityTestData.counterTaggedWith("orderId")))
          .isEqualTo(MeterFilterReply.NEUTRAL);
      assertThat(context.getEnvironment().getProperty("management.endpoints.web.exposure.include"))
          .isEqualTo("health,info");
      assertThat(context.getEnvironment().getProperty("management.metrics.tags.environment"))
          .isEqualTo("staging");
    }
  }

  /** Starts and stops the application with the module on and only metrics sent, to an address. */
  private void runWithMetricsAddress(String metricsAddress) {
    start(
            MODULE_ON,
            "--management.otlp.metrics.export.url=" + metricsAddress,
            "--management.tracing.export.enabled=false")
        .close();
  }

  @Test
  @DisplayName("with no Collector on localhost, metrics are dropped without a warning")
  void localhostWithoutCollectorIsQuiet(CapturedOutput output) {
    runWithMetricsAddress("http://localhost:1/v1/metrics");

    assertThat(output).doesNotContain(PUSH_FAILED);
  }

  @Test
  @DisplayName("with no Collector at another address, the failed push is logged")
  void otherAddressWithoutCollectorWarns(CapturedOutput output) {
    runWithMetricsAddress("http://otel-collector.invalid:1/v1/metrics");

    assertThat(output).contains(PUSH_FAILED);
  }

  /**
   * Starts the application with the module on and only spans sent, to an address over a transport,
   * makes one span and sends it while the application still runs.
   */
  private void runWithSpanAddress(String spanAddress, String transport) {
    try (var context =
        start(
            MODULE_ON,
            "--management.otlp.metrics.export.enabled=false",
            "--management.opentelemetry.tracing.export.otlp.endpoint=" + spanAddress,
            "--management.opentelemetry.tracing.export.otlp.transport=" + transport,
            "--management.tracing.sampling.probability=1.0")) {
      Observation.start("place-order", context.getBean(ObservationRegistry.class)).stop();
      context
          .getBean(SdkTracerProvider.class)
          .forceFlush()
          .join(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
  }

  @ParameterizedTest
  @FieldSource("SPAN_TRANSPORTS")
  @DisplayName("with no Collector on localhost, spans are dropped without an error")
  void localhostWithoutCollectorDropsSpansQuietly(String transport, CapturedOutput output) {
    runWithSpanAddress("http://localhost:1", transport);

    assertThat(output).doesNotContain(SPAN_EXPORT_FAILED);
  }

  @ParameterizedTest
  @FieldSource("SPAN_TRANSPORTS")
  @DisplayName("with no Collector at another address, the failed span send is logged")
  void otherAddressWithoutCollectorLogsSpanError(String transport, CapturedOutput output) {
    runWithSpanAddress("http://otel-collector.invalid:1", transport);

    assertThat(output).contains(SPAN_EXPORT_FAILED);
  }
}
