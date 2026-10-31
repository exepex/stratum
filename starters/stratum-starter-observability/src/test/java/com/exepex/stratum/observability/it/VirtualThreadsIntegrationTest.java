package com.exepex.stratum.observability.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.LoggingContextConstants;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
    classes = {OrdersApplication.class, VirtualThreadsIntegrationTest.ServicePools.class},
    properties = {
      "spring.main.web-application-type=none",
      "stratum.observability.enabled=true",
      "spring.threads.virtual.enabled=true",
      "management.tracing.sampling.probability=1.0",
      // Spring Boot propagates trace headers only while span export is on. Nothing listens
      // here, so the spans are dropped quietly.
      "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:1/v1/traces",
      "management.otlp.metrics.export.enabled=false"
    })
// Closes the application after this class, so its tracing and threads never reach the next one.
@DirtiesContext
@DisplayName("A service that turns on virtual threads keeps its trace on every virtual thread")
class VirtualThreadsIntegrationTest {

  /** The span of the request whose trace the tasks must keep. */
  private static final String PLACE_ORDER_SPAN = "place-order";

  /** The caller's correlation ID, set in baggage, which differs from the trace ID. */
  private static final String ORDER_CORRELATION_ID = "order-42-c7d1";

  /** How long a task may take before the test fails. */
  private static final long TASK_TIMEOUT_SECONDS = 10;

  @Autowired private Tracer tracer;

  @Autowired private ObservationRegistry observationRegistry;

  @Autowired private ExecutorService invoiceRenderingPool;

  @Autowired
  @Qualifier("applicationTaskExecutor")
  private AsyncTaskExecutor applicationTaskExecutor;

  /**
   * What a task saw on the thread it ran on.
   *
   * @param traceId the trace ID in the MDC, or {@code null} if there was none
   * @param correlationId the correlation ID in the MDC, or {@code null} if there was none
   * @param virtualThread whether the thread was virtual
   */
  record SeenInTask(
      @Nullable String traceId, @Nullable String correlationId, boolean virtualThread) {

    /**
     * Returns what the current thread holds.
     *
     * @return the IDs and whether the thread is virtual
     */
    static SeenInTask onCurrentThread() {
      return new SeenInTask(
          MDC.get(LoggingContextConstants.TRACE_ID),
          MDC.get(LoggingContextConstants.CORRELATION_ID),
          Thread.currentThread().isVirtual());
    }
  }

  @Test
  @DisplayName(
      "a task on Spring Boot's task executor runs on a virtual thread with the caller's trace and"
          + " correlation ID")
  void springTaskExecutorKeepsTrace() {
    assertTaskKeepsCallersTrace(applicationTaskExecutor);
  }

  @Test
  @DisplayName(
      "a task on the service's own virtual-thread ExecutorService bean keeps the caller's trace and"
          + " correlation ID")
  void serviceVirtualThreadPoolKeepsTrace() {
    assertTaskKeepsCallersTrace(invoiceRenderingPool);
  }

  /** Runs a task on the executor inside a request's observation and correlation ID. */
  private void assertTaskKeepsCallersTrace(Executor executor) {
    Observation.createNotStarted(PLACE_ORDER_SPAN, observationRegistry)
        .observe(() -> assertTaskSeesCurrentTraceAndCorrelationId(executor));
  }

  /** Sets the correlation ID in baggage and checks that a task on the executor sees both IDs. */
  private void assertTaskSeesCurrentTraceAndCorrelationId(Executor executor) {
    try (var _ =
        tracer.createBaggageInScope(LoggingContextConstants.CORRELATION_ID, ORDER_CORRELATION_ID)) {
      assertThat(
              CompletableFuture.supplyAsync(SeenInTask::onCurrentThread, executor)
                  .orTimeout(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                  .join())
          .isEqualTo(
              new SeenInTask(tracer.currentSpan().context().traceId(), ORDER_CORRELATION_ID, true));
    }
  }

  /** The pools the service declares itself. */
  @Configuration(proxyBeanMethods = false)
  static class ServicePools {

    /**
     * Returns a virtual-thread executor the service declares as an {@code ExecutorService}.
     *
     * @return the executor
     */
    @Bean
    ExecutorService invoiceRenderingPool() {
      return Executors.newVirtualThreadPerTaskExecutor();
    }
  }
}
