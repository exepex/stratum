package com.exepex.stratum.observability.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.ErrorReport;
import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.observability.ObservabilityErrorCode;
import io.micrometer.tracing.Tracer;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

@DisplayName("The correlation ID in an application with the observability starter")
class CorrelationIdIntegrationTest {

  /** A correlation ID set in baggage, as the web starter or the service's code would. */
  private static final String ORDER_CORRELATION_ID = "order-42-c7d1";

  /** A correlation ID with a line break, which could forge a log line. */
  private static final String UNSAFE_CORRELATION_ID = "order-42\nlevel=ERROR";

  /** Receives the spans the application sends. */
  private final CollectorStub collector = new CollectorStub();

  /**
   * Starts the collector stub for one test.
   *
   * @throws IOException if no local port is free
   */
  CorrelationIdIntegrationTest() throws IOException {}

  /** Stops the collector stub after each test. */
  @AfterEach
  void stopCollector() {
    collector.close();
  }

  /** Starts the test application with the module on, sending every span to the stub. */
  private ConfigurableApplicationContext start() {
    return new SpringApplicationBuilder(OrdersApplication.class)
        .web(WebApplicationType.NONE)
        .run(
            "--stratum.observability.enabled=true",
            "--management.otlp.metrics.export.url=" + collector.url(CollectorStub.METRICS_PATH),
            "--management.opentelemetry.tracing.export.otlp.endpoint="
                + collector.url(CollectorStub.TRACES_PATH),
            "--management.tracing.sampling.probability=1.0");
  }

  /** Returns the correlation ID of an error report made now, as a caller would see it. */
  private String correlationIdOfErrorReport() {
    return ErrorReport.of(ObservabilityErrorCode.EXPORT_ENDPOINT_MISSING)
        .getExternal()
        .correlationId();
  }

  @Test
  @DisplayName("with none set, it is the trace ID in logs and error reports, and gone afterwards")
  void defaultsToTraceId() {
    try (var context = start()) {
      var tracer = context.getBean(Tracer.class);
      var span = tracer.nextSpan().name("place-order").start();
      try (var _ = tracer.withSpan(span)) {
        var traceId = span.context().traceId();
        assertThat(MDC.get(LoggingContextConstants.CORRELATION_ID)).isEqualTo(traceId);
        assertThat(correlationIdOfErrorReport()).isEqualTo(traceId);
      } finally {
        span.end();
      }
      assertThat(MDC.get(LoggingContextConstants.CORRELATION_ID)).isNull();
    }
  }

  @Test
  @DisplayName("one set in baggage is used in logs and error reports, and recorded on later spans")
  void usesBaggageCorrelationId() {
    try (var context = start()) {
      startSpanWithBaggage(context.getBean(Tracer.class), ORDER_CORRELATION_ID);
    }

    assertThat(collector.bodiesOf(CollectorStub.TRACES_PATH))
        .anySatisfy(spans -> assertThat(spans).contains("charge-card", ORDER_CORRELATION_ID));
  }

  @Test
  @DisplayName("an unsafe one in baggage is ignored, and the trace ID is used instead")
  void ignoresUnsafeBaggageCorrelationId() {
    try (var context = start()) {
      startSpanWithBaggage(context.getBean(Tracer.class), UNSAFE_CORRELATION_ID);
    }

    assertThat(collector.bodiesOf(CollectorStub.TRACES_PATH))
        .isNotEmpty()
        .allSatisfy(spans -> assertThat(spans).doesNotContain(UNSAFE_CORRELATION_ID));
  }

  /**
   * Sets a correlation ID in baggage inside a span, checks the logging context and error reports,
   * and starts a child span that should record it.
   */
  private void startSpanWithBaggage(Tracer tracer, String correlationId) {
    var span = tracer.nextSpan().name("place-order").start();
    try (var _ = tracer.withSpan(span);
        var _ =
            tracer.createBaggageInScope(LoggingContextConstants.CORRELATION_ID, correlationId)) {
      var expectedId =
          ORDER_CORRELATION_ID.equals(correlationId) ? correlationId : span.context().traceId();
      assertThat(MDC.get(LoggingContextConstants.CORRELATION_ID)).isEqualTo(expectedId);
      assertThat(correlationIdOfErrorReport()).isEqualTo(expectedId);
      tracer.nextSpan().name("charge-card").start().end();
    } finally {
      span.end();
    }
  }
}
