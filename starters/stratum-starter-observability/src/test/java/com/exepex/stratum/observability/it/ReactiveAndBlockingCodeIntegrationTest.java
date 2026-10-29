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
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@DisplayName(
    "Reactive and blocking code carry the same trace and correlation ID, with every HTTP client")
class ReactiveAndBlockingCodeIntegrationTest {

  /** The caller's trace and correlation ID for the single-request tests. */
  private static final CallerTrace CALLER_TRACE =
      new CallerTrace("4bf92f3577b34da6a3ce929d0e0e4736", "order-flow-7001");

  /** The order sent with the caller's trace in the single-request tests. */
  private static final String ORDER_WITH_CALLER_TRACE = "ORD-7001";

  /** The order sent without trace headers, after {@link #ORDER_WITH_CALLER_TRACE}. */
  private static final String ORDER_WITHOUT_CALLER_TRACE = "ORD-7002";

  /** The caller's W3C baggage header, which the blocking clients must send on. */
  private static final String CALLER_BAGGAGE = CALLER_TRACE.baggage();

  /** How many requests the concurrency test sends at once. */
  private static final int CONCURRENT_REQUESTS = 24;

  /** How many tasks the concurrency test runs on each idle thread pool afterwards. */
  private static final int TASKS_PER_IDLE_POOL = 50;

  /** The downstream path below which WebClient calls each order from the reactive handler. */
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
  private ConfigurableApplicationContext startApplication(
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

  /**
   * A caller's trace ID and correlation ID, and the W3C headers that carry them.
   *
   * @param traceId the caller's trace ID, 32 hex digits
   * @param correlationId the correlation ID the caller sends in baggage
   */
  private record CallerTrace(String traceId, String correlationId) {

    /**
     * Returns the W3C trace header, sampled, with a fixed caller span ID.
     *
     * @return the {@code traceparent} header value
     */
    String traceparent() {
      return "00-" + traceId + "-00f067aa0ba902b7-01";
    }

    /**
     * Returns the W3C baggage header with the correlation ID.
     *
     * @return the {@code baggage} header value
     */
    String baggage() {
      return LoggingContextConstants.CORRELATION_ID + "=" + correlationId;
    }

    /**
     * Returns the IDs a log line in this caller's request must carry.
     *
     * @return the caller's trace ID and correlation ID
     */
    LoggingContextSeen expectedLoggingContext() {
      return new LoggingContextSeen(traceId, correlationId);
    }
  }

  /** Builds a request to the reactive handler for an order, without trace headers. */
  private HttpRequest.Builder reactiveHandlerRequest(
      ConfigurableApplicationContext context, String orderNumber) {
    var port = context.getEnvironment().getProperty("local.server.port");
    return HttpRequest.newBuilder(
        URI.create(
            "http://localhost:"
                + port
                + ReactiveOrdersApplication.PLACE_ORDER_PATH
                + "?"
                + ReactiveOrdersApplication.ORDER_NUMBER_PARAMETER
                + "="
                + orderNumber));
  }

  /** Builds a request to the reactive handler for an order, with the caller's trace headers. */
  private HttpRequest requestWithCallerTrace(
      ConfigurableApplicationContext context, String orderNumber, CallerTrace callerTrace) {
    return reactiveHandlerRequest(context, orderNumber)
        .header(CollectorStub.TRACEPARENT_HEADER, callerTrace.traceparent())
        .header(CollectorStub.BAGGAGE_HEADER, callerTrace.baggage())
        .build();
  }

  /** Sends one request and checks that the handler answered with the order number. */
  private void send(HttpRequest request, String orderNumber)
      throws IOException, InterruptedException {
    var response = caller.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.body()).isEqualTo(orderNumber);
  }

  /**
   * Checks that every place in an order's request, and its downstream call, had the caller's IDs.
   */
  private void assertOrderKeptCallerTrace(
      ReactiveOrdersController controller, String orderNumber, CallerTrace callerTrace) {
    assertThat(List.of(PlaceInReactiveCode.values()))
        .allSatisfy(
            place ->
                assertThat(controller.loggingContextAt(orderNumber, place))
                    .as("%s in %s", place, orderNumber)
                    .isEqualTo(callerTrace.expectedLoggingContext()));
    var downstreamPath = REACTIVE_WEB_CLIENT_PATH + "/" + orderNumber;
    assertThat(collectorAndDownstream.traceparentHeaderReceivedOn(downstreamPath))
        .hasValueSatisfying(traceparent -> assertThat(traceparent).contains(callerTrace.traceId()));
    assertThat(collectorAndDownstream.baggageHeaderReceivedOn(downstreamPath))
        .hasValueSatisfying(baggage -> assertThat(baggage).contains(callerTrace.baggage()));
  }

  @Test
  @DisplayName(
      "a WebFlux handler, a map after a thread change, blocking work on Spring's executor and the"
          + " WebClient call keep the caller's trace and correlation ID")
  void reactiveHandlerKeepsCallerTrace() throws IOException, InterruptedException {
    try (var context =
        startApplication(ReactiveOrdersApplication.class, WebApplicationType.REACTIVE)) {
      send(
          requestWithCallerTrace(context, ORDER_WITH_CALLER_TRACE, CALLER_TRACE),
          ORDER_WITH_CALLER_TRACE);

      assertOrderKeptCallerTrace(
          context.getBean(ReactiveOrdersController.class), ORDER_WITH_CALLER_TRACE, CALLER_TRACE);
    }
  }

  @Test
  @DisplayName(
      "concurrent requests each see only their own IDs at every step, and idle threads keep none")
  void concurrentRequestsSeeOnlyTheirOwnIds() throws InterruptedException, ExecutionException {
    try (var context =
        startApplication(ReactiveOrdersApplication.class, WebApplicationType.REACTIVE)) {
      var callerTraceByOrderNumber =
          IntStream.range(0, CONCURRENT_REQUESTS)
              .boxed()
              .collect(
                  Collectors.toMap(
                      requestIndex -> "ORD-8" + requestIndex,
                      requestIndex ->
                          new CallerTrace(
                              "%032x".formatted(requestIndex + 1), "order-flow-8" + requestIndex)));

      CompletableFuture.allOf(
              callerTraceByOrderNumber.entrySet().stream()
                  .map(
                      order ->
                          caller.sendAsync(
                              requestWithCallerTrace(context, order.getKey(), order.getValue()),
                              HttpResponse.BodyHandlers.ofString()))
                  .toArray(CompletableFuture[]::new))
          .get();

      var controller = context.getBean(ReactiveOrdersController.class);
      callerTraceByOrderNumber.forEach(
          (orderNumber, callerTrace) ->
              assertOrderKeptCallerTrace(controller, orderNumber, callerTrace));
      assertIdleThreadsHoldNoIds(
          context.getBean("applicationTaskExecutor", AsyncTaskExecutor.class));
    }
  }

  /** Checks that tasks run later on Reactor's schedulers and Spring's executor see no IDs. */
  private void assertIdleThreadsHoldNoIds(AsyncTaskExecutor applicationTaskExecutor) {
    var idsSeenOnReactorSchedulers =
        Flux.range(0, TASKS_PER_IDLE_POOL)
            .flatMap(
                _ ->
                    Flux.just(Schedulers.parallel(), Schedulers.boundedElastic())
                        .flatMap(
                            scheduler ->
                                Mono.fromCallable(this::idsInLoggingContext)
                                    .subscribeOn(scheduler)))
            .collectList()
            .block();
    var idsSeenOnSpringExecutor =
        IntStream.range(0, TASKS_PER_IDLE_POOL)
            .mapToObj(_ -> applicationTaskExecutor.submitCompletable(this::idsInLoggingContext))
            .toList()
            .stream()
            .map(CompletableFuture::join)
            .toList();

    assertThat(idsSeenOnReactorSchedulers).allSatisfy(ids -> assertThat(ids).isEmpty());
    assertThat(idsSeenOnSpringExecutor).allSatisfy(ids -> assertThat(ids).isEmpty());
  }

  /** Returns the trace and correlation IDs the logging context holds on this thread. */
  private List<String> idsInLoggingContext() {
    return Stream.of(LoggingContextConstants.TRACE_ID, LoggingContextConstants.CORRELATION_ID)
        .map(MDC::get)
        .filter(Objects::nonNull)
        .toList();
  }

  @Test
  @DisplayName("a request without a caller's trace gets its own IDs, never the previous request's")
  void nextRequestDoesNotSeePreviousRequestIds() throws IOException, InterruptedException {
    try (var context =
        startApplication(ReactiveOrdersApplication.class, WebApplicationType.REACTIVE)) {
      send(
          requestWithCallerTrace(context, ORDER_WITH_CALLER_TRACE, CALLER_TRACE),
          ORDER_WITH_CALLER_TRACE);
      send(
          reactiveHandlerRequest(context, ORDER_WITHOUT_CALLER_TRACE).build(),
          ORDER_WITHOUT_CALLER_TRACE);
      var controller = context.getBean(ReactiveOrdersController.class);

      assertThat(List.of(PlaceInReactiveCode.values()))
          .map(place -> controller.loggingContextAt(ORDER_WITHOUT_CALLER_TRACE, place))
          .allSatisfy(this::assertOwnTraceIsTheCorrelationId);
    }
  }

  /** Checks that a request with no caller's trace saw its own trace ID as its correlation ID. */
  private void assertOwnTraceIsTheCorrelationId(@Nullable LoggingContextSeen loggingContext) {
    assertThat(loggingContext)
        .isNotNull()
        .satisfies(seen -> assertThat(seen.correlationId()).isEqualTo(seen.traceId()))
        .extracting(LoggingContextSeen::traceId)
        .isNotNull()
        .isNotEqualTo(CALLER_TRACE.traceId());
  }

  @Test
  @DisplayName(
      "RestClient, RestTemplate and WebClient in blocking code send the same trace headers")
  void blockingClientsSendSameTraceHeaders() {
    try (var context = startApplication(OrdersApplication.class, WebApplicationType.NONE)) {
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
            LoggingContextConstants.CORRELATION_ID, CALLER_TRACE.correlationId())) {
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
