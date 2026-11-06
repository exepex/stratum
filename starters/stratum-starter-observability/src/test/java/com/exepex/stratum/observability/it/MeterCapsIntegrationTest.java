package com.exepex.stratum.observability.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.observability.testsupport.MeterCountTestData;
import com.exepex.stratum.observability.testsupport.ObservabilityTestData;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

@SpringBootTest(
    classes = OrdersApplication.class,
    properties = {
      "spring.main.web-application-type=none",
      "stratum.observability.metrics.max-client-names=" + MeterCapsIntegrationTest.MAX_HOSTS,
      "stratum.observability.metrics.max-meters=" + MeterCapsIntegrationTest.MAX_METERS,
      "management.tracing.export.enabled=false",
      "management.otlp.metrics.export.enabled=false"
    })
// Closes the application after these tests, so its tracing and threads never reach the next.
@DirtiesContext
@DisplayName("Spring Boot applies the meter caps to the service's registry")
class MeterCapsIntegrationTest {

  /** The most hosts the service's client metrics keep. */
  static final int MAX_HOSTS = 3;

  /** The most meters the service's registry holds, well above what the application makes. */
  static final int MAX_METERS = 1_000;

  /**
   * Six loopback hosts with a closed port, so each call fails at once but is still timed with its
   * host. Every address in 127.0.0.0/8 reaches this machine.
   */
  private static final List<String> HOST_URLS =
      IntStream.rangeClosed(1, 2 * MAX_HOSTS)
          .mapToObj(host -> "http://127.0.0." + host + ":1/")
          .toList();

  /** Spring Boot's RestClient builder, set up for metrics. */
  @Autowired private RestClient.Builder restClientBuilder;

  /** Spring Boot's WebClient builder, set up for metrics. */
  @Autowired private WebClient.Builder webClientBuilder;

  /** The registry that holds the client metrics. */
  @Autowired private MeterRegistry registry;

  @Test
  @DisplayName("RestClient and WebClient calls to six hosts leave three client.name values")
  void keepsOnlyAllowedHosts() {
    var restClient = restClientBuilder.build();
    var webClient = webClientBuilder.build();

    HOST_URLS.forEach(
        url ->
            assertThatThrownBy(() -> restClient.get().uri(url).retrieve().toBodilessEntity())
                .isNotNull());
    HOST_URLS.forEach(
        url ->
            assertThatThrownBy(() -> webClient.get().uri(url).retrieve().toBodilessEntity().block())
                .isNotNull());

    assertThat(MeterCountTestData.clientNamesIn(registry)).hasSize(MAX_HOSTS);
  }

  @Test
  @DisplayName("meters the tag guard drops take no slot, so the guard runs after it")
  void droppedMetersTakeNoSlot() {
    assertThat(registry.getMeters()).hasSizeLessThan(MAX_METERS);

    IntStream.range(0, MAX_METERS)
        .forEach(
            user -> registry.counter(ObservabilityTestData.METRIC_NAME, "userId", "u-" + user));
    registry.counter(ObservabilityTestData.METRIC_NAME, MeterCountTestData.ORDER_TAG, "o-1");

    assertThat(
            registry
                .find(ObservabilityTestData.METRIC_NAME)
                .tag(MeterCountTestData.ORDER_TAG, "o-1")
                .counter())
        .isNotNull();
  }
}
