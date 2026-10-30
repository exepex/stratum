package com.exepex.stratum.logging.mdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.exepex.stratum.core.error.LoggingContextConstants;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@DisplayName("ReactiveMdcFields puts MDC fields on every line a Mono or Flux writes")
class ReactiveMdcFieldsTest {

  /** A field of the service's own. */
  private static final String CUSTOMER_ID = "customerId";

  /** The customer whose request the tests run. */
  private static final String CUSTOMER = "CUST-7";

  /** The order each chain works on. */
  private static final String ORDER_NUMBER = "ORD-1042";

  /** Another field of the service's own. */
  private static final String TENANT_ID = "tenantId";

  /** Carries the service's own fields, as the logging module does while it is on. */
  private ServiceMdcFieldsPropagation serviceMdcFieldsPropagation;

  /**
   * Turns on Reactor's context propagation and the fields' accessor, as the logging module does.
   */
  @BeforeEach
  void propagateContextToEveryOperator() {
    serviceMdcFieldsPropagation = new ServiceMdcFieldsPropagation();
    Hooks.enableAutomaticContextPropagation();
  }

  /** Turns Reactor's global switch and the accessor off again and clears the logging context. */
  @AfterEach
  void restoreReactorAndLoggingContext() {
    Hooks.disableAutomaticContextPropagation();
    serviceMdcFieldsPropagation.close();
    MDC.clear();
  }

  @Test
  @DisplayName("a map after a thread change sees the fields")
  void monoFieldsReachMapAfterThreadChange() {
    var seenAfterThreadChange =
        ReactiveMdcFields.mono(
                Map.of(CUSTOMER_ID, CUSTOMER),
                Mono.just(ORDER_NUMBER)
                    .publishOn(Schedulers.boundedElastic())
                    .map(orderNumber -> MDC.get(CUSTOMER_ID)))
            .block();

    assertThat(seenAfterThreadChange).isEqualTo(CUSTOMER);
  }

  @Test
  @DisplayName("blocking code that calls block() passes its own MDC fields into the chain")
  void blockingCallerFieldsReachChain() {
    MDC.put(CUSTOMER_ID, CUSTOMER);

    var seenAfterThreadChange =
        Mono.just(ORDER_NUMBER)
            .publishOn(Schedulers.boundedElastic())
            .map(orderNumber -> String.valueOf(MDC.get(CUSTOMER_ID)))
            .block();

    assertThat(seenAfterThreadChange).isEqualTo(CUSTOMER);
  }

  @Test
  @DisplayName("an inner call keeps the outer call's fields and adds its own")
  void innerFluxKeepsOuterFields() {
    var seenInEachElement =
        ReactiveMdcFields.flux(
                Map.of(TENANT_ID, "TENANT-3"),
                ReactiveMdcFields.flux(
                    Map.of(CUSTOMER_ID, CUSTOMER),
                    Flux.just(1, 2)
                        .publishOn(Schedulers.parallel())
                        .map(element -> MDC.get(TENANT_ID) + "/" + MDC.get(CUSTOMER_ID))))
            .collectList()
            .block();

    assertThat(seenInEachElement)
        .isEqualTo(List.of("TENANT-3/" + CUSTOMER, "TENANT-3/" + CUSTOMER));
  }

  @Test
  @DisplayName(
      "a field put with MDC.put is gone after a CompletableFuture, and kept when the chain is"
          + " wrapped, which also carries the subscribing thread's fields")
  void wrappedChainKeepsPutFieldsAfterCompletableFuture()
      throws ExecutionException, InterruptedException {
    MDC.put(CUSTOMER_ID, CUSTOMER);

    var seenWithPutOnly = subscribeAndAwait(customerIdAfterCompletableFuture());
    var seenWhenWrapped =
        subscribeAndAwait(
            ReactiveMdcFields.mono(
                Map.of(TENANT_ID, "TENANT-3"), customerIdAfterCompletableFuture()));

    assertThat(seenWithPutOnly).isEqualTo("null");
    assertThat(seenWhenWrapped).isEqualTo(CUSTOMER);
  }

  @Test
  @DisplayName("the fields survive a service's own accessor for the whole logging context")
  void fieldsSurviveWholeLoggingContextAccessor() {
    ContextRegistry.getInstance().registerThreadLocalAccessor(new Slf4jThreadLocalAccessor());
    try {
      var seenAfterThreadChange =
          ReactiveMdcFields.mono(
                  Map.of(CUSTOMER_ID, CUSTOMER),
                  Mono.just(ORDER_NUMBER)
                      .publishOn(Schedulers.boundedElastic())
                      .map(orderNumber -> String.valueOf(MDC.get(CUSTOMER_ID))))
              .block();

      assertThat(seenAfterThreadChange).isEqualTo(CUSTOMER);
    } finally {
      ContextRegistry.getInstance().removeThreadLocalAccessor(Slf4jThreadLocalAccessor.KEY);
    }
  }

  @Test
  @DisplayName("the trace and workflow fields are refused, so they cannot fall out of step")
  void refusesFieldsManagedElsewhere() {
    var traceField = Map.of(LoggingContextConstants.TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736");

    assertThatIllegalArgumentException()
        .isThrownBy(() -> ReactiveMdcFields.mono(traceField, Mono.empty()))
        .withMessageContaining(LoggingContextConstants.TRACE_ID);
  }

  /** Returns a Mono that reads the customer ID after a CompletableFuture completes. */
  private static Mono<String> customerIdAfterCompletableFuture() {
    return Mono.fromFuture(() -> CompletableFuture.supplyAsync(() -> ORDER_NUMBER))
        .map(orderNumber -> String.valueOf(MDC.get(CUSTOMER_ID)));
  }

  /** Subscribes without {@code block()}, which would pass the caller's fields itself. */
  private static String subscribeAndAwait(Mono<String> work)
      throws ExecutionException, InterruptedException {
    var result = new CompletableFuture<String>();
    work.subscribe(result::complete, result::completeExceptionally);
    return result.get();
  }
}
