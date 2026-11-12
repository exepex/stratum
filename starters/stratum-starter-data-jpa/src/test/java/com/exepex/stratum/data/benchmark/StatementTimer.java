package com.exepex.stratum.data.benchmark;

import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stratum's planned per-statement timer, as the benchmarks measure it before it is wired in: one
 * shared scheduler thread; per statement, a new state and latch, one scheduled task, and at the end
 * one compare-and-set and one cancel of the task.
 *
 * @param scheduler the one scheduler thread every statement shares
 */
record StatementTimer(ScheduledThreadPoolExecutor scheduler) {

  /** The state of a statement still running. */
  private static final int RUNNING = 0;

  /** The state the timer sets when the limit is reached. */
  private static final int CANCELLING = 1;

  /** The state the end of the statement sets when the timer has not fired. */
  private static final int DONE = 2;

  /**
   * Starts the shared scheduler: one daemon platform thread, which drops a cancelled task at once.
   *
   * @return the timer
   */
  static StatementTimer start() {
    var scheduler =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().daemon().name("stratum-statement-timer").factory());
    scheduler.setRemoveOnCancelPolicy(true);
    return new StatementTimer(scheduler);
  }

  /**
   * Runs a call with a timer task armed at the limit, and disarms it when the call ends.
   *
   * @param limitMillis the limit, in milliseconds
   * @param call the call the limit applies to
   * @param <T> the call's result
   * @return the call's result
   * @throws SQLException if the call fails
   */
  <T> T around(long limitMillis, SqlCall<T> call) throws SQLException {
    var state = new AtomicInteger(RUNNING);
    var cancelled = new CountDownLatch(1);
    var task =
        scheduler.schedule(
            () -> state.compareAndSet(RUNNING, CANCELLING) && cancelled.getCount() > 0,
            limitMillis,
            TimeUnit.MILLISECONDS);
    try {
      return call.run();
    } finally {
      if (state.compareAndSet(RUNNING, DONE)) {
        task.cancel(false);
      }
    }
  }

  /** Stops the scheduler thread. */
  void stop() {
    scheduler.shutdownNow();
  }

  /**
   * A call to the database.
   *
   * @param <T> its result
   */
  @FunctionalInterface
  interface SqlCall<T> {

    /**
     * Runs the call.
     *
     * @return its result
     * @throws SQLException if the database fails
     */
    T run() throws SQLException;
  }
}
