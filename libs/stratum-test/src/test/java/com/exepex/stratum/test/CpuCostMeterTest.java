package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CpuCostMeter passes a call within its CPU time limit and fails one above it")
class CpuCostMeterTest {

  /** CPU time each call under test spends. */
  private static final Duration CALL_COST = Duration.ofMillis(2);

  /** Calls in each batch of the per-call check, so the batch costs 4 times one call. */
  private static final int CALLS_IN_ONE_BATCH = 4;

  /** The batches the meter times after its warm-up. */
  private static final int MEASURED_BATCHES = 5;

  /** The warm-up batches a meter runs when a test sets none. */
  private static final int DEFAULT_WARM_UP_BATCHES = 3;

  /** A warm-up longer than the default of 3 batches, as a slow-to-compile call needs. */
  private static final int LONG_WARM_UP_BATCHES = 20;

  /**
   * A limit above the cost of one call (2ms) and below that of a batch of them (8ms), so a call
   * passes it only when the cost is counted per call.
   */
  private static final Duration BETWEEN_CALL_AND_BATCH = Duration.ofMillis(3);

  /** One call per batch, so the test takes a few tens of milliseconds. */
  private final CpuCostMeter meter = new CpuCostMeter(1);

  /** Reads the CPU time of the test's thread, so the call spends a known amount of it. */
  private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();

  @Test
  @DisplayName("a call that spends less CPU time than the limit passes")
  void passesCallWithinLimit() {
    assertThatCode(
            () -> meter.assertCpuCostWithin("a cheap call", Duration.ofSeconds(1), this::spendCpu))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a call that spends more CPU time than the limit fails, naming the call")
  void failsCallAboveLimit() {
    var justBelowCost = CALL_COST.minusNanos(1);

    assertThatExceptionOfType(AssertionError.class)
        .isThrownBy(() -> meter.assertCpuCostWithin("a slow call", justBelowCost, this::spendCpu))
        .withMessageContaining("a slow call");
  }

  @Test
  @DisplayName("the limit applies to the cost of one call, not to the whole batch")
  void limitsOneCallNotTheBatch() {
    assertThatCode(
            () ->
                new CpuCostMeter(CALLS_IN_ONE_BATCH)
                    .assertCpuCostWithin(
                        "a batch of calls", BETWEEN_CALL_AND_BATCH, this::spendCpu))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("runs the warm-up batches it is given, or 3, before the 5 it times")
  void runsWarmUpBatchesBeforeMeasuring() {
    var defaultWarmUpCalls = new AtomicInteger();
    var longWarmUpCalls = new AtomicInteger();

    new CpuCostMeter(1)
        .assertCpuCostWithin(
            "a counted call", Duration.ofSeconds(1), defaultWarmUpCalls::incrementAndGet);
    new CpuCostMeter(1, LONG_WARM_UP_BATCHES)
        .assertCpuCostWithin(
            "a counted call", Duration.ofSeconds(1), longWarmUpCalls::incrementAndGet);

    assertThat(defaultWarmUpCalls).hasValue(DEFAULT_WARM_UP_BATCHES + MEASURED_BATCHES);
    assertThat(longWarmUpCalls).hasValue(LONG_WARM_UP_BATCHES + MEASURED_BATCHES);
  }

  /** Spends {@link #CALL_COST} of the thread's CPU time. */
  private void spendCpu() {
    var end = threads.getCurrentThreadCpuTime() + CALL_COST.toNanos();
    // A loop, not a stream: it only waits for the thread's CPU clock to pass the end.
    while (threads.getCurrentThreadCpuTime() < end) {
      Thread.onSpinWait();
    }
  }
}
