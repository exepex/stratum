package com.exepex.stratum.observability.it;

import com.exepex.stratum.core.error.LoggingContextConstants;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * A WebFlux service with the observability starter. Its handler moves work to another thread, runs
 * blocking work on Spring Boot's task executor and calls a downstream service with WebClient,
 * keeping what the logging context held at each step for each order.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(ReactiveOrdersApplication.ReactiveOrdersController.class)
public class ReactiveOrdersApplication {

  /** The path of the reactive handler. */
  static final String PLACE_ORDER_PATH = "/orders/reactive";

  /** The query parameter that names the order, so that concurrent requests can be told apart. */
  static final String ORDER_NUMBER_PARAMETER = "orderNumber";

  /** Where in the handler the logging context was read. */
  enum PlaceInReactiveCode {

    /** The handler method itself, on the server's thread. */
    HANDLER_BODY,

    /** A {@code map} operator after {@code publishOn} moved the work to another thread. */
    MAP_AFTER_THREAD_CHANGE,

    /** Blocking work the handler runs on Spring Boot's task executor. */
    SPRING_EXECUTOR_TASK
  }

  /**
   * The trace and correlation IDs the logging context held, as a log line written there would carry
   * them.
   *
   * @param traceId the trace ID, or null if there was none
   * @param correlationId the correlation ID, or null if there was none
   */
  record LoggingContextSeen(@Nullable String traceId, @Nullable String correlationId) {}

  /** The reactive handler that places an order and calls the downstream service. */
  @RestController
  static class ReactiveOrdersController {

    /** What the logging context held at each place, for each order number. */
    private final Map<String, Map<PlaceInReactiveCode, LoggingContextSeen>>
        loggingContextByOrderNumber = new ConcurrentHashMap<>();

    /** Spring Boot's WebClient, which sends the trace headers. */
    private final WebClient webClient;

    /** The downstream service's address; each order is sent to its own path below it. */
    private final String downstreamUrl;

    /** Spring Boot's task executor, which carries the logging context into its tasks. */
    private final AsyncTaskExecutor applicationTaskExecutor;

    /**
     * Creates the controller.
     *
     * @param webClientBuilder Spring Boot's WebClient builder, set up for tracing
     * @param downstreamUrl the downstream service's address
     * @param applicationTaskExecutor Spring Boot's task executor
     */
    ReactiveOrdersController(
        WebClient.Builder webClientBuilder,
        @Value("${orders.downstream-url}") String downstreamUrl,
        @Qualifier("applicationTaskExecutor") AsyncTaskExecutor applicationTaskExecutor) {
      this.webClient = webClientBuilder.build();
      this.downstreamUrl = downstreamUrl;
      this.applicationTaskExecutor = applicationTaskExecutor;
    }

    /**
     * Places an order: reads the logging context, moves to another thread and reads it again, runs
     * blocking work on Spring Boot's task executor, and calls the downstream service.
     *
     * @param orderNumber the order, which names the request in what this controller keeps
     * @return the order number, once the downstream call is done
     */
    @GetMapping(PLACE_ORDER_PATH)
    Mono<String> placeOrder(@RequestParam(ORDER_NUMBER_PARAMETER) String orderNumber) {
      rememberLoggingContext(orderNumber, PlaceInReactiveCode.HANDLER_BODY);
      return Mono.just(orderNumber)
          .publishOn(Schedulers.boundedElastic())
          .map(this::rememberLoggingContextAfterThreadChange)
          .flatMap(this::runBlockingWorkOnSpringExecutor)
          .flatMap(this::callDownstream);
    }

    /**
     * Returns what the logging context held at a place, for an order.
     *
     * @param orderNumber the order
     * @param place the place in the handler
     * @return the IDs seen there, or null if the order's request never reached that place
     */
    @Nullable LoggingContextSeen loggingContextAt(String orderNumber, PlaceInReactiveCode place) {
      return loggingContextByOrderNumber.getOrDefault(orderNumber, Map.of()).get(place);
    }

    /** Keeps what the logging context holds now, for a place in an order's request. */
    private void rememberLoggingContext(String orderNumber, PlaceInReactiveCode place) {
      loggingContextByOrderNumber
          .computeIfAbsent(orderNumber, _ -> new ConcurrentHashMap<>())
          .put(
              place,
              new LoggingContextSeen(
                  MDC.get(LoggingContextConstants.TRACE_ID),
                  MDC.get(LoggingContextConstants.CORRELATION_ID)));
    }

    /** Keeps the logging context seen in {@code map} after the thread change. */
    private String rememberLoggingContextAfterThreadChange(String orderNumber) {
      rememberLoggingContext(orderNumber, PlaceInReactiveCode.MAP_AFTER_THREAD_CHANGE);
      return orderNumber;
    }

    /**
     * Runs blocking work on Spring Boot's task executor, as reactive code that mixes in a blocking
     * call would.
     */
    private Mono<String> runBlockingWorkOnSpringExecutor(String orderNumber) {
      return Mono.fromFuture(
          () ->
              applicationTaskExecutor.submitCompletable(
                  () -> rememberLoggingContextInSpringExecutorTask(orderNumber)));
    }

    /** Keeps the logging context seen in the task on Spring Boot's executor. */
    private String rememberLoggingContextInSpringExecutorTask(String orderNumber) {
      rememberLoggingContext(orderNumber, PlaceInReactiveCode.SPRING_EXECUTOR_TASK);
      return orderNumber;
    }

    /** Calls the downstream service on the order's own path with WebClient. */
    private Mono<String> callDownstream(String orderNumber) {
      return webClient
          .get()
          .uri(downstreamUrl + "/" + orderNumber)
          .retrieve()
          .toBodilessEntity()
          .thenReturn(orderNumber);
    }
  }
}
