package com.exepex.stratum.observability.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.core.properties.ServiceInstanceId;
import com.exepex.stratum.observability.ObservabilityConstants;
import com.exepex.stratum.observability.testsupport.ObservabilityTestData;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.registry.otlp.HistogramFlavor;
import io.micrometer.registry.otlp.OtlpConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

// Each test starts and stops the application itself, because it checks what happens at startup or
// at shutdown (ST-TEST-31).
@DisplayName("A short-lived application with the observability starter")
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityStarterIntegrationTest {

  /** The path the test application calls, as if it were a partner's service. */
  private static final String PARTNER_PATH = "/rates";

  /** The path the test application calls through WebClient, as if it were a partner's service. */
  private static final String REACTIVE_PARTNER_PATH = "/fx-rates";

  /** An API key in the partner's query, which the span masks. */
  private static final String API_KEY = "LEAK-key-9d1";

  /** A query string, which the span keeps masked and the metrics leave out. */
  private static final String QUERY = "?currency=EUR-7f3&api_key=" + API_KEY;

  /** The query as the span keeps it: the ordinary value as it is, the API key masked. */
  private static final String MASKED_QUERY = "?currency=EUR-7f3&api_key=****";

  /** A password in the partner's URL, which must not reach spans or metrics. */
  private static final String PASSWORD = "LEAK-password-7c2";

  /** Micrometer's warning when it cannot push metrics. */
  private static final String PUSH_FAILED = "Failed to publish metrics";

  /** Receives what the application sends, and answers the partner call. */
  private final CollectorStub collector = new CollectorStub();

  /**
   * Stops the collector stub after each test, and clears the log level Stratum may have set,
   * because it outlives the application in the test JVM.
   */
  @AfterEach
  void stopCollectorAndResetLogLevel() {
    collector.close();
    ObservabilityTestData.resetExportLogLevels();
  }

  /** Starts the test application with the given command-line arguments. */
  private ConfigurableApplicationContext start(String... args) {
    return new SpringApplicationBuilder(OrdersApplication.class)
        .web(WebApplicationType.NONE)
        .run(args);
  }

  /**
   * Calls the partner with a password and a query in the URL, through Spring's RestClient and its
   * WebClient.
   */
  private void callPartner(ConfigurableApplicationContext context) {
    var observationRegistry = context.getBean(ObservationRegistry.class);
    RestClient.builder()
        .observationRegistry(observationRegistry)
        .build()
        .get()
        .uri(partnerUrlWithPassword(PARTNER_PATH))
        .retrieve()
        .toBodilessEntity();
    WebClient.builder()
        .observationRegistry(observationRegistry)
        .build()
        .get()
        .uri(partnerUrlWithPassword(REACTIVE_PARTNER_PATH))
        .retrieve()
        .toBodilessEntity()
        .block();
  }

  /** Returns the partner's URL for the path, with a password and the query. */
  private String partnerUrlWithPassword(String path) {
    return collector.url(path + QUERY).replace("://", "://user:" + PASSWORD + "@");
  }

  @Test
  @DisplayName(
      "sends its last metrics, as exponential histograms, and spans, each naming the instance,"
          + " when it stops")
  void sendsDataOnExit() {
    var instanceId = ServiceInstanceId.of(new MockEnvironment()).value();
    try (var context =
        start(
            collector.withExportsSentHere(
                "--spring.application.name=orders", "--stratum.environment=prod"))) {
      callPartner(context);
      assertThat(context.getBean(OtlpConfig.class).histogramFlavor())
          .isEqualTo(HistogramFlavor.BASE2_EXPONENTIAL_BUCKET_HISTOGRAM);
      assertThat(context.getBean(OtlpConfig.class).resourceAttributes())
          .containsEntry("service.instance.id", instanceId);
    }

    assertThat(collector.bodiesOf(CollectorStub.TRACES_PATH))
        .anySatisfy(
            spans ->
                assertThat(spans)
                    .contains(collector.url(PARTNER_PATH + MASKED_QUERY))
                    .doesNotContain(PASSWORD)
                    .doesNotContain(API_KEY)
                    .contains("deployment.environment.name")
                    .contains(instanceId));
    assertThat(collector.bodiesOf(CollectorStub.TRACES_PATH))
        .anySatisfy(
            spans ->
                assertThat(spans)
                    .contains(collector.url(REACTIVE_PARTNER_PATH + MASKED_QUERY))
                    .doesNotContain(API_KEY));
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
                    .contains("prod")
                    .contains(instanceId));
  }

  @Test
  @DisplayName("with the module off, sends nothing even though the starter is present")
  void moduleOffSendsNothing() {
    try (var context =
        start(
            collector.withExportsSentHere(
                "--spring.application.name=orders",
                "--" + ObservabilityConstants.ENABLED + "=false"))) {
      callPartner(context);
    }

    assertThat(collector.bodiesOf(CollectorStub.TRACES_PATH)).isEmpty();
    assertThat(collector.bodiesOf(CollectorStub.METRICS_PATH)).isEmpty();
  }

  @Test
  @DisplayName(
      "with no export address, startup stops with SX-OBSERVABILITY-1001, whose fix names the switch"
          + " that turns the module off")
  void missingAddressStopsStartup() {
    assertThatExceptionOfType(StratumException.class)
        .isThrownBy(() -> start())
        .withMessageStartingWith("SX-OBSERVABILITY-1001: ")
        .satisfies(
            failure ->
                assertThat(failure.getErrorCode().getFix())
                    .contains(ObservabilityConstants.ENABLED + "=false"));
  }

  @Test
  @DisplayName("with OpenTelemetry off, starts with only a metrics address and still sends metrics")
  void openTelemetryOffNeedsOnlyMetricsAddress() {
    start(
            "--management.opentelemetry.enabled=false",
            "--management.otlp.metrics.export.url=" + collector.url(CollectorStub.METRICS_PATH))
        .close();

    assertThat(collector.bodiesOf(CollectorStub.METRICS_PATH)).isNotEmpty();
    assertThat(collector.bodiesOf(CollectorStub.TRACES_PATH)).isEmpty();
  }

  /** Starts and stops the application with only metrics sent, to an address. */
  private void runWithMetricsAddress(String metricsAddress) {
    start(
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
}
