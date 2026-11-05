package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.api.StratumInternal;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.stream.IntStream;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Checks that a call stays within a CPU time limit, for guard tests whose limit is calibrated from
 * the costs this class logs on CI. It counts only the CPU the test's thread uses, after a warm-up,
 * and takes the fastest of several batches, so a busy build machine does not fail the test.
 */
@Slf4j
@StratumInternal
@AllArgsConstructor
public class CpuCostMeter {

  /** Warm-up batches when a test sets none, enough for most code under test. */
  private static final int DEFAULT_WARM_UP_BATCHES = 3;

  /** Batches timed; the fastest counts, so one slow batch on a busy build machine is ignored. */
  private static final int MEASURED_BATCHES = 5;

  /** Nanoseconds in a microsecond, the unit the limits and the logged costs are compared in. */
  private static final double NANOS_PER_MICROSECOND = 1_000.0;

  /**
   * Times the CPU this test's thread uses, so time spent waiting for a processor is not counted.
   */
  private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();

  /** Calls in each batch; enough that a batch takes at least a few milliseconds. */
  private final int callsPerBatch;

  /**
   * Batches run first and not counted, so the JIT compiler has compiled the code under test; more
   * for code it needs longer to compile, such as hashing while other tests keep it busy.
   */
  private final int warmUpBatches;

  /**
   * Creates a meter that runs 3 warm-up batches before it measures.
   *
   * @param callsPerBatch calls in each batch
   */
  public CpuCostMeter(int callsPerBatch) {
    this(callsPerBatch, DEFAULT_WARM_UP_BATCHES);
  }

  /**
   * Measures the call, logs its best CPU time per call and checks it is within the limit.
   *
   * @param callName what the call does, as the log line names it, such as {@code "an ordinary
   *     line"}
   * @param limit the most CPU time one call may take
   * @param call the code under test, with its input
   */
  public void assertCpuCostWithin(String callName, Duration limit, Runnable call) {
    assertThat(threads.isCurrentThreadCpuTimeSupported())
        .as("CPU time of the current thread can be measured")
        .isTrue();

    var bestMicros = bestCpuNanosPerCall(call) / NANOS_PER_MICROSECOND;
    if (log.isInfoEnabled()) {
      // The CI logs give the costs each limit is calibrated from; line breaks are removed for
      // SpotBugs.
      log.info(
          ("Best CPU microseconds per call for " + callName + ": " + bestMicros)
              .replaceAll("[\r\n]", " "));
    }

    assertThat(bestMicros)
        .as("CPU microseconds per call for %s", callName)
        .isLessThanOrEqualTo(limit.toNanos() / NANOS_PER_MICROSECOND);
  }

  /** Returns the fewest CPU nanoseconds per call over the measured batches, after the warm-up. */
  private double bestCpuNanosPerCall(Runnable call) {
    IntStream.range(0, warmUpBatches).forEach(batch -> cpuNanosPerCall(call));
    return IntStream.range(0, MEASURED_BATCHES)
        .mapToDouble(batch -> cpuNanosPerCall(call))
        .min()
        .orElseThrow();
  }

  /** Returns the CPU nanoseconds per call of one batch. */
  private double cpuNanosPerCall(Runnable call) {
    var start = threads.getCurrentThreadCpuTime();
    IntStream.range(0, callsPerBatch).forEach(callNumber -> call.run());
    return (threads.getCurrentThreadCpuTime() - start) / (double) callsPerBatch;
  }
}
