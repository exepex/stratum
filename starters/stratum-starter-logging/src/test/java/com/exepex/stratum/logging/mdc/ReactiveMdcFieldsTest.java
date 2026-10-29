package com.exepex.stratum.logging.mdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.exepex.stratum.core.error.LoggingContextConstants;
import java.util.List;
import java.util.Map;
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

  /** Another field of the service's own. */
  private static final String TENANT_ID = "tenantId";

  /** Turns on Reactor's context propagation, as the logging starter does by default. */
  @BeforeEach
  void propagateContextToEveryOperator() {
    Hooks.enableAutomaticContextPropagation();
  }

  /** Turns Reactor's global switch off again and clears the logging context. */
  @AfterEach
  void restoreReactorAndLoggingContext() {
    Hooks.disableAutomaticContextPropagation();
    MDC.clear();
  }

  @Test
  @DisplayName("a map after a thread change sees the fields")
  void monoFieldsReachMapAfterThreadChange() {
    var seenAfterThreadChange =
        ReactiveMdcFields.mono(
                Map.of(CUSTOMER_ID, CUSTOMER),
                Mono.just("ORD-1042")
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
        Mono.just("ORD-1042")
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
  @DisplayName("the trace and workflow fields are refused, so they cannot fall out of step")
  void refusesFieldsManagedElsewhere() {
    var traceField = Map.of(LoggingContextConstants.TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736");

    assertThatIllegalArgumentException()
        .isThrownBy(() -> ReactiveMdcFields.mono(traceField, Mono.empty()))
        .withMessageContaining(LoggingContextConstants.TRACE_ID);
  }
}
