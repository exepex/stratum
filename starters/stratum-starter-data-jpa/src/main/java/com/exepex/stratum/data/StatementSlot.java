package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.data.StatementLimitRule.Limit;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import lombok.AccessLevel;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

/**
 * The one place a connection keeps its running statement's deadline, which the {@link
 * StatementSweeper} reads. A connection runs one statement at a time, so the slot is reused by
 * every statement it runs: a statement writes its deadline when it starts and clears it when it
 * ends, with no allocation and no lock. The value is unique per statement, so neither side ever
 * acts on another statement's value.
 */
@StratumInternal
public final class StatementSlot {

  /** The largest deadline, in milliseconds, that fits beside the generation: about 278 years. */
  static final long MAX_DEADLINE = (1L << 43) - 1;

  /** The value of a slot whose connection runs no statement with a limit. */
  static final long IDLE = -1;

  /** The value of the sweeper's claim on the statement of generation 0, below {@link #IDLE}. */
  private static final long CLAIMED_ZERO = IDLE - 1;

  /** How long a claimed statement sleeps between two looks at its cancel: one millisecond. */
  private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

  /** How many bits of the value count the statements, so that each value is unique. */
  private static final int GENERATION_BITS = 20;

  /** The bits of the value that count the statements. */
  private static final long GENERATION_MASK = (1L << GENERATION_BITS) - 1;

  /**
   * {@link #IDLE}; a running statement's deadline and generation, 0 or more; the sweeper's claim on
   * it, {@code -2 - generation}; or its finished cancel, {@code Long.MIN_VALUE + generation}.
   */
  private final AtomicLong state = new AtomicLong(IDLE);

  /** The count of the statements this slot ran, which only the connection's thread changes. */
  private int generation;

  /** The driver's statement now running, published to the sweeper by the write of its value. */
  private @Nullable Statement statement;

  /** The running statement's limit, for its timeout's message and counter. */
  @Getter(AccessLevel.PACKAGE)
  private Limit limit = new Limit(0, StatementLimitRule.LimitSource.QUERY_TIMEOUT);

  /** What the end of a statement found in the slot. */
  enum Outcome {

    /** The sweeper did not claim the statement: it ends as it would without a limit. */
    NOT_CLAIMED,

    /** The sweeper claimed the statement and its cancel has ended. */
    CLAIMED,

    /** The sweeper claimed the statement and its cancel had not ended when the wait ran out. */
    CLAIMED_WAIT_EXPIRED
  }

  /**
   * Writes the deadline of a statement that starts now.
   *
   * @param driverStatement the driver's statement, which the sweeper cancels
   * @param nowMillis the time now, on the sweeper's clock
   * @param statementLimit the statement's limit, more than 0
   * @return the value written, which {@link #end} needs
   */
  long start(Statement driverStatement, long nowMillis, Limit statementLimit) {
    generation = (int) ((generation + 1) & GENERATION_MASK);
    statement = driverStatement;
    limit = statementLimit;
    long deadline =
        Math.min(nowMillis + Math.min(statementLimit.millis(), MAX_DEADLINE), MAX_DEADLINE);
    long running = (deadline << GENERATION_BITS) | generation;
    state.set(running);
    return running;
  }

  /**
   * Returns how long a statement has run: its deadline in the value, less its limit.
   *
   * @param running the value {@link #start} wrote
   * @param nowMillis the time now, on the sweeper's clock
   * @return the run time, in milliseconds
   */
  long runMillis(long running, long nowMillis) {
    return nowMillis - (deadlineOf(running) - limit.millis());
  }

  /**
   * Clears the slot when the statement ends. When the sweeper claimed the statement, it first waits
   * until the cancel has ended, so that a late cancel never reaches the next statement; an
   * interrupt does not end the wait, and the interrupt flag is set again before it returns.
   *
   * @param running the value {@link #start} wrote
   * @param maxWaitMillis how long to wait for the cancel at most, in real time
   * @return whether the sweeper claimed the statement
   */
  Outcome end(long running, long maxWaitMillis) {
    if (state.compareAndSet(running, IDLE)) {
      return Outcome.NOT_CLAIMED;
    }
    long claimed = claimedValue(running);
    long done = doneValue(running);
    long current = state.get();
    if (current != claimed && current != done) {
      return Outcome.NOT_CLAIMED;
    }
    boolean ended = awaitCancel(claimed, maxWaitMillis);
    state.compareAndSet(ended ? done : claimed, IDLE);
    return ended ? Outcome.CLAIMED : Outcome.CLAIMED_WAIT_EXPIRED;
  }

  /**
   * Claims the running statement if its deadline has passed, for the sweeper.
   *
   * @param nowMillis the sweeper's clock
   * @return the claimed statement and the value claimed, or {@code null} if nothing is claimed
   */
  @Nullable Claim claimIfExpired(long nowMillis) {
    long running = state.get();
    if (running < 0 || deadlineOf(running) >= nowMillis) {
      return null;
    }
    if (!state.compareAndSet(running, claimedValue(running))) {
      return null;
    }
    // Read right after the claim: no later statement can start while the slot is claimed.
    var claimedStatement = statement;
    return claimedStatement == null ? null : new Claim(this, running, claimedStatement);
  }

  /**
   * Marks a claimed statement's cancel as ended, which lets the statement's thread return.
   *
   * @param running the value the sweeper claimed
   */
  void cancelEnded(long running) {
    state.compareAndSet(claimedValue(running), doneValue(running));
  }

  /** Waits until the claimed cancel has ended, or the wait runs out; returns whether it ended. */
  private boolean awaitCancel(long claimed, long maxWaitMillis) {
    long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMillis);
    boolean interrupted = false;
    try {
      while (state.get() == claimed) {
        if (until - System.nanoTime() <= 0) {
          return false;
        }
        // Only a claimed statement waits, so polling costs nothing on the normal path.
        LockSupport.parkNanos(POLL_NANOS);
        interrupted |= Thread.interrupted();
      }
      return true;
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** Returns the deadline part of a running statement's value. */
  private long deadlineOf(long running) {
    return running >>> GENERATION_BITS;
  }

  /** Returns the value of the sweeper's claim on a running statement. */
  private long claimedValue(long running) {
    return CLAIMED_ZERO - (running & GENERATION_MASK);
  }

  /** Returns the value of a claimed statement whose cancel has ended. */
  private long doneValue(long running) {
    return Long.MIN_VALUE + (running & GENERATION_MASK);
  }

  /**
   * A statement the sweeper claimed, with the value it claimed.
   *
   * @param slot the slot the statement runs in
   * @param running the value the sweeper claimed
   * @param statement the driver's statement to cancel
   */
  record Claim(StatementSlot slot, long running, Statement statement) {}
}
