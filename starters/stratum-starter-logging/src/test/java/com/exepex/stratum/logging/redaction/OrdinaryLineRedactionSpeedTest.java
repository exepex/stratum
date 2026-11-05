package com.exepex.stratum.logging.redaction;

import static com.exepex.stratum.logging.testsupport.LoggingTestData.DEFAULT_REDACTOR;

import com.exepex.stratum.logging.testsupport.OrdinaryLogLines;
import com.exepex.stratum.test.CpuCostMeter;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Masking an ordinary log line stays inside its CPU time limit")
class OrdinaryLineRedactionSpeedTest {

  /** Lines masked in each batch. */
  private static final int LINES_PER_BATCH = 1_000;

  /**
   * CPU time masking an ordinary line may take: 10 times M = 103.114 µs, the largest best value of
   * 3 CI runs with coverage on (69.835, 103.114 and 86.068 µs), on JDK 25, 2026-11-03.
   */
  private static final Duration ORDINARY_LINE_LIMIT = Duration.of(1_032, ChronoUnit.MICROS);

  /**
   * CPU time masking a line with a stack trace may take: 10 times M = 838.968 µs, the largest best
   * value of 3 CI runs with coverage on (838.968, 823.866 and 777.772 µs), on JDK 25, 2026-11-03.
   */
  private static final Duration STACK_TRACE_LINE_LIMIT = Duration.of(8_390, ChronoUnit.MICROS);

  /** Measures the CPU time of masking each line, in batches of {@link #LINES_PER_BATCH}. */
  private final CpuCostMeter meter = new CpuCostMeter(LINES_PER_BATCH);

  @Nested
  @DisplayName("An ordinary line")
  class AnOrdinaryLine {

    @Test
    @DisplayName("is masked within its limit")
    void maskedWithinTheLimit() {
      meter.assertCpuCostWithin("an ordinary line", ORDINARY_LINE_LIMIT, () -> maskOrdinaryLine());
    }
  }

  @Nested
  @DisplayName("A line with a stack trace")
  class LineWithStackTrace {

    @Test
    @DisplayName("is masked within its limit")
    void maskedWithinTheLimit() {
      meter.assertCpuCostWithin(
          "a line with a stack trace", STACK_TRACE_LINE_LIMIT, () -> maskLineWithStackTrace());
    }
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
