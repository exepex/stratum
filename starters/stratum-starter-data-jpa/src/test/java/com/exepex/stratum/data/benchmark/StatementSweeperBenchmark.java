package com.exepex.stratum.data.benchmark;

import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
 * Measures what Stratum's fallback statement limit, the sweeper, costs per statement, with no
 * database: writing the deadline into the connection's slot and clearing it, on one thread and on
 * 16, each thread with its own slot as each connection has. The {@code none} side runs the same
 * call without the slot, so the sweeper's cost is the difference.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class StatementSweeperBenchmark {

  /** The limit of each call, in milliseconds: Stratum's 30s default. */
  private static final long LIMIT_MILLIS = 30_000;

  /** The call every side runs: it does no work. */
  private static final StatementTimer.SqlCall<Integer> NO_WORK = () -> 1;

  /**
   * Runs a call that does no work, with its deadline in the slot except on the none side, on one
   * thread.
   *
   * @param slot this thread's slot
   * @return the call's result, which JMH consumes
   * @throws SQLException never: the call does no work
   */
  @Benchmark
  @Threads(1)
  public int sweeper(ThreadSlot slot) throws SQLException {
    return slot.run();
  }

  /**
   * Runs a call that does no work, with its deadline in the slot except on the none side, on 16
   * threads at once.
   *
   * @param slot this thread's slot
   * @return the call's result, which JMH consumes
   * @throws SQLException never: the call does no work
   */
  @Benchmark
  @Threads(16)
  public int sweeperConcurrently(ThreadSlot slot) throws SQLException {
    return slot.run();
  }

  /** The sweeper every thread shares, which the none side starts too but never uses. */
  @State(Scope.Benchmark)
  public static class SharedSweeper {

    /** No limit, or the sweeper's limit. */
    @Param({"none", "sweeper"})
    public String setting;

    /** The sweeper of this fork. */
    private StatementSweeperModel sweeper;

    /** Starts the sweeper thread. */
    @Setup(Level.Trial)
    public void start() {
      sweeper = StatementSweeperModel.start();
    }

    /** Stops the sweeper thread. */
    @TearDown(Level.Trial)
    public void stop() {
      sweeper.stop();
    }
  }

  /** One thread's slot, as one connection has. */
  @State(Scope.Thread)
  public static class ThreadSlot {

    /** The sweeper the slot belongs to. */
    private StatementSweeperModel sweeper;

    /** This thread's slot. */
    private AtomicLong slot;

    /** Whether the call runs with its deadline in the slot. */
    private boolean usesSlot;

    /**
     * Opens this thread's slot.
     *
     * @param shared the sweeper of this fork
     */
    @Setup(Level.Trial)
    public void open(SharedSweeper shared) {
      sweeper = shared.sweeper;
      slot = sweeper.open();
      usesSlot = "sweeper".equals(shared.setting);
    }

    /**
     * Runs the call, with its deadline in the slot on the sweeper side.
     *
     * @return the call's result
     * @throws SQLException never: the call does no work
     */
    int run() throws SQLException {
      return usesSlot ? sweeper.around(slot, LIMIT_MILLIS, NO_WORK) : NO_WORK.run();
    }
  }
}
