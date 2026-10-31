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
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

  /** Stands in for the downstream service and keeps the trace headers it receives. */
  @AutoClose private static final CollectorStub DOWNSTREAM = new CollectorStub();

  /**
   * Points the reactive handler's WebClient at the downstream stub.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void callDownstreamStub(DynamicPropertyRegistry registry) {
    registry.add("orders.downstream-url", () -> DOWNSTREAM.url(REACTIVE_WEB_CLIENT_PATH));
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

  @Nested
  @SpringBootTest(
      classes = ReactiveOrdersApplication.class,
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = {
        "stratum.observability.enabled=true",
        "management.tracing.sampling.probability=1.0",
        // Spring Boot propagates trace headers only while span export is on. Nothing listens
        // here, so the spans are dropped quietly.
        "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:1/v1/traces",
        "management.otlp.metrics.export.enabled=false"
      })
  // Closes the application after these tests, so its tracing and threads never reach the next.
  @DirtiesContext
  @DisplayName("in a WebFlux service")
  class ReactiveService {

    /** The port the service listens on. */
    @LocalServerPort private int port;

    /** The handler, which keeps what the logging context held at each step. */
    @Autowired private ReactiveOrdersController controller;

    /** Spring Boot's task executor, which also runs {@code @Async} methods. */
    @Autowired
    @Qualifier("applicationTaskExecutor")
    private AsyncTaskExecutor applicationTaskExecutor;

    /** Sends requests to the reactive service, as its caller. */
    private final HttpClient caller = HttpClient.newHttpClient();

    /** Builds a request to the reactive handler for an order, without trace headers. */
    private HttpRequest.Builder reactiveHandlerRequest(String orderNumber) {
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
    private HttpRequest requestWithCallerTrace(String orderNumber, CallerTrace callerTrace) {
      return reactiveHandlerRequest(orderNumber)
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
    private void assertOrderKeptCallerTrace(String orderNumber, CallerTrace callerTrace) {
      assertThat(List.of(PlaceInReactiveCode.values()))
          .allSatisfy(
              place ->
                  assertThat(controller.loggingContextAt(orderNumber, place))
                      .as("%s in %s", place, orderNumber)
                      .isEqualTo(callerTrace.expectedLoggingContext()));
      var downstreamPath = REACTIVE_WEB_CLIENT_PATH + "/" + orderNumber;
      assertThat(DOWNSTREAM.traceparentHeaderReceivedOn(downstreamPath))
          .hasValueSatisfying(
              traceparent -> assertThat(traceparent).contains(callerTrace.traceId()));
      assertThat(DOWNSTREAM.baggageHeaderReceivedOn(downstreamPath))
          .hasValueSatisfying(baggage -> assertThat(baggage).contains(callerTrace.baggage()));
    }

    @Test
    @DisplayName(
        "a WebFlux handler, a map after a thread change, blocking work on Spring's executor and the"
            + " WebClient call keep the caller's trace and correlation ID")
    void reactiveHandlerKeepsCallerTrace() throws IOException, InterruptedException {
      send(requestWithCallerTrace(ORDER_WITH_CALLER_TRACE, CALLER_TRACE), ORDER_WITH_CALLER_TRACE);

      assertOrderKeptCallerTrace(ORDER_WITH_CALLER_TRACE, CALLER_TRACE);
    }

    @Test
    @DisplayName(
        "concurrent requests each see only their own IDs at every step, and idle threads keep none")
    void concurrentRequestsSeeOnlyTheirOwnIds() throws InterruptedException, ExecutionException {
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
                              requestWithCallerTrace(order.getKey(), order.getValue()),
                              HttpResponse.BodyHandlers.ofString()))
                  .toArray(CompletableFuture[]::new))
          .get();

      callerTraceByOrderNumber.forEach(this::assertOrderKeptCallerTrace);
      assertIdleThreadsHoldNoIds();
    }

    /** Checks that tasks run later on Reactor's schedulers and Spring's executor see no IDs. */
    private void assertIdleThreadsHoldNoIds() {
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

      assertThat(idsSeenOnReactorSchedulers)
          .hasSize(2 * TASKS_PER_IDLE_POOL)
          .allSatisfy(ids -> assertThat(ids).isEmpty());
      assertThat(idsSeenOnSpringExecutor)
          .hasSize(TASKS_PER_IDLE_POOL)
          .allSatisfy(ids -> assertThat(ids).isEmpty());
    }

    /** Returns the trace and correlation IDs the logging context holds on this thread. */
    private List<String> idsInLoggingContext() {
      return Stream.of(LoggingContextConstants.TRACE_ID, LoggingContextConstants.CORRELATION_ID)
          .map(MDC::get)
          .filter(Objects::nonNull)
          .toList();
    }

    @Test
    @DisplayName(
        "a request without a caller's trace gets its own IDs, never the previous request's")
    void nextRequestDoesNotSeePreviousRequestIds() throws IOException, InterruptedException {
      send(requestWithCallerTrace(ORDER_WITH_CALLER_TRACE, CALLER_TRACE), ORDER_WITH_CALLER_TRACE);
      send(reactiveHandlerRequest(ORDER_WITHOUT_CALLER_TRACE).build(), ORDER_WITHOUT_CALLER_TRACE);

      assertThat(List.of(PlaceInReactiveCode.values()))
          .map(place -> controller.loggingContextAt(ORDER_WITHOUT_CALLER_TRACE, place))
          .allSatisfy(this::assertOwnTraceIsTheCorrelationId);
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
  }

  @Nested
  @SpringBootTest(
      classes = OrdersApplication.class,
      properties = {
        "spring.main.web-application-type=none",
        "stratum.observability.enabled=true",
        "management.tracing.sampling.probability=1.0",
        // Spring Boot propagates trace headers only while span export is on. Nothing listens
        // here, so the spans are dropped quietly.
        "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:1/v1/traces",
        "management.otlp.metrics.export.enabled=false"
      })
  // Closes the application after these tests, so its tracing and threads never reach the next.
  @DirtiesContext
  @DisplayName("in blocking code")
  class BlockingCode {

    /** Starts and ends spans. */
    @Autowired private Tracer tracer;

    /** Starts observations, which Spring turns into spans. */
    @Autowired private ObservationRegistry observationRegistry;

    /** Spring Boot's RestClient builder, set up for tracing. */
    @Autowired private RestClient.Builder restClientBuilder;

    /** Spring Boot's RestTemplate builder, set up for tracing. */
    @Autowired private RestTemplateBuilder restTemplateBuilder;

    /** Spring Boot's WebClient builder, set up for tracing. */
    @Autowired private WebClient.Builder webClientBuilder;

    @Test
    @DisplayName(
        "RestClient, RestTemplate and WebClient in blocking code send the same trace headers")
    void blockingClientsSendSameTraceHeaders() {
      Observation.createNotStarted("place-order", observationRegistry)
          .observe(this::callEveryClientWithCorrelationId);

      assertThat(
              List.of(
                  BLOCKING_REST_CLIENT_PATH, BLOCKING_REST_TEMPLATE_PATH, BLOCKING_WEB_CLIENT_PATH))
          .allSatisfy(this::assertTraceHeadersReceived);
    }

    /** Calls the downstream service with each of Spring's clients, with a correlation ID set. */
    private void callEveryClientWithCorrelationId() {
      try (var _ =
          tracer.createBaggageInScope(
              LoggingContextConstants.CORRELATION_ID, CALLER_TRACE.correlationId())) {
        restClientBuilder
            .build()
            .get()
            .uri(DOWNSTREAM.url(BLOCKING_REST_CLIENT_PATH))
            .retrieve()
            .toBodilessEntity();
        restTemplateBuilder
            .build()
            .getForEntity(DOWNSTREAM.url(BLOCKING_REST_TEMPLATE_PATH), Void.class);
        webClientBuilder
            .build()
            .get()
            .uri(DOWNSTREAM.url(BLOCKING_WEB_CLIENT_PATH))
            .retrieve()
            .toBodilessEntity()
            .block();
      }
    }

    /** Checks that a downstream path received a trace header and the correlation ID in baggage. */
    private void assertTraceHeadersReceived(String downstreamPath) {
      assertThat(DOWNSTREAM.traceparentHeaderReceivedOn(downstreamPath))
          .as("traceparent on %s", downstreamPath)
          .isPresent();
      assertThat(DOWNSTREAM.baggageHeaderReceivedOn(downstreamPath))
          .as("baggage on %s", downstreamPath)
          .hasValueSatisfying(baggage -> assertThat(baggage).contains(CALLER_BAGGAGE));
    }
  }
}
