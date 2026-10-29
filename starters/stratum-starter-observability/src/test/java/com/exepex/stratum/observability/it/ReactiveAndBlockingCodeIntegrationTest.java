package com.exepex.stratum.observability.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.observability.it.ReactiveOrdersApplication.LoggingContextSeen;
import com.exepex.stratum.observability.it.ReactiveOrdersApplication.PlaceInReactiveCode;
import com.exepex.stratum.observability.it.ReactiveOrdersApplication.ReactiveOrdersController;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

@DisplayName(
    "Reactive and blocking code carry the same trace and correlation ID, with every HTTP client")
class ReactiveAndBlockingCodeIntegrationTest {

  /** The trace ID of the caller's request. */
  private static final String CALLER_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  /** The caller's W3C trace header, sampled. */
  private static final String CALLER_TRACEPARENT = "00-" + CALLER_TRACE_ID + "-00f067aa0ba902b7-01";

  /** The correlation ID the caller sends in baggage. */
  private static final String CALLER_CORRELATION_ID = "order-flow-7001";

  /** The caller's W3C baggage header. */
  private static final String CALLER_BAGGAGE = "correlationId=" + CALLER_CORRELATION_ID;

  /** The downstream path that WebClient calls from the reactive handler. */
  private static final String REACTIVE_WEB_CLIENT_PATH = "/downstream/reactive-web-client";

  /** The downstream paths that each client calls from blocking code. */
  private static final String BLOCKING_REST_CLIENT_PATH = "/downstream/blocking-rest-client";

  private static final String BLOCKING_REST_TEMPLATE_PATH = "/downstream/blocking-rest-template";

  private static final String BLOCKING_WEB_CLIENT_PATH = "/downstream/blocking-web-client";

  /** Receives the spans, and stands in for the downstream service. */
  private final CollectorStub collectorAndDownstream = new CollectorStub();

  /** Sends requests to the reactive service, as its caller. */
  private final HttpClient caller = HttpClient.newHttpClient();

  /**
   * Starts the stub for one test.
   *
   * @throws IOException if no local port is free
   */
  ReactiveAndBlockingCodeIntegrationTest() throws IOException {}

  /** Stops the stub after each test. */
  @AfterEach
  void stopStub() {
    collectorAndDownstream.close();
  }

  /** Starts an application with the module on, every span kept, and the given web type. */
  private ConfigurableApplicationContext start(
      Class<?> application, WebApplicationType webApplicationType) {
    return new SpringApplicationBuilder(application)
        .web(webApplicationType)
        .run(
            "--stratum.observability.enabled=true",
            "--server.port=0",
            "--orders.downstream-url=" + collectorAndDownstream.url(REACTIVE_WEB_CLIENT_PATH),
            "--management.otlp.metrics.export.url="
                + collectorAndDownstream.url(CollectorStub.METRICS_PATH),
            "--management.opentelemetry.tracing.export.otlp.endpoint="
                + collectorAndDownstream.url(CollectorStub.TRACES_PATH),
            "--management.tracing.sampling.probability=1.0");
  }

  /** Sends one request to the reactive handler, with or without the caller's trace headers. */
  private void callReactiveHandler(ConfigurableApplicationContext context, boolean withCallerTrace)
      throws IOException, InterruptedException {
    var port = context.getEnvironment().getProperty("local.server.port");
    var request =
        HttpRequest.newBuilder(
            URI.create("http://localhost:" + port + ReactiveOrdersApplication.PLACE_ORDER_PATH));
    if (withCallerTrace) {
      request
          .header(CollectorStub.TRACEPARENT_HEADER, CALLER_TRACEPARENT)
          .header(CollectorStub.BAGGAGE_HEADER, CALLER_BAGGAGE);
    }
    var response = caller.send(request.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.body()).isEqualTo("ORD-7001");
  }

  @Test
  @DisplayName(
      "a WebFlux handler, a map after a thread change, and its WebClient call keep the caller's"
          + " trace and correlation ID")
  void reactiveHandlerKeepsCallerTrace() throws IOException, InterruptedException {
    try (var context = start(ReactiveOrdersApplication.class, WebApplicationType.REACTIVE)) {
      callReactiveHandler(context, true);
      var controller = context.getBean(ReactiveOrdersController.class);

      var callerContext = new LoggingContextSeen(CALLER_TRACE_ID, CALLER_CORRELATION_ID);
      assertThat(controller.loggingContextAt(PlaceInReactiveCode.HANDLER_BODY))
          .isEqualTo(callerContext);
      assertThat(controller.loggingContextAt(PlaceInReactiveCode.MAP_AFTER_THREAD_CHANGE))
          .isEqualTo(callerContext);
      assertThat(collectorAndDownstream.traceparentHeaderReceivedOn(REACTIVE_WEB_CLIENT_PATH))
          .hasValueSatisfying(traceparent -> assertThat(traceparent).contains(CALLER_TRACE_ID));
      assertThat(collectorAndDownstream.baggageHeaderReceivedOn(REACTIVE_WEB_CLIENT_PATH))
          .hasValueSatisfying(baggage -> assertThat(baggage).contains(CALLER_BAGGAGE));
    }
  }

  @Test
  @DisplayName("a request without a caller's trace gets its own IDs, never the previous request's")
  void nextRequestDoesNotSeePreviousRequestIds() throws IOException, InterruptedException {
    try (var context = start(ReactiveOrdersApplication.class, WebApplicationType.REACTIVE)) {
      callReactiveHandler(context, true);
      callReactiveHandler(context, false);
      var controller = context.getBean(ReactiveOrdersController.class);

      assertThat(List.of(PlaceInReactiveCode.values()))
          .map(controller::loggingContextAt)
          .allSatisfy(this::assertOwnTraceIsTheCorrelationId);
    }
  }

  /** Checks that a request with no caller's trace saw its own trace ID as its correlation ID. */
  private void assertOwnTraceIsTheCorrelationId(LoggingContextSeen loggingContext) {
    assertThat(loggingContext.traceId()).isNotNull().isNotEqualTo(CALLER_TRACE_ID);
    assertThat(loggingContext.correlationId()).isEqualTo(loggingContext.traceId());
  }

  @Test
  @DisplayName(
      "RestClient, RestTemplate and WebClient in blocking code send the same trace headers")
  void blockingClientsSendSameTraceHeaders() {
    try (var context = start(OrdersApplication.class, WebApplicationType.NONE)) {
      var tracer = context.getBean(Tracer.class);
      Observation.createNotStarted("place-order", context.getBean(ObservationRegistry.class))
          .observe(() -> callEveryClientWithCorrelationId(context, tracer));
    }

    assertThat(
            List.of(
                BLOCKING_REST_CLIENT_PATH, BLOCKING_REST_TEMPLATE_PATH, BLOCKING_WEB_CLIENT_PATH))
        .allSatisfy(this::assertTraceHeadersReceived);
  }

  /** Calls the downstream service with each of Spring's clients, with a correlation ID set. */
  private void callEveryClientWithCorrelationId(
      ConfigurableApplicationContext context, Tracer tracer) {
    try (var _ =
        tracer.createBaggageInScope(
            LoggingContextConstants.CORRELATION_ID, CALLER_CORRELATION_ID)) {
      context
          .getBean(RestClient.Builder.class)
          .build()
          .get()
          .uri(collectorAndDownstream.url(BLOCKING_REST_CLIENT_PATH))
          .retrieve()
          .toBodilessEntity();
      context
          .getBean(RestTemplateBuilder.class)
          .build()
          .getForEntity(collectorAndDownstream.url(BLOCKING_REST_TEMPLATE_PATH), Void.class);
      context
          .getBean(WebClient.Builder.class)
          .build()
          .get()
          .uri(collectorAndDownstream.url(BLOCKING_WEB_CLIENT_PATH))
          .retrieve()
          .toBodilessEntity()
          .block();
    }
  }

  /** Checks that a downstream path received a trace header and the correlation ID in baggage. */
  private void assertTraceHeadersReceived(String downstreamPath) {
    assertThat(collectorAndDownstream.traceparentHeaderReceivedOn(downstreamPath))
        .as("traceparent on %s", downstreamPath)
        .isPresent();
    assertThat(collectorAndDownstream.baggageHeaderReceivedOn(downstreamPath))
        .as("baggage on %s", downstreamPath)
        .hasValueSatisfying(baggage -> assertThat(baggage).contains(CALLER_BAGGAGE));
  }
}
