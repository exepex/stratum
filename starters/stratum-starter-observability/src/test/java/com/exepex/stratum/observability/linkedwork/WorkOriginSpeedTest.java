package com.exepex.stratum.observability.linkedwork;

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

  /** Characters in each hostile text: 1MB. */
  private static final int LARGE_LENGTH = 1_000_000;

  /** Texts refused in each timed batch: a few milliseconds of work. */
  private static final int CALLS_PER_BATCH = 1_000;

  /** A stored origin as a service gives it back. */
  private static final String STORED_ORIGIN =
      "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01 order-42";

  /**
   * CPU time refusing 1MB of junk may take: 5 times M, where M = 14.666 µs is the largest best
   * value of 3 CI runs with coverage on (9.836 µs, 10.999 µs, 14.666 µs), CI run 41749701016 on JDK
   * 25, 2026-11-04.
   */
  private static final Duration JUNK_LIMIT = Duration.of(73_329, ChronoUnit.NANOS);

  /**
   * CPU time refusing a 1MB correlation ID may take: 5 times M, where M = 19.182 µs is the largest
   * best value of 3 CI runs with coverage on (10.151 µs, 13.688 µs, 19.182 µs), CI run 41749701016
   * on JDK 25, 2026-11-04.
   */
  private static final Duration CORRELATION_ID_LIMIT = Duration.of(95_912, ChronoUnit.NANOS);

  /**
   * CPU time refusing a 1MB trace ID may take: 5 times M, where M = 14.858 µs is the largest best
   * value of 3 CI runs with coverage on (8.527 µs, 10.311 µs, 14.858 µs), CI run 41749701016 on JDK
   * 25, 2026-11-04.
   */
  private static final Duration TRACE_ID_LIMIT = Duration.of(74_291, ChronoUnit.NANOS);

  /**
   * Stored texts of 1MB, each with the CPU time refusing it may take, built only when the test
   * runs: junk, a valid origin with a long correlation ID, and a trace ID that never ends.
   */
  private static final Supplier<Stream<Arguments>> HOSTILE_TEXTS_AND_LIMITS =
      () ->
          Stream.of(
              Arguments.of("1MB of junk", "a".repeat(LARGE_LENGTH), JUNK_LIMIT),
              Arguments.of(
                  "a 1MB correlation ID",
                  STORED_ORIGIN + "a".repeat(LARGE_LENGTH),
                  CORRELATION_ID_LIMIT),
              Arguments.of("a 1MB trace ID", "00-" + "f".repeat(LARGE_LENGTH), TRACE_ID_LIMIT));

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
