package com.exepex.stratum.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.LoggingContextConstants;
import io.micrometer.tracing.otel.bridge.EventPublishingContextWrapper.ScopeAttachedEvent;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("CorrelationIdRecorder handles contexts made with OpenTelemetry's own API")
class CorrelationIdRecorderTest {

  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  private final CorrelationIdRecorder recorder = new CorrelationIdRecorder();

  /** Clears the logging context after each test. */
  @AfterEach
  void clearLoggingContext() {
    MDC.clear();
  }

  @Test
  @DisplayName("a span made current with no baggage in its context gets the trace ID")
  void spanWithoutBaggageUsesTraceId() {
    var span =
        Span.wrap(
            SpanContext.create(
                TRACE_ID, "00f067aa0ba902b7", TraceFlags.getSampled(), TraceState.getDefault()));

    recorder.onEvent(new ScopeAttachedEvent(Context.root().with(span)));

    assertThat(MDC.get(LoggingContextConstants.CORRELATION_ID)).isEqualTo(TRACE_ID);
  }

  @Test
  @DisplayName("a context without a span, which clears tracing, removes the correlation ID")
  void contextWithoutSpanRemovesId() {
    MDC.put(LoggingContextConstants.CORRELATION_ID, TRACE_ID);

    recorder.onEvent(new ScopeAttachedEvent(Context.root()));

    assertThat(MDC.get(LoggingContextConstants.CORRELATION_ID)).isNull();
  }
}
