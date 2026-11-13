package com.exepex.stratum.data.benchmark;

import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Measures what Stratum's own statement timer costs per statement, with no database: arming a task
 * at the limit and disarming it, on one thread and on 16. The mixed limits make some tasks the new
 * head of the queue, which wakes the scheduler thread; a fixed limit never does after the first.
 * The {@code none} side runs the same call without the timer, so the timer's cost is the
 * difference.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class StatementTimerBenchmark {

  /** The limits the mixed side cycles through, in milliseconds: 10ms, 1s and 30s. */
  private static final long[] MIXED_LIMITS = {10, 1000, 30_000};

  /** The limit of the fixed side, in milliseconds: Stratum's 30s default. */
  private static final long FIXED_LIMIT = 30_000;

  /** The call every side runs: it does no work. */
  private static final StatementTimer.SqlCall<Integer> NO_WORK = () -> 1;

  /**
   * Runs a call that does no work, with the timer armed around it except on the none side, on one
   * thread.
   *
   * @param timer the shared timer
   * @param limits this thread's limits
   * @return the call's result, which JMH consumes
   * @throws SQLException never: the call does no work
   */
  @Benchmark
  @Threads(1)
  public int timer(SharedTimer timer, Limits limits) throws SQLException {
    return timer.run(limits);
  }

  /**
   * Runs a call that does no work, with the timer armed around it except on the none side, on 16
   * threads at once.
   *
   * @param timer the shared timer
   * @param limits this thread's limits
   * @return the call's result, which JMH consumes
   * @throws SQLException never: the call does no work
   */
  @Benchmark
  @Threads(16)
  public int timerConcurrently(SharedTimer timer, Limits limits) throws SQLException {
    return timer.run(limits);
  }

  /** The timer every thread shares, with mixed or fixed limits, or no timer on the none side. */
  @State(Scope.Benchmark)
  public static class SharedTimer {

    /**
     * No timer, or the timer with mixed limits, so some tasks become the head of the queue, or with
     * a fixed limit.
     */
    @Param({"none", "mixed", "fixed-30s"})
    public String limits;

    /** The timer of this fork. */
    private StatementTimer timer;

    /** Whether the call runs with the timer armed. */
    private boolean armsTimer;

    /** Starts the scheduler thread, which the none side starts too but never uses. */
    @Setup(Level.Trial)
    public void start() {
      timer = StatementTimer.start();
      armsTimer = !"none".equals(limits);
    }

    /**
     * Takes this thread's next limit, then runs the call with the timer armed at it, or without the
     * timer on the none side.
     *
     * @param threadLimits this thread's limits
     * @return the call's result
     * @throws SQLException never: the call does no work
     */
    int run(Limits threadLimits) throws SQLException {
      long limit = threadLimits.next(limits);
      return armsTimer ? timer.around(limit, NO_WORK) : NO_WORK.run();
    }

    /** Stops the scheduler thread. */
    @TearDown(Level.Trial)
    public void stop() {
      timer.stop();
    }
  }

  /** The limits one thread cycles through. */
  @State(Scope.Thread)
  public static class Limits {

    /** The position of the next mixed limit. */
    private int position;

    /**
     * Returns the next limit of this thread.
     *
     * @param kind {@code none}, {@code mixed} or {@code fixed-30s}
     * @return the limit, in milliseconds
     */
    long next(String kind) {
      if (!"mixed".equals(kind)) {
        return FIXED_LIMIT;
      }
      position = (position + 1) % MIXED_LIMITS.length;
      return MIXED_LIMITS[position];
    }
  }
}
