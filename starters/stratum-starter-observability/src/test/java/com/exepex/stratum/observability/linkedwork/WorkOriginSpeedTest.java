package com.exepex.stratum.observability.linkedwork;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.exepex.stratum.test.CpuCostMeter;
import java.time.Duration;
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
   * Stored texts of 1MB, each with the CPU time refusing it may take, built only when the test
   * runs: junk, a valid origin with a long correlation ID, and a trace ID that never ends.
   */
  private static final Supplier<Stream<Arguments>> HOSTILE_TEXTS_AND_LIMITS =
      () ->
          Stream.of(
              Arguments.of("1MB of junk", "a".repeat(LARGE_LENGTH), Duration.ofSeconds(1)),
              Arguments.of(
                  "a 1MB correlation ID",
                  STORED_ORIGIN + "a".repeat(LARGE_LENGTH),
                  Duration.ofSeconds(1)),
              Arguments.of(
                  "a 1MB trace ID", "00-" + "f".repeat(LARGE_LENGTH), Duration.ofSeconds(1)));

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
