package com.exepex.stratum.observability.it;

import com.exepex.stratum.core.error.LoggingContextConstants;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * A WebFlux service with the observability starter. Its handler moves work to another thread and
 * calls a downstream service with WebClient, keeping what the logging context held at each step.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(ReactiveOrdersApplication.ReactiveOrdersController.class)
public class ReactiveOrdersApplication {

  /** The path of the reactive handler. */
  static final String PLACE_ORDER_PATH = "/orders/reactive";

  /** Where in the handler the logging context was read. */
  enum PlaceInReactiveCode {

    /** The handler method itself, on the server's thread. */
    HANDLER_BODY,

    /** A {@code map} operator after {@code publishOn} moved the work to another thread. */
    MAP_AFTER_THREAD_CHANGE
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

    /** What the logging context held at each place, for the latest request. */
    private final Map<PlaceInReactiveCode, LoggingContextSeen> loggingContextByPlace =
        new ConcurrentHashMap<>();

    /** Spring Boot's WebClient, which sends the trace headers. */
    private final WebClient webClient;

    /** The downstream service's address. */
    private final String downstreamUrl;

    /**
     * Creates the controller.
     *
     * @param webClientBuilder Spring Boot's WebClient builder, set up for tracing
     * @param downstreamUrl the downstream service's address
     */
    ReactiveOrdersController(
        WebClient.Builder webClientBuilder,
        @Value("${orders.downstream-url}") String downstreamUrl) {
      this.webClient = webClientBuilder.build();
      this.downstreamUrl = downstreamUrl;
    }

    /**
     * Places an order: reads the logging context, moves to another thread, reads it again, and
     * calls the downstream service.
     *
     * @return the order number, once the downstream call is done
     */
    @GetMapping(PLACE_ORDER_PATH)
    Mono<String> placeOrder() {
      rememberLoggingContext(PlaceInReactiveCode.HANDLER_BODY);
      return Mono.just("ORD-7001")
          .publishOn(Schedulers.boundedElastic())
          .map(this::rememberLoggingContextAfterThreadChange)
          .flatMap(this::callDownstream);
    }

    /**
     * Returns what the logging context held at a place, for the latest request.
     *
     * @param place the place in the handler
     * @return the IDs seen there
     */
    LoggingContextSeen loggingContextAt(PlaceInReactiveCode place) {
      return loggingContextByPlace.get(place);
    }

    /** Keeps what the logging context holds now, for a place. */
    private void rememberLoggingContext(PlaceInReactiveCode place) {
      loggingContextByPlace.put(
          place,
          new LoggingContextSeen(
              MDC.get(LoggingContextConstants.TRACE_ID),
              MDC.get(LoggingContextConstants.CORRELATION_ID)));
    }

    /** Keeps the logging context seen in {@code map} after the thread change. */
    private String rememberLoggingContextAfterThreadChange(String orderNumber) {
      rememberLoggingContext(PlaceInReactiveCode.MAP_AFTER_THREAD_CHANGE);
      return orderNumber;
    }

    /** Calls the downstream service with WebClient and returns the order number. */
    private Mono<String> callDownstream(String orderNumber) {
      return webClient
          .get()
          .uri(downstreamUrl)
          .retrieve()
          .toBodilessEntity()
          .thenReturn(orderNumber);
    }
  }
}
