package com.exepex.stratum.logging.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.logging.mdc.ReactiveMdcFields;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.test.annotation.DirtiesContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@SpringBootTest(
    classes = {OrdersApplication.class, ServiceMdcFieldsIntegrationTest.OrderFulfilment.class},
    properties = {"spring.application.name=orders", "stratum.logging.enabled=true"})
// Closing the context resets logging, so later tests in this JVM start from Spring Boot's own.
@DirtiesContext
@DisplayName("An MDC field the service puts itself follows the request to every thread it uses")
class ServiceMdcFieldsIntegrationTest {

  /** The MDC field each request puts, as a service would. */
  private static final String CUSTOMER_ID = "customerId";

  /** How many requests run at once. */
  private static final int CONCURRENT_REQUESTS = 24;

  /** How many threads serve the requests, so that each thread serves several in turn. */
  private static final int REQUEST_THREADS = 6;

  /** How many tasks run on each pool afterwards, to look for values left behind. */
  private static final int TASKS_PER_IDLE_POOL = 50;

  @Autowired private OrderFulfilment orderFulfilment;

  @Autowired private ApplicationEventPublisher eventPublisher;

  @Autowired
  @Qualifier("applicationTaskExecutor")
  private AsyncTaskExecutor applicationTaskExecutor;

  /**
   * The customer ID a request saw at each place it reached.
   *
   * @param ownCustomerId the ID the request put in the MDC
   * @param asyncMethod what an {@code @Async} method saw
   * @param springExecutorTask what a task on Spring Boot's executor saw
   * @param plainEventListener what an {@code @EventListener} on the publishing thread saw
   * @param reactiveMapAfterThreadChange what a {@code map} after {@code publishOn} saw
   */
  private record CustomerIdSeen(
      String ownCustomerId,
      @Nullable String asyncMethod,
      @Nullable String springExecutorTask,
      @Nullable String plainEventListener,
      @Nullable String reactiveMapAfterThreadChange) {}

  @Test
  @DisplayName(
      "concurrent requests each see only their own field in @Async methods, Spring's executor,"
          + " event listeners and Reactor, and idle threads keep none")
  void concurrentRequestsSeeOnlyTheirOwnField() {
    List<CustomerIdSeen> customerIdsSeen;
    try (var requestThreads = Executors.newFixedThreadPool(REQUEST_THREADS)) {
      var requests =
          IntStream.range(0, CONCURRENT_REQUESTS)
              .mapToObj(
                  requestIndex ->
                      CompletableFuture.supplyAsync(
                          () -> placeOrderAsCustomer("CUST-" + requestIndex), requestThreads))
              .toList();
      customerIdsSeen = requests.stream().map(CompletableFuture::join).toList();
    }

    assertThat(customerIdsSeen)
        .hasSize(CONCURRENT_REQUESTS)
        .allSatisfy(this::assertOwnCustomerIdEverywhere);
    await()
        .untilAsserted(
            () ->
                assertThat(orderFulfilment.asyncListenerCustomerIdsByOrder())
                    .hasSize(CONCURRENT_REQUESTS));
    orderFulfilment
        .asyncListenerCustomerIdsByOrder()
        .forEach(
            (orderNumber, customerIdSeen) ->
                assertThat(orderNumber).isEqualTo("ORD-" + customerIdSeen));
    assertIdleThreadsHoldNoCustomerId();
  }

  /**
   * Places an order as one request would, with its customer ID in the MDC, and keeps what each
   * place saw.
   */
  private CustomerIdSeen placeOrderAsCustomer(String customerId) {
    try (var _ = MDC.putCloseable(CUSTOMER_ID, customerId)) {
      var asyncMethod = orderFulfilment.customerIdSeenInAsyncMethod().join();
      var springExecutorTask =
          applicationTaskExecutor.submitCompletable(() -> MDC.get(CUSTOMER_ID)).join();
      eventPublisher.publishEvent(new OrderPlaced("ORD-" + customerId));
      var plainEventListener = orderFulfilment.plainListenerCustomerIdFor("ORD-" + customerId);
      var reactiveMapAfterThreadChange =
          ReactiveMdcFields.mono(
                  Map.of(CUSTOMER_ID, customerId),
                  Mono.just(customerId)
                      .publishOn(Schedulers.boundedElastic())
                      .map(_ -> MDC.get(CUSTOMER_ID)))
              .block();
      return new CustomerIdSeen(
          customerId,
          asyncMethod,
          springExecutorTask,
          plainEventListener,
          reactiveMapAfterThreadChange);
    }
  }

  /** Checks that every place a request reached saw that request's own customer ID. */
  private void assertOwnCustomerIdEverywhere(CustomerIdSeen seen) {
    assertThat(
            List.of(
                String.valueOf(seen.asyncMethod()),
                String.valueOf(seen.springExecutorTask()),
                String.valueOf(seen.plainEventListener()),
                String.valueOf(seen.reactiveMapAfterThreadChange())))
        .as("customer IDs seen by %s", seen.ownCustomerId())
        .containsOnly(seen.ownCustomerId());
  }

  /**
   * Checks that tasks run later on Spring's executor and Reactor's schedulers see no customer ID.
   */
  private void assertIdleThreadsHoldNoCustomerId() {
    var seenOnSpringExecutor =
        IntStream.range(0, TASKS_PER_IDLE_POOL)
            .mapToObj(_ -> applicationTaskExecutor.submitCompletable(() -> MDC.get(CUSTOMER_ID)))
            .toList()
            .stream()
            .map(CompletableFuture::join)
            .toList();
    var seenOnReactorSchedulers =
        Flux.range(0, TASKS_PER_IDLE_POOL)
            .flatMap(
                _ ->
                    Mono.fromCallable(() -> String.valueOf(MDC.get(CUSTOMER_ID)))
                        .subscribeOn(Schedulers.boundedElastic()))
            .collectList()
            .block();

    assertThat(seenOnSpringExecutor).hasSize(TASKS_PER_IDLE_POOL).containsOnlyNulls();
    assertThat(seenOnReactorSchedulers).hasSize(TASKS_PER_IDLE_POOL).containsOnly("null");
  }

  /**
   * The event a request publishes when it places an order.
   *
   * @param orderNumber the order's number
   */
  record OrderPlaced(String orderNumber) {}

  /** A service bean with an {@code @Async} method and event listeners that keep what they see. */
  @EnableAsync
  static class OrderFulfilment {

    /** What the listener on the publishing thread saw, by order number. */
    private final Map<String, String> customerIdSeenByPlainEventListener =
        new ConcurrentHashMap<>();

    /** What the {@code @Async} listener saw, by order number. */
    private final Map<String, String> customerIdSeenByAsyncEventListener =
        new ConcurrentHashMap<>();

    /**
     * Returns what the listener on the publishing thread saw for an order. Public, so that it is
     * called on the bean and not on the proxy that {@code @Async} adds.
     *
     * @param orderNumber the order's number
     * @return the customer ID seen, or {@code null} if the listener never ran for the order
     */
    public @Nullable String plainListenerCustomerIdFor(String orderNumber) {
      return customerIdSeenByPlainEventListener.get(orderNumber);
    }

    /**
     * Returns what the {@code @Async} listener saw, by order number.
     *
     * @return the customer IDs seen so far
     */
    public Map<String, String> asyncListenerCustomerIdsByOrder() {
      return Map.copyOf(customerIdSeenByAsyncEventListener);
    }

    /**
     * Returns the customer ID the MDC holds on the thread Spring runs this method on.
     *
     * @return the ID, or a completed {@code null} if there was none
     */
    @Async
    public CompletableFuture<@Nullable String> customerIdSeenInAsyncMethod() {
      return CompletableFuture.completedFuture(MDC.get(CUSTOMER_ID));
    }

    /**
     * Keeps the customer ID seen on the publishing thread.
     *
     * @param orderPlaced the event
     */
    @EventListener
    public void recordCustomerIdOnPublishingThread(OrderPlaced orderPlaced) {
      customerIdSeenByPlainEventListener.put(
          orderPlaced.orderNumber(), String.valueOf(MDC.get(CUSTOMER_ID)));
    }

    /**
     * Keeps the customer ID seen on the thread Spring runs this listener on.
     *
     * @param orderPlaced the event
     */
    @Async
    @EventListener
    public void recordCustomerIdOnAsyncThread(OrderPlaced orderPlaced) {
      customerIdSeenByAsyncEventListener.put(
          orderPlaced.orderNumber(), String.valueOf(MDC.get(CUSTOMER_ID)));
    }
  }
}
