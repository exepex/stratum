package com.exepex.stratum.observability;

import static com.exepex.stratum.observability.testsupport.ObservabilityTestData.HOSTILE_INPUT_LENGTH;
import static com.exepex.stratum.observability.testsupport.ObservabilityTestData.SAMPLED_SPAN;
import static com.exepex.stratum.observability.testsupport.ObservabilityTestData.TRACE_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.logging.LoggingContextConstants;
import com.exepex.stratum.test.CpuCostMeter;
import io.micrometer.tracing.otel.bridge.EventPublishingContextWrapper.ScopeAttachedEvent;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
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

  // A scope change takes from under 1 to a few microseconds, so a batch of a few thousand measures
  // scheduler and JIT noise; batches of about 50ms of CPU time average it out.
  /** Scope changes in each timed batch with the ID of safe characters: about 50ms of work. */
  private static final int SAFE_ID_CALLS_PER_BATCH = 50_000;

  /** Scope changes in each timed batch with the ID of unsafe characters: about 50ms of work. */
  private static final int UNSAFE_ID_CALLS_PER_BATCH = 1_000_000;

  /** A run of characters that are not safe for logs: a space and a line feed. */
  private static final String UNSAFE_CHARACTERS = "a b\n";

  /**
   * CPU time a scope change with a 1MB ID of safe characters may take: 5 times M, where M = 2.241
   * µs is the largest best value of 3 CI runs with coverage on (2.241 µs, 1.980 µs, 2.164 µs), CI
   * run 41755894601 on JDK 25, 2026-11-04.
   */
  private static final Duration SAFE_ID_LIMIT = Duration.of(11_206, ChronoUnit.NANOS);

  /**
   * CPU time a scope change with a 1MB ID of unsafe characters may take: 5 times M, where M = 0.113
   * µs is the largest best value of 3 CI runs with coverage on (0.113 µs, 0.108 µs, 0.113 µs), CI
   * run 41755894601 on JDK 25, 2026-11-04.
   */
  private static final Duration UNSAFE_ID_LIMIT = Duration.of(567, ChronoUnit.NANOS);

  /**
   * Baggage correlation IDs of 1MB, each with the scope changes in a timed batch and the CPU time a
   * scope change carrying it may take, built only when the test runs: safe characters only, and
   * characters that are not safe for logs.
   */
  private static final Supplier<Stream<Arguments>> HOSTILE_IDS_AND_LIMITS =
      () ->
          Stream.of(
              Arguments.of(
                  "a 1MB ID of safe characters",
                  "a".repeat(HOSTILE_INPUT_LENGTH),
                  SAFE_ID_CALLS_PER_BATCH,
                  SAFE_ID_LIMIT),
              Arguments.of(
                  "a 1MB ID of unsafe characters",
                  UNSAFE_CHARACTERS.repeat(HOSTILE_INPUT_LENGTH / UNSAFE_CHARACTERS.length()),
                  UNSAFE_ID_CALLS_PER_BATCH,
                  UNSAFE_ID_LIMIT));

  private final CorrelationIdRecorder recorder = new CorrelationIdRecorder();

  /** Clears the logging context after each test. */
  @AfterEach
  void clearLoggingContext() {
    MDC.clear();
  }

  @ParameterizedTest(name = "{0}")
  @FieldSource("HOSTILE_IDS_AND_LIMITS")
  @DisplayName("a span made current with a 1MB ID gets the trace ID within its limit")
  void hostileIdIsRefusedWithinLimit(
      String idName, String correlationId, int callsPerBatch, Duration limit) {
    var baggage =
        Baggage.builder().put(LoggingContextConstants.CORRELATION_ID, correlationId).build();
    var scope = new ScopeAttachedEvent(Context.root().with(SAMPLED_SPAN).with(baggage));

    recorder.onEvent(scope);
    assertThat(MDC.get(LoggingContextConstants.CORRELATION_ID)).isEqualTo(TRACE_ID);

    new CpuCostMeter(callsPerBatch)
        .assertCpuCostWithin(idName, limit, () -> recorder.onEvent(scope));
  }
}
