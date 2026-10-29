package com.exepex.stratum.observability.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.StratumException;
import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

@DisplayName("A short-lived application with the observability starter")
class ObservabilityStarterIntegrationTest {

  /** The same settings, written once as YAML and once as properties. */
  private static final List<String> CONFIGURATION_NAMES =
      List.of("observability-yaml", "observability-properties");

  /** Receives what the application sends. */
  private final CollectorStub collector = new CollectorStub();

  /**
   * Starts the collector stub for one test.
   *
   * @throws IOException if no local port is free
   */
  ObservabilityStarterIntegrationTest() throws IOException {}

  /** Stops the collector stub after each test. */
  @AfterEach
  void stopCollector() {
    collector.close();
  }

  /** Starts the test application with the given command-line arguments. */
  private ConfigurableApplicationContext start(String... args) {
    return new SpringApplicationBuilder(OrdersApplication.class)
        .web(WebApplicationType.NONE)
        .run(args);
  }

  /** Returns the arguments that point both exports at the collector stub. */
  private String[] withCollector(String... args) {
    var collectorArgs =
        List.of(
            "--management.otlp.metrics.export.url=" + collector.url(CollectorStub.METRICS_PATH),
            "--management.opentelemetry.tracing.export.otlp.endpoint="
                + collector.url(CollectorStub.TRACES_PATH),
            "--management.tracing.sampling.probability=1.0");
    return Stream.concat(collectorArgs.stream(), List.of(args).stream()).toArray(String[]::new);
  }

  /** Records one call to a partner with a token in its URL, as Spring's HTTP clients would. */
  private void callPartner(ConfigurableApplicationContext context) {
    Observation.createNotStarted("http.client.requests", context.getBean(ObservationRegistry.class))
        .highCardinalityKeyValue(
            KeyValue.of("http.url", "https://user:secret@partner.com/rates?api_key=abc123"))
        .observe(() -> context.getBean(MeterRegistry.class).counter("orders.placed").increment());
  }

  @Test
  @DisplayName("sends its last metrics and spans when it stops, with the URL cleaned and tags set")
  void sendsDataOnExit() {
    try (var context =
        start(
            withCollector(
                "--spring.application.name=orders",
                "--stratum.environment=prod",
                "--stratum.observability.enabled=true"))) {
      callPartner(context);
    }

    assertThat(collector.bodiesOf(CollectorStub.TRACES_PATH))
        .anySatisfy(
            spans ->
                assertThat(spans)
                    .contains("https://partner.com/rates")
                    .doesNotContain("api_key")
                    .doesNotContain("secret")
                    .contains("deployment.environment.name"));
    assertThat(collector.bodiesOf(CollectorStub.METRICS_PATH))
        .anySatisfy(
            metrics ->
                assertThat(metrics)
                    .contains("orders.placed")
                    .contains("application")
                    .contains("orders")
                    .contains("prod"));
  }

  @Test
  @DisplayName("with the module unset, sends nothing even though the starter is present")
  void moduleOffSendsNothing() {
    try (var context = start(withCollector("--spring.application.name=orders"))) {
      callPartner(context);
    }

    assertThat(collector.bodiesOf(CollectorStub.TRACES_PATH)).isEmpty();
    assertThat(collector.bodiesOf(CollectorStub.METRICS_PATH)).isEmpty();
  }

  @Test
  @DisplayName("with the module on and no export address, startup stops with SX-OBSERVABILITY-1001")
  void missingAddressStopsStartup() {
    assertThatExceptionOfType(StratumException.class)
        .isThrownBy(() -> start("--stratum.observability.enabled=true"))
        .withMessageStartingWith("SX-OBSERVABILITY-1001: ");
  }

  @ParameterizedTest(name = "{0}")
  @FieldSource("CONFIGURATION_NAMES")
  @DisplayName("the switch works from application.yml and application.properties")
  void switchBindsFromBothFormats(String configName) {
    try (var context = start(withCollector("--spring.config.name=" + configName))) {
      assertThat(context.getEnvironment().getProperty("management.endpoints.web.exposure.include"))
          .isEqualTo("health,info");
      assertThat(context.getEnvironment().getProperty("management.metrics.tags.environment"))
          .isEqualTo("staging");
    }
  }
}
