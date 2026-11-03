package com.exepex.stratum.logging.redaction;

import static com.exepex.stratum.logging.testsupport.LoggingTestData.DEFAULT_REDACTOR;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.logging.testsupport.OrdinaryLogLines;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@Slf4j
@DisplayName("Masking an ordinary log line stays inside its CPU time limit")
class OrdinaryLineRedactionSpeedTest {

  /** Batches run first and not counted, so the JIT compiler has compiled the masking code. */
  private static final int WARM_UP_BATCHES = 3;

  /** Batches timed; the fastest counts, so one slow batch on a busy build machine is ignored. */
  private static final int MEASURED_BATCHES = 5;

  /** Lines masked in each batch. */
  private static final int LINES_PER_BATCH = 1_000;

  /** Nanoseconds in a microsecond, the unit the limits and the logged times are in. */
  private static final double NANOS_PER_MICROSECOND = 1_000.0;

  /** CPU time masking an ordinary line may take: provisional, until measured in CI. */
  private static final Duration ORDINARY_LINE_LIMIT = Duration.ofMillis(1);

  /** CPU time masking a line with a stack trace may take: provisional, until measured in CI. */
  private static final Duration STACK_TRACE_LINE_LIMIT = Duration.ofMillis(1);

  /**
   * Times the CPU this test's thread uses, so time spent waiting for a processor is not counted.
   */
  private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();

  @Nested
  @DisplayName("An ordinary line")
  class AnOrdinaryLine {

    @Test
    @DisplayName("is masked within its limit")
    void maskedWithinTheLimit() {
      assertMaskedWithinLimit("an ordinary line", ORDINARY_LINE_LIMIT, () -> maskOrdinaryLine());
    }
  }

  @Nested
  @DisplayName("A line with a stack trace")
  class LineWithStackTrace {

    @Test
    @DisplayName("is masked within its limit")
    void maskedWithinTheLimit() {
      assertMaskedWithinLimit(
          "a line with a stack trace", STACK_TRACE_LINE_LIMIT, () -> maskLineWithStackTrace());
    }
  }

  /** Measures the line, logs its best CPU time and checks it is within the limit. */
  private void assertMaskedWithinLimit(String lineName, Duration limit, Runnable line) {
    assertThat(threads.isCurrentThreadCpuTimeSupported())
        .as("CPU time of the current thread can be measured")
        .isTrue();

    var bestMicros = bestCpuNanosPerLine(line) / NANOS_PER_MICROSECOND;
    log.info("Best CPU microseconds per line for {}: {}", lineName, bestMicros);

    assertThat(bestMicros)
        .as("CPU microseconds per line")
        .isLessThanOrEqualTo(limit.toNanos() / NANOS_PER_MICROSECOND);
  }

  /** Returns the fewest CPU nanoseconds per line over the measured batches, after the warm-up. */
  private long bestCpuNanosPerLine(Runnable line) {
    IntStream.range(0, WARM_UP_BATCHES).forEach(batch -> cpuNanosPerLine(line));
    return IntStream.range(0, MEASURED_BATCHES)
        .mapToLong(batch -> cpuNanosPerLine(line))
        .min()
        .orElseThrow();
  }

  /** Returns the CPU nanoseconds per line of one batch. */
  private long cpuNanosPerLine(Runnable line) {
    var start = threads.getCurrentThreadCpuTime();
    IntStream.range(0, LINES_PER_BATCH).forEach(lineNumber -> line.run());
    return (threads.getCurrentThreadCpuTime() - start) / LINES_PER_BATCH;
  }

  /** Masks a line as the JSON writer does: each safe field, the message and each MDC field. */
  private void maskOrdinaryLine() {
    OrdinaryLogLines.SAFE_ECS_FIELDS.forEach(
        field -> DEFAULT_REDACTOR.redactKnownSafe(field.getKey(), field.getValue()));
    DEFAULT_REDACTOR.redact("message", OrdinaryLogLines.ORDINARY_MESSAGE);
    OrdinaryLogLines.MDC_FIELDS.forEach(
        field -> DEFAULT_REDACTOR.redact(field.getKey(), field.getValue()));
  }

  /** Masks an ordinary line with its error message and stack trace. */
  private void maskLineWithStackTrace() {
    maskOrdinaryLine();
    DEFAULT_REDACTOR.redact("error.message", OrdinaryLogLines.ERROR_MESSAGE);
    DEFAULT_REDACTOR.redactStackTrace("error.stack_trace", OrdinaryLogLines.STACK_TRACE);
  }
}
