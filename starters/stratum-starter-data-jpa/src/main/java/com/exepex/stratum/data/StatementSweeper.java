package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Cancels every statement that runs past its limit. One thread checks the slot of every borrowed
 * connection every 50 milliseconds and cancels a statement past its deadline on a virtual thread,
 * since a cancel opens a connection to the database. A statement is cancelled within 50
 * milliseconds after its limit, never before. The sweeper logs nothing and carries no logging or
 * tracing context.
 */
@StratumInternal
public class StatementSweeper implements AutoCloseable {

  /** How often the slots are checked, in milliseconds. */
  static final long SWEEP_MILLIS = 50;

  /** The slots of the borrowed connections: one is added on borrow and removed on return. */
  private final Set<StatementSlot> slots = ConcurrentHashMap.newKeySet();

  /** The sweeper's clock, in milliseconds, which only goes forward. */
  private final LongSupplier ticker;

  /** Starts the thread that runs one cancel. */
  private final Consumer<Runnable> cancelStarter;

  /** The thread that checks the slots. */
  private final ScheduledThreadPoolExecutor thread;

  /**
   * Creates the sweeper with its own clock and the way it starts each cancel, and does not start
   * checking; tests call {@link #sweep} themselves.
   *
   * @param ticker the clock, in milliseconds, which only goes forward
   * @param cancelStarter starts the thread that runs one cancel
   */
  StatementSweeper(LongSupplier ticker, Consumer<Runnable> cancelStarter) {
    this.ticker = ticker;
    this.cancelStarter = cancelStarter;
    this.thread =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().name("stratum-statement-sweeper").daemon().factory());
  }

  /**
   * Starts the sweeper of the application: it checks the slots every 50 milliseconds, on the
   * monotonic clock, and cancels each statement on a new virtual thread.
   *
   * @return the running sweeper
   */
  public static StatementSweeper start() {
    long origin = System.nanoTime();
    var sweeper =
        new StatementSweeper(
            () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - origin),
            task -> Thread.ofVirtual().name("stratum-statement-cancel").start(task));
    sweeper.thread.scheduleWithFixedDelay(
        sweeper::sweep, SWEEP_MILLIS, SWEEP_MILLIS, TimeUnit.MILLISECONDS);
    return sweeper;
  }

  /**
   * Returns the sweeper's clock, which the statements' deadlines count on.
   *
   * @return the time now, in milliseconds
   */
  long now() {
    return ticker.getAsLong();
  }

  /**
   * Starts checking the slot of a borrowed connection.
   *
   * @param slot the connection's slot
   */
  void register(StatementSlot slot) {
    slots.add(slot);
  }

  /**
   * Stops checking the slot of a connection that is returned or aborted.
   *
   * @param slot the connection's slot
   */
  void release(StatementSlot slot) {
    slots.remove(slot);
  }

  /**
   * Returns how many slots are checked, which is how many connections are borrowed.
   *
   * @return the number of slots
   */
  int size() {
    return slots.size();
  }

  /**
   * Claims every statement past its deadline and starts its cancel. A cancel thread that cannot
   * start ends the claim at once, so the statement's thread does not wait for it, and the sweep
   * goes on.
   */
  void sweep() {
    long now = ticker.getAsLong();
    for (var slot : slots) {
      var claim = slot.claimIfExpired(now);
      if (claim != null) {
        try {
          cancelStarter.accept(() -> cancel(claim));
        } catch (RejectedExecutionException notStarted) {
          slot.cancelEnded(claim.running());
        }
      }
    }
  }

  /** Cancels a claimed statement; a cancel that fails leaves it to the network timeout. */
  private static void cancel(StatementSlot.Claim claim) {
    try {
      claim.statement().cancel();
    } catch (SQLException ignored) {
      // The statement then ends at the connection's network timeout.
    } finally {
      claim.slot().cancelEnded(claim.running());
    }
  }

  /** Stops the thread when the application stops. */
  @Override
  public void close() {
    thread.shutdownNow();
  }
}
