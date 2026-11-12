package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.ErrorReport;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.data.StatementLimitRule.Limit;
import com.exepex.stratum.data.StatementLimitRule.LimitSource;
import com.exepex.stratum.data.StatementLimitRule.TimeLeft;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;
import javax.sql.DataSource;
import lombok.AccessLevel;
import lombok.Builder;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The time limits of one data source bean's statements: it decides each statement's limit, writes
 * its deadline into the connection's slot for the {@link StatementSweeper}, and turns a statement
 * the sweeper cancelled into a {@link StatementTimeoutException}. It never logs.
 */
@StratumInternal
@Builder(access = AccessLevel.PACKAGE)
public class StatementLimits {

  /** The value {@link #enter} returns for a statement with no limit. */
  static final long NO_LIMIT = -1;

  /** How much longer than a statement's limit its connection waits for the network. */
  static final long NETWORK_MARGIN_MILLIS = 30_000;

  /** The data source bean's name, which the counter and the message show. */
  private final String pool;

  /** Finds the data source bean, wrapped by any proxy, that transactions bind their holder to. */
  private final Supplier<DataSource> outermost;

  /** The pool's {@code query-timeout}, in milliseconds; 0 for none. */
  private final long poolMillis;

  /** Whether {@code stratum.backward-compatible} is on, which keeps the code out of answers. */
  private final boolean backwardCompatible;

  /** Cancels the statements past their deadline, and keeps the clock their deadlines count on. */
  private final StatementSweeper sweeper;

  /** Counts the cancelled statements. */
  private final TimeoutCounter counter;

  /** The wall clock a transaction's deadline is set on. */
  private final Clock wallClock;

  /**
   * How long a statement the sweeper cancelled waits for the cancel to end before it fails anyway:
   * the network timeout's margin, 30 seconds.
   */
  @Builder.Default private final long cancelWaitMillis = NETWORK_MARGIN_MILLIS;

  /**
   * Returns a new connection's slot, which the sweeper checks until it is released.
   *
   * @return the slot
   */
  StatementSlot openSlot() {
    var slot = new StatementSlot();
    sweeper.register(slot);
    return slot;
  }

  /**
   * Stops checking a returned connection's slot.
   *
   * @param slot the connection's slot
   */
  void release(StatementSlot slot) {
    sweeper.release(slot);
  }

  /**
   * Returns the network timeout a connection gets when it is borrowed: the pool's limit plus 30
   * seconds, or none.
   *
   * @return the network timeout in milliseconds, or 0 for none
   */
  int borrowNetworkTimeout() {
    return poolMillis > 0 ? networkTimeoutFor(poolMillis) : 0;
  }

  /**
   * Starts a statement's limit: it fails at once when no time is left, raises the connection's
   * network timeout above the limit, and writes the deadline into the connection's slot.
   *
   * @param connection the statement's connection
   * @param slot the connection's slot
   * @param driverStatement the driver's statement, which the sweeper cancels
   * @param callerSeconds the time limit the code set on the statement, in seconds; 0 for none
   * @return the slot's value, or {@link #NO_LIMIT}
   * @throws SQLException {@code SX-DATA-1003} when no time is left, or the driver's error when the
   *     network timeout cannot be raised
   */
  long enter(
      ConnectionWrapper connection,
      StatementSlot slot,
      Statement driverStatement,
      int callerSeconds)
      throws SQLException {
    var found = StatementLimitRule.limitOf(callerSeconds, deadlines(), poolMillis);
    if (found.isEmpty()) {
      return NO_LIMIT;
    }
    var limit = found.get();
    if (limit.millis() <= 0) {
      counter.count(pool, limit.source());
      throw timedOut(limit, 0, null);
    }
    connection.raiseNetworkTimeout(networkTimeoutFor(limit.millis()));
    return slot.start(driverStatement, sweeper.now(), limit);
  }

  /**
   * Ends a statement's limit: when the sweeper cancelled the statement, the failure becomes {@code
   * SX-DATA-1003}; any other end is left as it is.
   *
   * @param slot the connection's slot
   * @param running the value {@link #enter} returned
   * @param thrown what the statement threw, already clean, or {@code null}
   * @return what the statement throws, or {@code null} when it returns
   */
  @Nullable Throwable exit(StatementSlot slot, long running, @Nullable Throwable thrown) {
    if (running == NO_LIMIT) {
      return thrown;
    }
    var outcome = slot.end(running, cancelWaitMillis);
    boolean timedOut =
        outcome == StatementSlot.Outcome.CLAIMED_WAIT_EXPIRED
            || outcome == StatementSlot.Outcome.CLAIMED && isCancelled(thrown);
    if (!timedOut) {
      return thrown;
    }
    var limit = slot.getLimit();
    counter.count(pool, limit.source());
    return timedOut(limit, slot.runMillis(running, sweeper.now()), thrown);
  }

  /** Returns the network timeout for a limit: 30 seconds longer, at most the largest int. */
  private static int networkTimeoutFor(long limitMillis) {
    return (int) Math.min(limitMillis + NETWORK_MARGIN_MILLIS, Integer.MAX_VALUE);
  }

  /** Returns whether a failure is the driver's report of a cancelled statement. */
  private static boolean isCancelled(@Nullable Throwable thrown) {
    return thrown instanceof SQLException failure
        && StatementTimeoutException.CANCELLED.equals(failure.getSQLState());
  }

  /** Returns the time left before the deadline of the transaction the statement runs in. */
  private List<TimeLeft> deadlines() {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return List.of();
    }
    var deadline =
        TransactionSynchronizationManager.getResource(outermost.get())
                instanceof ConnectionHolder holder
            ? holder.getDeadline()
            : null;
    return deadline == null
        ? List.of()
        : List.of(new TimeLeft(deadline.getTime() - wallClock.millis(), LimitSource.TRANSACTION));
  }

  /**
   * Returns the exception of a statement past its limit. Outside compatible mode its cause is a
   * {@code StratumException} with {@code SX-DATA-1003} and no detail, which the web starters answer
   * with; a {@code SQLException} cannot carry the code itself, since its {@code getErrorCode()} is
   * the vendor's number.
   */
  private StatementTimeoutException timedOut(
      Limit limit, long runMillis, @Nullable Throwable driverFailure) {
    var message =
        "%s: the statement ran %d ms, past its limit of %d ms set by %s, on data source %s"
            .formatted(
                DataErrorCode.STATEMENT_TIMED_OUT.getCode(),
                runMillis,
                Math.max(limit.millis(), 0),
                limit.source().getTag(),
                pool);
    if (backwardCompatible) {
      return new StatementTimeoutException(message, driverFailure);
    }
    var report = ErrorReport.of(DataErrorCode.STATEMENT_TIMED_OUT);
    return new StatementTimeoutException(
        message,
        new StratumException(driverFailure == null ? report : report.withCause(driverFailure)));
  }
}
