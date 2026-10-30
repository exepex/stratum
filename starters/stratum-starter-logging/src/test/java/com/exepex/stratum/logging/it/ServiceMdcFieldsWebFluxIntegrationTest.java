package com.exepex.stratum.logging.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.logging.mdc.ReactiveMdcFields;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@SpringBootTest(
    classes = {
      OrdersApplication.class,
      ServiceMdcFieldsWebFluxIntegrationTest.ReactiveOrdersController.class
    },
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.application.name=orders", "stratum.logging.enabled=true"})
// Closing the context resets logging, so later tests in this JVM start from Spring Boot's own.
@DirtiesContext
@DisplayName(
    "An MDC field a WebFlux handler puts follows its request through Reactor, and no other")
class ServiceMdcFieldsWebFluxIntegrationTest {

  /** The MDC field a handler puts as soon as it knows the customer. */
  private static final String CUSTOMER_ID = "customerId";

  /** The MDC field known only part-way down the chain, added with {@link ReactiveMdcFields}. */
  private static final String ORDER_ID = "orderId";

  /** How many requests run at once. Every other one puts no field, to catch values left behind. */
  private static final int CONCURRENT_REQUESTS = 24;

  /** How long the handler waits, which moves its work to one of Reactor's timer threads. */
  private static final Duration DELAY_ON_TIMER_THREAD = Duration.ofMillis(5);

  /** How many tasks run on each Reactor scheduler afterwards, to look for values left behind. */
  private static final int TASKS_PER_IDLE_SCHEDULER = 50;

  @LocalServerPort private int serverPort;

  @Autowired private ReactiveOrdersController reactiveOrdersController;

  /** Sends requests to the service, as its callers. */
  private final HttpClient caller = HttpClient.newHttpClient();

  @Test
  @DisplayName(
      "concurrent requests each see only their own fields in the handler, after publishOn,"
          + " delayElement and a CompletableFuture, and Reactor's threads keep none afterwards")
  void concurrentRequestsSeeOnlyTheirOwnFields() {
    var requests =
        IntStream.range(0, CONCURRENT_REQUESTS)
            .mapToObj(requestIndex -> placeOrder("CUST-" + requestIndex, requestIndex % 2 == 0))
            .toList();
    CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).join();

    IntStream.range(0, CONCURRENT_REQUESTS)
        .forEach(
            requestIndex ->
                assertOwnFieldsEverywhere("CUST-" + requestIndex, requestIndex % 2 == 0));
    assertThat(serviceFieldsOnIdleReactorThreads())
        .hasSize(TASKS_PER_IDLE_SCHEDULER * 2)
        .containsOnly("null/null");
  }

  /** Sends one order request, with or without the handler putting the customer ID. */
  private CompletableFuture<HttpResponse<String>> placeOrder(
      String customerId, boolean handlerPutsCustomerId) {
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:%d/customers/%s/orders?putCustomerId=%s"
                        .formatted(serverPort, customerId, handlerPutsCustomerId)))
            .build();
    return caller
        .sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(this::requireSuccess);
  }

  /** Checks what a request saw at every place, given whether its handler put the customer ID. */
  private void assertOwnFieldsEverywhere(String customerId, boolean handlerPutsCustomerId) {
    var expectedCustomerId = handlerPutsCustomerId ? customerId : "null";
    var expectedOrderId = "ORD-" + customerId;
    assertThat(reactiveOrdersController.fieldsSeenBy(customerId))
        .as("fields seen by the request of %s", customerId)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                PlaceInReactiveCode.HANDLER_BODY, expectedCustomerId + "/null",
                PlaceInReactiveCode.MAP_AFTER_PUBLISH_ON, expectedCustomerId + "/null",
                PlaceInReactiveCode.MAP_AFTER_DELAY, expectedCustomerId + "/null",
                PlaceInReactiveCode.MAP_WITH_ORDER_ID, expectedCustomerId + "/" + expectedOrderId));
  }

  /** Returns what tasks run later on Reactor's two shared schedulers see. */
  private List<String> serviceFieldsOnIdleReactorThreads() {
    return Flux.range(0, TASKS_PER_IDLE_SCHEDULER * 2)
        .flatMap(
            taskIndex ->
                Mono.fromCallable(reactiveOrdersController::serviceFieldsOnThisThread)
                    .subscribeOn(
                        taskIndex % 2 == 0 ? Schedulers.parallel() : Schedulers.boundedElastic()))
        .collectList()
        .block();
  }

  /** Fails unless the service answered the request successfully. */
  private HttpResponse<String> requireSuccess(HttpResponse<String> response) {
    if (response.statusCode() != HttpURLConnection.HTTP_OK) {
      throw new UncheckedIOException(new IOException("HTTP " + response.statusCode()));
    }
    return response;
  }

  /** Where in the handler the MDC was read. */
  enum PlaceInReactiveCode {

    /** The handler method itself, right after it put the customer ID. */
    HANDLER_BODY,

    /** A {@code map} operator after {@code publishOn} moved the work to another thread. */
    MAP_AFTER_PUBLISH_ON,

    /** A {@code map} operator after {@code delayElement} moved the work to a timer thread. */
    MAP_AFTER_DELAY,

    /**
     * A {@code map} inside {@link ReactiveMdcFields#mono}, which adds the order ID, after a {@code
     * CompletableFuture} completed on a thread Reactor does not manage.
     */
    MAP_WITH_ORDER_ID
  }

  /** A WebFlux handler that puts the customer ID, moves between threads and reads the MDC. */
  @RestController
  static class ReactiveOrdersController {

    /** The customer ID and order ID each place saw, written as {@code customerId/orderId}. */
    private final Map<String, Map<PlaceInReactiveCode, String>> fieldsSeenByCustomer =
        new ConcurrentHashMap<>();

    /**
     * Places an order. The handler puts the customer ID with {@code MDC.put}, as a service would,
     * unless asked not to; the order ID is added part-way with {@link ReactiveMdcFields}.
     *
     * @param customerId the customer, which names the request in what this controller keeps
     * @param putCustomerId whether the handler puts the customer ID in the MDC
     * @return the order ID, once every step is done
     */
    @GetMapping("/customers/{customerId}/orders")
    Mono<String> placeOrder(@PathVariable String customerId, @RequestParam boolean putCustomerId) {
      if (putCustomerId) {
        MDC.put(CUSTOMER_ID, customerId);
      }
      rememberFields(customerId, PlaceInReactiveCode.HANDLER_BODY);
      return Mono.just(customerId)
          .publishOn(Schedulers.boundedElastic())
          .map(_ -> rememberFields(customerId, PlaceInReactiveCode.MAP_AFTER_PUBLISH_ON))
          .delayElement(DELAY_ON_TIMER_THREAD)
          .map(_ -> rememberFields(customerId, PlaceInReactiveCode.MAP_AFTER_DELAY))
          .flatMap(
              _ ->
                  ReactiveMdcFields.mono(
                      Map.of(ORDER_ID, "ORD-" + customerId),
                      Mono.fromFuture(() -> CompletableFuture.supplyAsync(() -> customerId))
                          .map(
                              _ ->
                                  rememberFields(
                                      customerId, PlaceInReactiveCode.MAP_WITH_ORDER_ID))))
          .thenReturn("ORD-" + customerId);
    }

    /**
     * Returns the fields each place saw for a customer's request.
     *
     * @param customerId the customer
     * @return the fields by place, written as {@code customerId/orderId}
     */
    Map<PlaceInReactiveCode, String> fieldsSeenBy(String customerId) {
      return Map.copyOf(fieldsSeenByCustomer.getOrDefault(customerId, Map.of()));
    }

    /**
     * Returns the customer ID and order ID on this thread, written as {@code customerId/orderId}.
     *
     * @return the fields, with {@code null} for a field that is not set
     */
    String serviceFieldsOnThisThread() {
      return MDC.get(CUSTOMER_ID) + "/" + MDC.get(ORDER_ID);
    }

    /** Keeps the fields seen now at a place in a customer's request. */
    private @Nullable String rememberFields(String customerId, PlaceInReactiveCode place) {
      fieldsSeenByCustomer
          .computeIfAbsent(customerId, _ -> new ConcurrentHashMap<>())
          .put(place, serviceFieldsOnThisThread());
      return customerId;
    }
  }
}
