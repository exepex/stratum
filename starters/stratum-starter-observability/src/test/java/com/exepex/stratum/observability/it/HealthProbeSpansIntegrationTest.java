package com.exepex.stratum.observability.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.observability.testsupport.RecordedSpans;
import com.exepex.stratum.test.WebTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

@SpringBootTest(
    classes = {
      ReactiveOrdersApplication.class,
      RecordedSpans.class,
      HealthProbeSpansIntegrationTest.ServiceHealthCheck.class
    },
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.tracing.sampling.probability=1.0",
      "management.endpoint.health.probes.enabled=true",
      // Stratum's startup check needs a span address; sending to it fails without harm, and the
      // test reads the spans from its own span processor.
      "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:1/v1/traces",
      "management.otlp.metrics.export.enabled=false",
      "orders.downstream-url=http://localhost:1"
    })
// Closes each group's application after its tests, so its tracing never reaches the next.
@DirtiesContext
@DisplayName("Health probes keep their timers but make no spans (WebFlux)")
class HealthProbeSpansIntegrationTest {

  /** The liveness probe's path, as Kubernetes calls it. */
  private static final String LIVENESS_PATH = "/actuator/health/liveness";

  /** The whole health endpoint, which runs the service's own health check. */
  private static final String HEALTH_PATH = "/actuator/health";

  /** The observation the service's health check makes, as a check that calls a database does. */
  private static final String HEALTH_CHECK_OBSERVATION = "orders.health.check";

  /** The span name Spring gives the liveness probe's request. */
  private static final String PROBE_SPAN = "http get /actuator/health/{*path}";

  /** How long a test waits for a request's timer or span. */
  private static final Duration WAIT = Duration.ofSeconds(5);

  /** A service health check that needs observation beans, so it would close a bean cycle. */
  @TestConfiguration(proxyBeanMethods = false)
  static class ServiceHealthCheck {

    /**
     * Returns a health check that makes its own observation. It takes a {@code WebClient.Builder}
     * and the registry, as a check of a downstream service does, so it is created while the
     * registry is.
     *
     * @param webClientBuilder the builder a downstream check would use
     * @param observationRegistry times the check
     * @return the check
     */
    @Bean
    HealthIndicator ordersHealthCheck(
        WebClient.Builder webClientBuilder, ObservationRegistry observationRegistry) {
      return () -> {
        Observation.createNotStarted(HEALTH_CHECK_OBSERVATION, observationRegistry)
            .observe(webClientBuilder::build);
        return Health.up().build();
      };
    }
  }

  /** A running service, the spans that ended in it and its timers. */
  class RunningService {

    /** The port the service listens on. */
    @LocalServerPort int port;

    /** Every span that ended in the service. */
    @Autowired RecordedSpans recordedSpans;

    /** The service's timers. */
    @Autowired MeterRegistry meterRegistry;

    /** Forgets the spans of earlier tests. */
    @BeforeEach
    void forgetSpans() {
      recordedSpans.clear();
    }

    /**
     * Calls a path and waits until its request is timed, which is when its span would end.
     *
     * @param servicePort the port to call
     * @param path the path to call
     * @throws IOException if the request cannot be sent
     * @throws InterruptedException if the thread is interrupted while it waits
     */
    void callAndAwaitTimer(int servicePort, String path) throws IOException, InterruptedException {
      var timedBefore = requestsTimed();
      assertThat(WebTestSupport.getFromService(servicePort, path).statusCode())
          .isEqualTo(HttpStatus.OK.value());
      await().atMost(WAIT).until(() -> requestsTimed() > timedBefore);
    }

    /** Returns how many requests the service has timed so far. */
    private long requestsTimed() {
      return meterRegistry.find("http.server.requests").timers().stream()
          .mapToLong(Timer::count)
          .sum();
    }
  }

  @Nested
  @DisplayName("by default")
  class ByDefault extends RunningService {

    @Test
    @DisplayName("a probe is timed but makes no span")
    void probeMakesNoSpan() throws IOException, InterruptedException {
      callAndAwaitTimer(port, LIVENESS_PATH);

      assertThat(recordedSpans.ended()).isEmpty();
    }

    @Test
    @DisplayName("the service's health check inside a probe makes no span either")
    void healthCheckMakesNoSpan() throws IOException, InterruptedException {
      callAndAwaitTimer(port, HEALTH_PATH);

      assertThat(meterRegistry.find(HEALTH_CHECK_OBSERVATION).timer()).isNotNull();
      assertThat(recordedSpans.ended()).isEmpty();
    }

    @Test
    @DisplayName("other requests keep their spans")
    void otherRequestsKeepSpans() throws IOException, InterruptedException {
      WebTestSupport.getFromService(port, ReactiveOrdersApplication.FAILING_ORDER_PATH);

      assertThat(recordedSpans.named("http get " + ReactiveOrdersApplication.FAILING_ORDER_PATH))
          .isNotNull();
    }
  }

  @Nested
  @TestPropertySource(properties = "stratum.observability.tracing.health-probes=true")
  @DisplayName("with health-probes=true")
  class ProbesTraced extends RunningService {

    @Test
    @DisplayName("a probe makes its span")
    void probeMakesSpan() throws IOException, InterruptedException {
      callAndAwaitTimer(port, LIVENESS_PATH);

      assertThat(recordedSpans.named(PROBE_SPAN)).isNotNull();
    }
  }

  @Nested
  @TestPropertySource(properties = "management.endpoint.health.probes.add-additional-paths=true")
  @DisplayName("with the probes also served on the main port")
  class MainPortProbes extends RunningService {

    @ParameterizedTest
    @ValueSource(strings = {"/livez", "/readyz"})
    @DisplayName("a probe on its main-port path makes no span")
    void mainPortProbeMakesNoSpan(String path) throws IOException, InterruptedException {
      callAndAwaitTimer(port, path);

      assertThat(recordedSpans.ended()).isEmpty();
    }
  }

  @Nested
  @TestPropertySource(properties = "management.server.port=0")
  @DisplayName("with a separate management port")
  class SeparateManagementPort extends RunningService {

    /** The management port, which serves the probes. */
    @LocalManagementPort int managementPort;

    @Test
    @DisplayName("a probe on the management port makes no span")
    void probeMakesNoSpan() throws IOException, InterruptedException {
      callAndAwaitTimer(managementPort, LIVENESS_PATH);

      assertThat(recordedSpans.ended()).isEmpty();
    }
  }
}
