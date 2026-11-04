package com.exepex.stratum.observability.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.logging.LoggingContextConstants;
import com.exepex.stratum.observability.testsupport.BaggageTestData;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.context.propagation.ContextPropagators;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

@DisplayName("Only the allowed baggage entries are read from callers and sent on over HTTP")
class BaggageAllowListIntegrationTest {

  /** The downstream path below which the WebFlux handler calls each order with WebClient. */
  private static final String RELAY_PATH = "/downstream/baggage-relay";

  /** The downstream paths that blocking code calls with RestClient and WebClient. */
  private static final String REST_CLIENT_PATH = "/downstream/baggage-rest-client";

  private static final String WEB_CLIENT_PATH = "/downstream/baggage-web-client";

  /** The order the caller sends with its baggage. */
  private static final String ORDER_NUMBER = "ORD-9001";

  /** A sampled W3C trace header from the caller. */
  private static final String CALLER_TRACEPARENT =
      "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  /** The remote field's entry, which Spring Boot also sends in a header of its own. */
  private static final String REMOTE_FIELD_ENTRY = BaggageTestData.REMOTE_FIELD + "=t-1";

  /**
   * Stands in for the downstream service and keeps the baggage header it receives. It also takes
   * the spans at once, so each application closes without retrying a failed send.
   */
  @AutoClose private static final CollectorStub DOWNSTREAM = new CollectorStub();

  /**
   * Points the WebFlux handler's WebClient and the span export at the stub. Spring Boot propagates
   * trace headers only while span export is on.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void callDownstreamStub(DynamicPropertyRegistry registry) {
    registry.add("orders.downstream-url", () -> DOWNSTREAM.url(RELAY_PATH));
    DOWNSTREAM.receiveSpans(registry);
  }

  @Nested
  @SpringBootTest(
      classes = ReactiveOrdersApplication.class,
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = {
        "management.tracing.sampling.probability=1.0",
        // Stratum's startup check needs an address before @DynamicPropertySource runs; the
        // collector stub's address then replaces this one.
        "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:1/v1/traces",
        "management.otlp.metrics.export.enabled=false"
      })
  // Closes the application after these tests, so its tracing and threads never reach the next.
  @DirtiesContext
  @DisplayName("in a WebFlux service that calls another service")
  class WebFluxService {

    /** The port the service listens on. */
    @LocalServerPort private int port;

    @Test
    @DisplayName("a caller's secret and casing variant are not sent on; its correlation ID is")
    void sendsOnOnlyCallersCorrelationId() throws IOException, InterruptedException {
      var request =
          HttpRequest.newBuilder(
                  URI.create(
                      "http://localhost:"
                          + port
                          + ReactiveOrdersApplication.PLACE_ORDER_PATH
                          + "?"
                          + ReactiveOrdersApplication.ORDER_NUMBER_PARAMETER
                          + "="
                          + ORDER_NUMBER))
              .header(CollectorStub.TRACEPARENT_HEADER, CALLER_TRACEPARENT)
              .header(BaggageTestData.BAGGAGE_HEADER, BaggageTestData.CALLER_BAGGAGE)
              .build();

      var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

      assertThat(response.body()).isEqualTo(ORDER_NUMBER);
      assertThat(DOWNSTREAM.baggageHeaderReceivedOn(RELAY_PATH + "/" + ORDER_NUMBER))
          .contains(BaggageTestData.CORRELATION_ID_ENTRY);
    }
  }

  @Nested
  @SpringBootTest(
      classes = OrdersApplication.class,
      properties = {
        "spring.main.web-application-type=none",
        "management.tracing.sampling.probability=1.0",
        "management.tracing.baggage.remote-fields=" + BaggageTestData.REMOTE_FIELD,
        // Stratum's startup check needs an address before @DynamicPropertySource runs; the
        // collector stub's address then replaces this one.
        "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:1/v1/traces",
        "management.otlp.metrics.export.enabled=false"
      })
  // Closes the application after these tests, so its tracing and threads never reach the next.
  @DirtiesContext
  @DisplayName("in blocking code, with a remote field set")
  class BlockingCode {

    /** Starts and ends spans, and sets baggage. */
    @Autowired private Tracer tracer;

    /** Starts observations, which Spring turns into spans. */
    @Autowired private ObservationRegistry observationRegistry;

    /** Spring Boot's RestClient builder, set up for tracing. */
    @Autowired private RestClient.Builder restClientBuilder;

    /** Spring Boot's WebClient builder, set up for tracing. */
    @Autowired private WebClient.Builder webClientBuilder;

    /** Spring Boot's propagators, which servers and message listeners read callers with. */
    @Autowired private ContextPropagators contextPropagators;

    @Test
    @DisplayName(
        "RestClient and WebClient send the correlation ID and the remote field, and none of the"
            + " service's other entries")
    void sendsOnlyAllowedEntries() {
      Observation.createNotStarted("place-order", observationRegistry)
          .observe(this::callBothClientsWithBaggage);

      assertThat(List.of(REST_CLIENT_PATH, WEB_CLIENT_PATH))
          .allSatisfy(
              path ->
                  assertThat(DOWNSTREAM.baggageHeaderReceivedOn(path))
                      .as("baggage on %s", path)
                      .hasValueSatisfying(this::assertOnlyAllowedEntries));
    }

    @Test
    @DisplayName("Spring Boot's propagators read only the caller's allowed entries")
    void readsOnlyAllowedEntries() {
      assertThat(
              BaggageTestData.namesRead(
                  contextPropagators.getTextMapPropagator(),
                  BaggageTestData.CALLER_BAGGAGE + "," + REMOTE_FIELD_ENTRY))
          .containsExactlyInAnyOrder(
              LoggingContextConstants.CORRELATION_ID, BaggageTestData.REMOTE_FIELD);
    }

    /**
     * Checks that a baggage header holds the two allowed entries and nothing else. Micrometer adds
     * {@code ;propagation=unlimited} to a remote field, so only each entry's name and value count.
     */
    private void assertOnlyAllowedEntries(String baggage) {
      assertThat(Stream.of(baggage.split(",")).map(entry -> entry.split(";", 2)[0]))
          .containsExactlyInAnyOrder(BaggageTestData.CORRELATION_ID_ENTRY, REMOTE_FIELD_ENTRY);
    }

    /**
     * Calls the stub with both clients while the service's baggage holds allowed and other names.
     */
    private void callBothClientsWithBaggage() {
      try (var _ =
              tracer.createBaggageInScope(LoggingContextConstants.CORRELATION_ID, "order-42-c7d1");
          var _ = tracer.createBaggageInScope(BaggageTestData.REMOTE_FIELD, "t-1");
          var _ = tracer.createBaggageInScope("evil", "SECRET");
          var _ = tracer.createBaggageInScope("CORRELATIONID", "leak")) {
        restClientBuilder
            .build()
            .get()
            .uri(DOWNSTREAM.url(REST_CLIENT_PATH))
            .retrieve()
            .toBodilessEntity();
        webClientBuilder
            .build()
            .get()
            .uri(DOWNSTREAM.url(WEB_CLIENT_PATH))
            .retrieve()
            .toBodilessEntity()
            .block();
      }
    }
  }
}
