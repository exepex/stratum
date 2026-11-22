package com.exepex.stratum.logging.mdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.exepex.stratum.core.logging.LoggingContextConstants;
import com.exepex.stratum.logging.testsupport.MdcFieldsRegistrations;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

  /** Fields that set the customer ID to {@code null}, as a service does with no customer. */
  private static final Map<String, String> WITHOUT_CUSTOMER =
      Collections.singletonMap(CUSTOMER_ID, null);

  /** What a chain reads for a field that is not set. */
  private static final String NOT_SET = String.valueOf((Object) null);

  /** The tenant whose request the tests run. */
  private static final String TENANT = "TENANT-3";

  /**
   * Turns on Reactor's context propagation and the fields' accessor, as the logging module does.
   */
  @BeforeEach
  void propagateContextToEveryOperator() {
    MdcFieldsRegistrations.register();
    Hooks.enableAutomaticContextPropagation();
  }

  /** Turns Reactor's global switch and the accessor off again and clears the logging context. */
  @AfterEach
  void restoreReactorAndLoggingContext() {
    Hooks.disableAutomaticContextPropagation();
    MdcFieldsRegistrations.remove();
    MDC.clear();
  }

  @Nested
  @DisplayName("carrying the fields")
  class CarryingFields {

    @Test
    @DisplayName("a map after a thread change sees the fields")
    void monoFieldsReachMapAfterThreadChange() {
      var seenAfterThreadChange =
          ReactiveMdcFields.mono(Map.of(CUSTOMER_ID, CUSTOMER), customerIdAfterThreadChange())
              .block();

      assertThat(seenAfterThreadChange).isEqualTo(CUSTOMER);
    }

    @Test
    @DisplayName("blocking code that calls block() passes its own MDC fields into the chain")
    void blockingCallerFieldsReachChain() {
      MDC.put(CUSTOMER_ID, CUSTOMER);

      var seenAfterThreadChange = customerIdAfterThreadChange().block();

      assertThat(seenAfterThreadChange).isEqualTo(CUSTOMER);
    }

    @Test
    @DisplayName("an inner call keeps the outer call's fields and adds its own")
    void innerFluxKeepsOuterFields() {
      var seenInEachElement =
          ReactiveMdcFields.flux(
                  Map.of(TENANT_ID, TENANT),
                  ReactiveMdcFields.flux(
                      Map.of(CUSTOMER_ID, CUSTOMER),
                      Flux.just(1, 2)
                          .publishOn(Schedulers.parallel())
                          .map(ReactiveMdcFieldsTest.this::tenantAndCustomerIds)))
              .collectList()
              .block();

      assertThat(seenInEachElement)
          .isEqualTo(List.of(TENANT + "/" + CUSTOMER, TENANT + "/" + CUSTOMER));
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
                  Map.of(TENANT_ID, TENANT), customerIdAfterCompletableFuture()));

      assertThat(seenWithPutOnly).isEqualTo(NOT_SET);
      assertThat(seenWhenWrapped).isEqualTo(CUSTOMER);
    }

    @Test
    @DisplayName(
        "the added and the subscribing thread's fields survive a service's own accessor for the"
            + " whole logging context")
    void fieldsSurviveWholeLoggingContextAccessor()
        throws ExecutionException, InterruptedException {
      MdcFieldsRegistrations.registerWholeLoggingContextAccessor();
      MDC.put(CUSTOMER_ID, CUSTOMER);

      var seenAfterThreadChange =
          subscribeAndAwait(
              ReactiveMdcFields.mono(
                  Map.of(TENANT_ID, TENANT),
                  Mono.just(ORDER_NUMBER)
                      .publishOn(Schedulers.boundedElastic())
                      .map(ReactiveMdcFieldsTest.this::tenantAndCustomerIds)));

      assertThat(seenAfterThreadChange).isEqualTo(TENANT + "/" + CUSTOMER);
    }
  }

  @Nested
  @DisplayName("choosing the fields")
  class ChoosingFields {

    @Test
    @DisplayName("the trace and workflow fields are refused, so they cannot fall out of step")
    void refusesFieldsManagedElsewhere() {
      var traceField = Map.of(LoggingContextConstants.TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736");

      assertThatIllegalArgumentException()
          .isThrownBy(() -> ReactiveMdcFields.mono(traceField, Mono.empty()))
          .withMessageContaining(LoggingContextConstants.TRACE_ID);
    }

    @Test
    @DisplayName("a null value leaves the field off, and the other fields are kept")
    void nullValueLeavesFieldOff() {
      var fields = new HashMap<String, String>();
      fields.put(CUSTOMER_ID, null);
      fields.put(TENANT_ID, TENANT);

      var seenAfterThreadChange =
          ReactiveMdcFields.flux(
                  fields,
                  Flux.just(ORDER_NUMBER)
                      .publishOn(Schedulers.parallel())
                      .map(ReactiveMdcFieldsTest.this::tenantAndCustomerIds))
              .blockLast();

      assertThat(seenAfterThreadChange).isEqualTo(TENANT + "/" + NOT_SET);
    }

    @Test
    @DisplayName(
        "a null value hides the value of an enclosing call, also with a service's own accessor for"
            + " the whole logging context")
    void nullValueHidesEnclosingCallValue() {
      var hideCustomer =
          ReactiveMdcFields.mono(
              Map.of(CUSTOMER_ID, CUSTOMER),
              ReactiveMdcFields.mono(WITHOUT_CUSTOMER, customerIdAfterThreadChange()));

      var seenWithoutWholeContextAccessor = hideCustomer.block();
      MdcFieldsRegistrations.registerWholeLoggingContextAccessor();
      var seenWithWholeContextAccessor = hideCustomer.block();

      assertThat(seenWithoutWholeContextAccessor).isEqualTo(NOT_SET);
      assertThat(seenWithWholeContextAccessor).isEqualTo(NOT_SET);
    }

    @Test
    @DisplayName(
        "a null value hides the subscribing thread's value, also after a CompletableFuture and with"
            + " a service's own accessor for the whole logging context")
    void nullValueHidesSubscribingThreadValue() throws ExecutionException, InterruptedException {
      MdcFieldsRegistrations.registerWholeLoggingContextAccessor();
      MDC.put(CUSTOMER_ID, CUSTOMER);

      var seenAfterThreadChange =
          subscribeAndAwait(
              ReactiveMdcFields.mono(WITHOUT_CUSTOMER, customerIdAfterThreadChange()));
      var seenAfterCompletableFuture =
          subscribeAndAwait(
              ReactiveMdcFields.mono(WITHOUT_CUSTOMER, customerIdAfterCompletableFuture()));

      assertThat(seenAfterThreadChange).isEqualTo(NOT_SET);
      assertThat(seenAfterCompletableFuture).isEqualTo(NOT_SET);
    }
  }

  /** Returns a Mono that reads the customer ID after a CompletableFuture completes. */
  private Mono<String> customerIdAfterCompletableFuture() {
    return Mono.fromFuture(() -> CompletableFuture.supplyAsync(() -> ORDER_NUMBER))
        .map(orderNumber -> String.valueOf(MDC.get(CUSTOMER_ID)));
  }

  /** Returns a Mono that reads the customer ID after a thread change. */
  private Mono<String> customerIdAfterThreadChange() {
    return Mono.just(ORDER_NUMBER)
        .publishOn(Schedulers.boundedElastic())
        .map(orderNumber -> String.valueOf(MDC.get(CUSTOMER_ID)));
  }

  /** Returns the tenant and customer IDs the chain sees, as {@code tenant/customer}. */
  private String tenantAndCustomerIds(Object ignoredElement) {
    return MDC.get(TENANT_ID) + "/" + MDC.get(CUSTOMER_ID);
  }

  /** Subscribes without {@code block()}, which would pass the caller's fields itself. */
  private String subscribeAndAwait(Mono<String> work)
      throws ExecutionException, InterruptedException {
    var result = new CompletableFuture<String>();
    work.subscribe(result::complete, result::completeExceptionally);
    return result.get();
  }
}
