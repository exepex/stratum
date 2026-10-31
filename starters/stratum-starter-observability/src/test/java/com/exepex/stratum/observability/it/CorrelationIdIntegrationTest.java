package com.exepex.stratum.observability.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.ErrorReport;
import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.observability.ObservabilityErrorCode;
import com.exepex.stratum.observability.testsupport.RecordedSpans;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.common.AttributeKey;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
    classes = {OrdersApplication.class, RecordedSpans.class},
    properties = {
      "spring.main.web-application-type=none",
      "stratum.observability.enabled=true",
      "management.tracing.sampling.probability=1.0",
      // Spring Boot propagates trace headers only while span export is on. Nothing listens
      // here, so the spans are dropped quietly.
      "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:1/v1/traces",
      "management.otlp.metrics.export.enabled=false"
    })
// Closes the application after this class, so its tracing and threads never reach the next one.
@DirtiesContext
@DisplayName("The correlation ID in an application with the observability starter")
class CorrelationIdIntegrationTest {

  /** A correlation ID set in baggage, as the web starter or the service's code would. */
  private static final String ORDER_CORRELATION_ID = "order-42-c7d1";

  /** A correlation ID with a line break, which could forge a log line. */
  private static final String UNSAFE_CORRELATION_ID = "order-42\nlevel=ERROR";

  /** The span of the request, which sets the correlation ID. */
  private static final String PLACE_ORDER_SPAN = "place-order";

  /** A child span of the request, which must record its correlation ID. */
  private static final String CHARGE_CARD_SPAN = "charge-card";

  /** The span attribute that holds the correlation ID. */
  private static final AttributeKey<String> CORRELATION_ID_ATTRIBUTE =
      AttributeKey.stringKey(LoggingContextConstants.CORRELATION_ID);

  /** How long to wait for work on the executor. */
  private static final long EXECUTOR_TIMEOUT_SECONDS = 10;

  /** Starts and ends spans. */
  @Autowired private Tracer tracer;

  /** Starts observations, which Spring turns into spans. */
  @Autowired private ObservationRegistry observationRegistry;

  /** Spring Boot's task executor, which also runs {@code @Async} methods. */
  @Autowired
  @Qualifier("applicationTaskExecutor")
  private AsyncTaskExecutor applicationTaskExecutor;

  /** Every span that ended. */
  @Autowired private RecordedSpans recordedSpans;

  /** Forgets the spans of earlier tests, so each test reads only its own. */
  @BeforeEach
  void forgetEarlierSpans() {
    recordedSpans.clear();
  }

  /** Returns the correlation ID of an error report made now, as a caller would see it. */
  private String correlationIdOfErrorReport() {
    return ErrorReport.of(ObservabilityErrorCode.EXPORT_ENDPOINT_MISSING)
        .getExternal()
        .correlationId();
  }

  /** Returns the correlation ID recorded on the ended span with this name, if any. */
  private String correlationIdRecordedOn(String spanName) {
    return recordedSpans.named(spanName).getAttributes().get(CORRELATION_ID_ATTRIBUTE);
  }

  @Test
  @DisplayName("with none set, it is the trace ID in logs and error reports, and gone afterwards")
  void defaultsToTraceId() {
    var span = tracer.nextSpan().name(PLACE_ORDER_SPAN).start();
    try (var _ = tracer.withSpan(span)) {
      var traceId = span.context().traceId();
      assertThat(MDC.get(LoggingContextConstants.CORRELATION_ID)).isEqualTo(traceId);
      assertThat(correlationIdOfErrorReport()).isEqualTo(traceId);
    } finally {
      span.end();
    }
    assertThat(MDC.get(LoggingContextConstants.CORRELATION_ID)).isNull();
  }

  @Test
  @DisplayName("work on Spring Boot's task executor keeps the caller's trace and correlation ID")
  void taskExecutorKeepsTrace() throws Exception {
    Observation.createNotStarted(PLACE_ORDER_SPAN, observationRegistry)
        .observeChecked(() -> assertExecutorKeepsCorrelationId());
  }

  /**
   * Checks that work on the executor sees the caller's correlation ID, which is its trace ID when
   * no baggage is set.
   */
  private void assertExecutorKeepsCorrelationId() throws Exception {
    var callerTraceId = tracer.currentSpan().context().traceId();
    var correlationIdOnExecutor =
        applicationTaskExecutor
            .submit(() -> MDC.get(LoggingContextConstants.CORRELATION_ID))
            .get(EXECUTOR_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(correlationIdOnExecutor).isEqualTo(callerTraceId);
  }

  @Test
  @DisplayName(
      "one set in baggage is used in logs and error reports, recorded on later spans, and kept"
          + " when a child span's scope closes")
  void usesBaggageCorrelationId() {
    startSpanWithBaggage(ORDER_CORRELATION_ID);

    assertThat(correlationIdRecordedOn(CHARGE_CARD_SPAN)).isEqualTo(ORDER_CORRELATION_ID);
  }

  @Test
  @DisplayName("an unsafe one in baggage is ignored, and the trace ID is used instead")
  void ignoresUnsafeBaggageCorrelationId() {
    startSpanWithBaggage(UNSAFE_CORRELATION_ID);

    assertThat(Stream.of(PLACE_ORDER_SPAN, CHARGE_CARD_SPAN).map(this::correlationIdRecordedOn))
        .hasSize(2)
        .doesNotContain(UNSAFE_CORRELATION_ID);
  }

  /**
   * Sets a correlation ID in baggage inside a span, checks the logging context and error reports,
   * and runs a child span that should record it. After the child's scope closes, the parent's ID
   * must be back in the logging context.
   */
  private void startSpanWithBaggage(String correlationId) {
    var span = tracer.nextSpan().name(PLACE_ORDER_SPAN).start();
    try (var _ = tracer.withSpan(span);
        var _ =
            tracer.createBaggageInScope(LoggingContextConstants.CORRELATION_ID, correlationId)) {
      var expectedId =
          ORDER_CORRELATION_ID.equals(correlationId) ? correlationId : span.context().traceId();
      assertThat(MDC.get(LoggingContextConstants.CORRELATION_ID)).isEqualTo(expectedId);
      assertThat(correlationIdOfErrorReport()).isEqualTo(expectedId);
      var childSpan = tracer.nextSpan().name(CHARGE_CARD_SPAN).start();
      try (var _ = tracer.withSpan(childSpan)) {
        assertThat(MDC.get(LoggingContextConstants.CORRELATION_ID)).isEqualTo(expectedId);
      } finally {
        childSpan.end();
      }
      assertThat(MDC.get(LoggingContextConstants.CORRELATION_ID)).isEqualTo(expectedId);
    } finally {
      span.end();
    }
  }
}
