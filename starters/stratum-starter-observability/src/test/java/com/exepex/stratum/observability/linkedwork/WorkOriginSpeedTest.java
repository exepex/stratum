package com.exepex.stratum.observability.linkedwork;

import static com.exepex.stratum.observability.testsupport.ObservabilityTestData.HOSTILE_INPUT_LENGTH;
import static com.exepex.stratum.observability.testsupport.ObservabilityTestData.STORED_ORIGIN;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.exepex.stratum.test.CpuCostMeter;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;

@DisplayName("Refusing 1MB of hostile stored text stays inside its CPU time limit")
class WorkOriginSpeedTest {

  /** Texts refused in each timed batch: a few milliseconds of work. */
  private static final int CALLS_PER_BATCH = 1_000;

  /**
   * CPU time refusing 1MB of junk may take: 5 times M, where M = 11.031 µs is the largest best
   * value of 3 CI runs with coverage on (10.251 µs, 11.031 µs, 10.952 µs), CI run 41755894601 on
   * JDK 25, 2026-11-04.
   */
  private static final Duration JUNK_LIMIT = Duration.of(55_153, ChronoUnit.NANOS);

  /**
   * CPU time refusing a 1MB correlation ID may take: 5 times M, where M = 14.147 µs is the largest
   * best value of 3 CI runs with coverage on (14.034 µs, 13.777 µs, 14.147 µs), CI run 41755894601
   * on JDK 25, 2026-11-04.
   */
  private static final Duration CORRELATION_ID_LIMIT = Duration.of(70_735, ChronoUnit.NANOS);

  /**
   * CPU time refusing a 1MB trace ID may take: 5 times M, where M = 10.562 µs is the largest best
   * value of 3 CI runs with coverage on (10.562 µs, 9.770 µs, 10.538 µs), CI run 41755894601 on JDK
   * 25, 2026-11-04.
   */
  private static final Duration TRACE_ID_LIMIT = Duration.of(52_811, ChronoUnit.NANOS);

  /**
   * Stored texts of 1MB, each with the CPU time refusing it may take, built only when the test
   * runs: junk, a valid origin with a long correlation ID, and a trace ID that never ends.
   */
  private static final Supplier<Stream<Arguments>> HOSTILE_TEXTS_AND_LIMITS =
      () ->
          Stream.of(
              Arguments.of("1MB of junk", "a".repeat(HOSTILE_INPUT_LENGTH), JUNK_LIMIT),
              Arguments.of(
                  "a 1MB correlation ID",
                  STORED_ORIGIN + "a".repeat(HOSTILE_INPUT_LENGTH),
                  CORRELATION_ID_LIMIT),
              Arguments.of(
                  "a 1MB trace ID", "00-" + "f".repeat(HOSTILE_INPUT_LENGTH), TRACE_ID_LIMIT));

  private final CpuCostMeter meter = new CpuCostMeter(CALLS_PER_BATCH);

  @ParameterizedTest(name = "{0}")
  @FieldSource("HOSTILE_TEXTS_AND_LIMITS")
  @DisplayName("each 1MB text is refused within its limit")
  void hostileTextIsRefusedWithinLimit(String textName, String storedText, Duration limit) {
    assertThatIllegalArgumentException().isThrownBy(() -> WorkOrigin.fromText(storedText));

    meter.assertCpuCostWithin(
        textName, limit, () -> catchThrowable(() -> WorkOrigin.fromText(storedText)));
  }
}
