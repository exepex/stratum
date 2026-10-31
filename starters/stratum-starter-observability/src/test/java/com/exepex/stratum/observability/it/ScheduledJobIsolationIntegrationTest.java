package com.exepex.stratum.observability.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.LoggingContextConstants;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
    classes = {OrdersApplication.class, ScheduledJobIsolationIntegrationTest.CleanupJob.class},
    properties = {
      "spring.main.web-application-type=none",
      "stratum.observability.enabled=true",
      "management.tracing.sampling.probability=1.0",
      "management.tracing.export.enabled=false",
      "management.otlp.metrics.export.enabled=false"
    })
// Closing the application stops the job, which would otherwise run until the build ends.
@DirtiesContext
@DisplayName("A @Scheduled job never takes the trace, correlation ID or MDC fields of a request")
class ScheduledJobIsolationIntegrationTest {

  /** The MDC field a request puts, as a service would. */
  private static final String CUSTOMER_ID = "customerId";

  /** The correlation ID of the request running while the job runs. */
  private static final String ORDER_CORRELATION_ID = "order-42-c7d1";

  /** What the job records for an ID it does not see. */
  private static final String NOT_SET = "none";

  /** How long to wait for a run of the job. */
  private static final long JOB_RUN_TIMEOUT_SECONDS = 10;

  /** Reads the current trace. */
  @Autowired private Tracer tracer;

  /** The job, which runs every 50 milliseconds. */
  @Autowired private CleanupJob job;

  /** Clears the logging context, so no test sees fields left by another. */
  @AfterEach
  void clearLoggingContext() {
    MDC.clear();
  }

  @Test
  @DisplayName("a run during a request has its own trace and correlation ID, and no request field")
  void runDuringRequestIsNotLinked() throws InterruptedException {
    var requestSpan = tracer.nextSpan().name("place-order").start();
    Map<String, String> idsInRun;
    try (var _ = tracer.withSpan(requestSpan);
        var _ =
            tracer.createBaggageInScope(
                LoggingContextConstants.CORRELATION_ID, ORDER_CORRELATION_ID);
        var _ = MDC.putCloseable(CUSTOMER_ID, "CUST-7")) {
      job.idsSeenByRuns.clear();
      idsInRun = job.idsSeenByRuns.poll(JOB_RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } finally {
      requestSpan.end();
    }

    assertThat(idsInRun).isNotNull();
    assertThat(idsInRun.get(LoggingContextConstants.TRACE_ID))
        .isNotEqualTo(requestSpan.context().traceId());
    assertThat(idsInRun.get(LoggingContextConstants.CORRELATION_ID))
        .isNotEqualTo(ORDER_CORRELATION_ID)
        .isEqualTo(idsInRun.get(LoggingContextConstants.TRACE_ID));
    assertThat(idsInRun.get(CUSTOMER_ID)).isEqualTo(NOT_SET);
  }

  /** A job that runs every 50 milliseconds and records the IDs each run sees. */
  @EnableScheduling
  @RequiredArgsConstructor
  static class CleanupJob {

    /** The IDs each run saw, newest last. */
    final BlockingQueue<Map<String, String>> idsSeenByRuns = new LinkedBlockingQueue<>();

    /** Reads the current trace; Spring gives each run its own span. */
    private final Tracer tracer;

    /** Records the trace ID, correlation ID and customer ID this run sees. */
    @Scheduled(fixedDelay = 50)
    void removeExpiredCarts() {
      var span = tracer.currentSpan();
      idsSeenByRuns.add(
          Map.of(
              LoggingContextConstants.TRACE_ID,
              span == null ? NOT_SET : span.context().traceId(),
              LoggingContextConstants.CORRELATION_ID,
              Objects.requireNonNullElse(MDC.get(LoggingContextConstants.CORRELATION_ID), NOT_SET),
              CUSTOMER_ID,
              Objects.requireNonNullElse(MDC.get(CUSTOMER_ID), NOT_SET)));
    }
  }
}
