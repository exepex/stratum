package com.exepex.stratum.data.benchmark;

import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stratum's fallback statement limit, as the benchmarks measure it before it is wired in: each
 * connection keeps one reused slot; a statement writes its deadline into the slot and ends with one
 * compare-and-set back to idle; one sweeper thread checks every open slot every 50 milliseconds and
 * marks a statement past its deadline as cancelling. Nothing is allocated and no lock is taken per
 * statement.
 *
 * @param sweeper the one thread that checks the slots
 * @param slots the slots of the open connections, added on borrow and removed on return
 * @param origin the time every deadline counts from, so that a deadline is never negative
 */
record StatementSweeper(ScheduledThreadPoolExecutor sweeper, Set<AtomicLong> slots, long origin) {

  /** How often the sweeper checks the slots, in milliseconds. */
  private static final long SWEEP_MILLIS = 50;

  /** The value of a slot whose connection runs no statement. */
  private static final long IDLE = -1;

  /** The value the sweeper sets on a slot whose statement ran past its deadline. */
  private static final long CANCELLING = -2;

  /**
   * Starts the sweeper thread: one daemon platform thread, which checks the slots every 50ms.
   *
   * @return the sweeper
   */
  static StatementSweeper start() {
    var statementSweeper =
        new StatementSweeper(
            new ScheduledThreadPoolExecutor(
                1, Thread.ofPlatform().daemon().name("stratum-statement-sweeper").factory()),
            ConcurrentHashMap.newKeySet(),
            System.nanoTime());
    statementSweeper
        .sweeper()
        .scheduleAtFixedRate(
            statementSweeper::sweep, SWEEP_MILLIS, SWEEP_MILLIS, TimeUnit.MILLISECONDS);
    return statementSweeper;
  }

  /**
   * Returns the slot of a newly opened connection, which the sweeper checks from then on.
   *
   * @return the slot, idle
   */
  AtomicLong open() {
    var slot = new AtomicLong(IDLE);
    slots.add(slot);
    return slot;
  }

  /**
   * Removes the slot of a connection being returned, which the sweeper then no longer checks.
   *
   * @param slot the connection's slot
   */
  void close(AtomicLong slot) {
    slots.remove(slot);
  }

  /**
   * Runs a call with its deadline in the connection's slot, and clears the slot when it ends.
   *
   * @param slot the connection's slot
   * @param limitMillis the limit, in milliseconds
   * @param call the call the limit applies to
   * @param <T> the call's result
   * @return the call's result
   * @throws SQLException if the call fails
   */
  <T> T around(AtomicLong slot, long limitMillis, StatementTimer.SqlCall<T> call)
      throws SQLException {
    long deadline = System.nanoTime() - origin + TimeUnit.MILLISECONDS.toNanos(limitMillis);
    slot.set(deadline);
    try {
      return call.run();
    } finally {
      if (!slot.compareAndSet(deadline, IDLE)) {
        slot.set(IDLE);
      }
    }
  }

  /** Marks every statement past its deadline as cancelling. */
  private void sweep() {
    long now = System.nanoTime() - origin;
    for (var slot : slots) {
      long deadline = slot.get();
      if (deadline >= 0 && deadline <= now) {
        slot.compareAndSet(deadline, CANCELLING);
      }
    }
  }

  /** Stops the sweeper thread. */
  void stop() {
    sweeper.shutdownNow();
  }
}
