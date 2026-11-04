package com.exepex.stratum.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.logging.LoggingContextConstants;
import com.exepex.stratum.test.CpuCostMeter;
import io.micrometer.tracing.otel.bridge.EventPublishingContextWrapper.ScopeAttachedEvent;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import java.time.Duration;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.slf4j.MDC;

@DisplayName("Refusing a 1MB baggage correlation ID stays inside its CPU time limit")
class CorrelationIdRecorderSpeedTest {

  /** Characters in each hostile correlation ID: 1MB. */
  private static final int LARGE_LENGTH = 1_000_000;

  /** Scope changes in each timed batch: a few milliseconds of work. */
  private static final int CALLS_PER_BATCH = 3_000;

  /** The trace ID of the span, which the logging context gets in place of a refused ID. */
  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  /** A run of characters that are not safe for logs: a space and a line feed. */
  private static final String UNSAFE_CHARACTERS = "a b\n";

  /**
   * Baggage correlation IDs of 1MB, each with the CPU time a scope change carrying it may take,
   * built only when the test runs: safe characters only, and characters that are not safe for logs.
   */
  private static final Supplier<Stream<Arguments>> HOSTILE_IDS_AND_LIMITS =
      () ->
          Stream.of(
              Arguments.of(
                  "a 1MB ID of safe characters", "a".repeat(LARGE_LENGTH), Duration.ofSeconds(1)),
              Arguments.of(
                  "a 1MB ID of unsafe characters",
                  UNSAFE_CHARACTERS.repeat(LARGE_LENGTH / UNSAFE_CHARACTERS.length()),
                  Duration.ofSeconds(1)));

  /** The span made current with each baggage. */
  private static final Span SPAN =
      Span.wrap(
          SpanContext.create(
              TRACE_ID, "00f067aa0ba902b7", TraceFlags.getSampled(), TraceState.getDefault()));

  private final CorrelationIdRecorder recorder = new CorrelationIdRecorder();

  private final CpuCostMeter meter = new CpuCostMeter(CALLS_PER_BATCH);

  /** Clears the logging context after each test. */
  @AfterEach
  void clearLoggingContext() {
    MDC.clear();
  }

  @ParameterizedTest(name = "{0}")
  @FieldSource("HOSTILE_IDS_AND_LIMITS")
  @DisplayName("a span made current with a 1MB ID gets the trace ID within its limit")
  void hostileIdIsRefusedWithinLimit(String idName, String correlationId, Duration limit) {
    var baggage =
        Baggage.builder().put(LoggingContextConstants.CORRELATION_ID, correlationId).build();
    var scope = new ScopeAttachedEvent(Context.root().with(SPAN).with(baggage));

    recorder.onEvent(scope);
    assertThat(MDC.get(LoggingContextConstants.CORRELATION_ID)).isEqualTo(TRACE_ID);

    meter.assertCpuCostWithin(idName, limit, () -> recorder.onEvent(scope));
  }
}
