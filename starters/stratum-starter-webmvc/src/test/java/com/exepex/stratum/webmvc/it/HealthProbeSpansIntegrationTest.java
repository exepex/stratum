package com.exepex.stratum.webmvc.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.test.WebTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

@SpringBootTest(
    classes = {OrdersApplication.class, HealthProbeSpansIntegrationTest.ServiceHealthCheck.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "server.servlet.context-path=" + HealthProbeSpansIntegrationTest.CONTEXT_PATH,
      "management.tracing.sampling.probability=1.0",
      "management.endpoint.health.probes.enabled=true",
      "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:1/v1/traces",
      "management.otlp.metrics.export.enabled=false"
    })
// Closes each group's application after its tests, so its tracing never reaches the next.
@DirtiesContext
@DisplayName("Health probes keep their timers but make no spans (Spring MVC)")
class HealthProbeSpansIntegrationTest {

  /** The base path of every request, which the probe paths sit behind. */
  static final String CONTEXT_PATH = "/orders-service";

  /** The liveness probe's path, as Kubernetes calls it. */
  private static final String LIVENESS_PATH = CONTEXT_PATH + "/actuator/health/liveness";

  /** The whole health endpoint, which runs the service's own health check. */
  private static final String HEALTH_PATH = CONTEXT_PATH + "/actuator/health";

  /** The observation the service's health check makes, as a check that calls a database does. */
  private static final String HEALTH_CHECK_OBSERVATION = "orders.health.check";

  /** The span name Spring MVC gives the liveness probe's request. */
  private static final String PROBE_SPAN = "http get /actuator/health/**";

  /** How long a test waits for a request's timer. */
  private static final Duration WAIT = Duration.ofSeconds(5);

  /** A service health check that needs observation beans, and the spans that ended. */
  @TestConfiguration(proxyBeanMethods = false)
  static class ServiceHealthCheck {

    /**
     * Returns a health check that makes its own observation. It takes a {@code RestClient.Builder}
     * and the registry, as a check of a downstream service does, so it is created while the
     * registry is.
     *
     * @param restClientBuilder the builder a downstream check would use
     * @param observationRegistry times the check
     * @return the check
     */
    @Bean
    HealthIndicator ordersHealthCheck(
        RestClient.Builder restClientBuilder, ObservationRegistry observationRegistry) {
      return () -> {
        Observation.createNotStarted(HEALTH_CHECK_OBSERVATION, observationRegistry)
            .observe(restClientBuilder::build);
        return Health.up().build();
      };
    }

    /**
     * Returns a span processor that keeps the name of every span that ends. Spring Boot passes
     * every {@code SpanProcessor} bean to OpenTelemetry.
     *
     * @return the processor
     */
    @Bean
    EndedSpans endedSpans() {
      return new EndedSpans();
    }
  }

  /** Keeps the name of every span that ends. */
  static class EndedSpans implements SpanProcessor {

    /** The names of the spans that ended, oldest first. */
    private final List<String> names = new CopyOnWriteArrayList<>();

    /**
     * Does nothing: names are kept when spans end.
     *
     * @param parentContext the context the span starts in
     * @param span the span that starts
     */
    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
      // Names are kept when spans end.
    }

    /**
     * Returns {@code false}: nothing is done when a span starts.
     *
     * @return {@code false}
     */
    @Override
    public boolean isStartRequired() {
      return false;
    }

    /**
     * Keeps the span's name.
     *
     * @param span the span that ended
     */
    @Override
    public void onEnd(ReadableSpan span) {
      names.add(span.getName());
    }

    /**
     * Returns {@code true}: names are kept when spans end.
     *
     * @return {@code true}
     */
    @Override
    public boolean isEndRequired() {
      return true;
    }
  }

  /** A running service, the spans that ended in it and its timers. */
  class RunningService {

    /** The port the service listens on. */
    @LocalServerPort int port;

    /** The spans that ended in the service. */
    @Autowired EndedSpans endedSpans;

    /** The service's timers. */
    @Autowired MeterRegistry meterRegistry;

    /** Forgets the spans of earlier tests. */
    @BeforeEach
    void forgetSpans() {
      endedSpans.names.clear();
    }

    /**
     * Calls a path and waits until its request is timed, which is when its span would end.
     *
     * @param path the path to call
     * @return the names of the spans that ended
     * @throws IOException if the request cannot be sent
     * @throws InterruptedException if the thread is interrupted while it waits
     */
    List<String> callAndAwaitTimer(String path) throws IOException, InterruptedException {
      var timedBefore = requestsTimed();
      assertThat(WebTestSupport.getFromService(port, path).statusCode())
          .isEqualTo(HttpStatus.OK.value());
      await().atMost(WAIT).until(() -> requestsTimed() > timedBefore);
      return List.copyOf(endedSpans.names);
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
    @DisplayName("a probe behind the context path is timed but makes no span")
    void probeMakesNoSpan() throws IOException, InterruptedException {
      assertThat(callAndAwaitTimer(LIVENESS_PATH)).isEmpty();
    }

    @Test
    @DisplayName("the service's health check inside a probe makes no span either")
    void healthCheckMakesNoSpan() throws IOException, InterruptedException {
      assertThat(callAndAwaitTimer(HEALTH_PATH)).isEmpty();
      assertThat(meterRegistry.find(HEALTH_CHECK_OBSERVATION).timer()).isNotNull();
    }
  }

  @Nested
  @TestPropertySource(properties = "stratum.observability.tracing.health-probes=true")
  @DisplayName("with health-probes=true")
  class ProbesTraced extends RunningService {

    @Test
    @DisplayName("a probe makes its span")
    void probeMakesSpan() throws IOException, InterruptedException {
      assertThat(callAndAwaitTimer(LIVENESS_PATH)).contains(PROBE_SPAN);
    }
  }

  @Nested
  @TestPropertySource(properties = "management.endpoint.health.probes.add-additional-paths=true")
  @DisplayName("with the probes also served on the main port")
  class MainPortProbes extends RunningService {

    @Test
    @DisplayName("a probe on its main-port path makes no span")
    void mainPortProbeMakesNoSpan() throws IOException, InterruptedException {
      assertThat(callAndAwaitTimer(CONTEXT_PATH + "/livez")).isEmpty();
    }
  }
}
