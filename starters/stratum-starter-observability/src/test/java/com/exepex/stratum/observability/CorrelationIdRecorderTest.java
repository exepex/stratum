package com.exepex.stratum.observability;

import static com.exepex.stratum.observability.testsupport.ObservabilityTestData.SAMPLED_SPAN;
import static com.exepex.stratum.observability.testsupport.ObservabilityTestData.TRACE_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.logging.LoggingContextConstants;
import io.micrometer.tracing.otel.bridge.EventPublishingContextWrapper.ScopeAttachedEvent;
import io.opentelemetry.context.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("CorrelationIdRecorder handles contexts made with OpenTelemetry's own API")
class CorrelationIdRecorderTest {

  private final CorrelationIdRecorder recorder = new CorrelationIdRecorder();

  /** Clears the logging context after each test. */
  @AfterEach
  void clearLoggingContext() {
    MDC.clear();
  }

  @Test
  @DisplayName("a span made current with no baggage in its context gets the trace ID")
  void spanWithoutBaggageUsesTraceId() {
    recorder.onEvent(new ScopeAttachedEvent(Context.root().with(SAMPLED_SPAN)));

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
