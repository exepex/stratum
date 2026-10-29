package com.exepex.stratum.logging.mdc;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.logging.LoggingConstants;
import io.micrometer.context.ContextExecutorService;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("ServiceMdcFieldsAccessor carries the service's own MDC fields to other threads")
class ServiceMdcFieldsAccessorTest {

  /** A field of the service's own. */
  private static final String CUSTOMER_ID = "customerId";

  /** The customer whose request the tests run. */
  private static final String CUSTOMER = "CUST-7";

  /** The trace ID tracing put in the MDC. */
  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  private final ServiceMdcFieldsAccessor accessor = new ServiceMdcFieldsAccessor();

  /** Clears the logging context, so no test sees fields left by another. */
  @AfterEach
  void clearLoggingContext() {
    MDC.clear();
  }

  @Test
  @DisplayName("it reads only the service's own fields, never the trace or workflow fields")
  void readsOnlyServiceFields() {
    MDC.put(CUSTOMER_ID, CUSTOMER);
    MDC.put(LoggingContextConstants.TRACE_ID, TRACE_ID);
    MDC.put(LoggingConstants.WORKFLOW, "ORDER_CHECKOUT");

    assertThat(accessor.getValue()).containsExactlyEntriesOf(Map.of(CUSTOMER_ID, CUSTOMER));
  }

  @Test
  @DisplayName("a thread with no fields of the service's own carries an empty set of fields")
  void carriesNoFieldsWithoutServiceFields() {
    MDC.put(LoggingContextConstants.SPAN_ID, "00f067aa0ba902b7");

    assertThat(accessor.getValue()).isEmpty();
  }

  @Test
  @DisplayName(
      "setting the carried fields replaces the thread's own, and leaves trace fields alone")
  void replacesServiceFieldsOnly() {
    MDC.put("tenantId", "TENANT-3");
    MDC.put(LoggingContextConstants.CORRELATION_ID, "order-flow-7001");

    accessor.setValue(Map.of(CUSTOMER_ID, CUSTOMER));

    assertThat(MDC.getCopyOfContextMap())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                CUSTOMER_ID, CUSTOMER, LoggingContextConstants.CORRELATION_ID, "order-flow-7001"));
  }

  @Test
  @DisplayName("clearing removes the service's own fields and keeps the trace fields")
  void clearsServiceFieldsOnly() {
    MDC.put(CUSTOMER_ID, CUSTOMER);
    MDC.put(LoggingContextConstants.TRACE_ID, TRACE_ID);

    accessor.setValue();

    assertThat(MDC.getCopyOfContextMap())
        .containsExactlyEntriesOf(Map.of(LoggingContextConstants.TRACE_ID, TRACE_ID));
  }

  @Test
  @DisplayName(
      "in a pool wrapped with ContextExecutorService.wrap(pool), as the guide shows, a task sees"
          + " the submitting thread's fields and the pool thread holds none afterwards")
  void poolTaskSeesFieldsAndThreadIsClearedAfter() throws ExecutionException, InterruptedException {
    try (var singleThreadPool = Executors.newSingleThreadExecutor();
        var contextCarryingPool = ContextExecutorService.wrap(singleThreadPool)) {
      MDC.put(CUSTOMER_ID, CUSTOMER);

      var seenInTask = contextCarryingPool.submit(() -> MDC.get(CUSTOMER_ID)).get();
      MDC.remove(CUSTOMER_ID);
      var seenOnIdleThread = singleThreadPool.submit(() -> MDC.get(CUSTOMER_ID)).get();

      assertThat(seenInTask).isEqualTo(CUSTOMER);
      assertThat(seenOnIdleThread).isNull();
    }
  }
}
