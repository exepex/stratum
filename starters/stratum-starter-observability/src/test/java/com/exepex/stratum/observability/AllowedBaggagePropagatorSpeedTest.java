package com.exepex.stratum.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.logging.LoggingContextConstants;
import com.exepex.stratum.observability.testsupport.BaggageTestData;
import com.exepex.stratum.test.CpuCostMeter;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;

/**
 * Catches a blow-up in reading a caller's baggage, such as a cost that grows with the square of the
 * number of entries. CI runners differ about 2.3 times in speed, so with limits of 5 times the
 * slowest CI run it does not catch a constant-factor slowdown.
 */
@DisplayName("Reading hostile baggage stays inside its CPU time limit")
class AllowedBaggagePropagatorSpeedTest {

  // A read of the capped baggage takes about 10 to 18 microseconds; 5,000 a batch is about 50 to
  // 90ms of CPU time, which averages out scheduler and JIT noise.
  /** Reads in each timed batch of the capped baggage. */
  private static final int CAPPED_CALLS_PER_BATCH = 5_000;

  /**
   * Reads in each timed batch of the 1MB header, which OpenTelemetry drops: a few milliseconds of
   * work, so a read that parses the header still finishes in minutes.
   */
  private static final int HUGE_CALLS_PER_BATCH = 50_000;

  /**
   * CPU time a read of 64 entries in 8,192 bytes may take: 5 times M, where M = 18.059 µs is the
   * largest best value of 3 CI runs with coverage on (9.658 µs, 17.557 µs, 18.059 µs), CI run
   * 42090140160 on JDK 25, 2026-11-05.
   */
  private static final Duration CAPPED_LIMIT = Duration.of(90_293, ChronoUnit.NANOS);

  /**
   * CPU time a read of a 1MB header may take: 5 times M, where M = 0.166 µs is the largest best
   * value of 3 CI runs with coverage on (0.058 µs, 0.154 µs, 0.166 µs), CI run 42090140160 on JDK
   * 25, 2026-11-05.
   */
  private static final Duration HUGE_LIMIT = Duration.of(830, ChronoUnit.NANOS);

  /** The names kept, as by default. */
  private static final Set<String> ALLOWED = Set.of(LoggingContextConstants.CORRELATION_ID);

  /**
   * The hostile baggage, each with the propagator it is read through, the header, the names kept,
   * its reads in a timed batch and the CPU time a read may take, built only when the test runs.
   * OpenTelemetry drops a header of more than 8,192 bytes whole, so the 1MB header keeps no name.
   */
  private static final Supplier<Stream<Arguments>> HOSTILE_BAGGAGE_AND_LIMITS =
      () ->
          Stream.of(
              Arguments.of(
                  "64 entries in 8,192 bytes",
                  new AllowedBaggagePropagator(
                      new FixedBaggagePropagator(BaggageTestData.cappedBaggage()), ALLOWED),
                  BaggageTestData.CORRELATION_ID_ENTRY,
                  ALLOWED,
                  CAPPED_CALLS_PER_BATCH,
                  CAPPED_LIMIT),
              Arguments.of(
                  "a 1MB header",
                  new AllowedBaggagePropagator(W3CBaggagePropagator.getInstance(), ALLOWED),
                  BaggageTestData.hugeHeader(),
                  Set.of(),
                  HUGE_CALLS_PER_BATCH,
                  HUGE_LIMIT));

  @ParameterizedTest(name = "{0}")
  @FieldSource("HOSTILE_BAGGAGE_AND_LIMITS")
  @DisplayName("hostile baggage is read, keeping no name but the correlation ID, within its limit")
  void hostileBaggageIsReadWithinLimit(
      String baggageName,
      TextMapPropagator propagator,
      String baggageHeader,
      Set<String> keptNames,
      int callsPerBatch,
      Duration limit) {
    assertThat(BaggageTestData.namesRead(propagator, baggageHeader))
        .containsExactlyInAnyOrderElementsOf(keptNames);

    var headers = Map.of(BaggageTestData.BAGGAGE_HEADER, baggageHeader);
    new CpuCostMeter(callsPerBatch)
        .assertCpuCostWithin(
            baggageName,
            limit,
            () -> propagator.extract(Context.root(), headers, BaggageTestData.HEADERS));
  }

  /**
   * A propagator that reads the same baggage from every carrier and writes nothing.
   *
   * @param baggage the baggage every read returns
   */
  private record FixedBaggagePropagator(Baggage baggage) implements TextMapPropagator {

    @Override
    public Collection<String> fields() {
      return List.of(BaggageTestData.BAGGAGE_HEADER);
    }

    @Override
    public <C> void inject(Context context, @Nullable C carrier, TextMapSetter<C> setter) {
      // Writes nothing: the guard measures reading.
    }

    @Override
    public <C> Context extract(Context context, @Nullable C carrier, TextMapGetter<C> getter) {
      return context.with(baggage);
    }
  }
}
