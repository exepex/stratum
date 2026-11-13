package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import java.sql.SQLException;
import java.sql.Statement;
import org.jspecify.annotations.Nullable;

/**
 * The base of the boundary's statement wrappers. On a data source with statement limits, the time
 * limit the code sets with {@code setQueryTimeout} is kept here and never reaches the driver, whose
 * own timer costs more per statement: the {@link StatementSweeper} enforces it instead, to the
 * millisecond rather than the whole second. Its subclasses are generated at startup.
 */
@StratumInternal
public abstract class StatementWrapper extends JdbcWrapper {

  /** The SQLState of an invalid argument, as the driver reports a negative time limit. */
  private static final String INVALID_ARGUMENT = "22023";

  /** The time limit the code set on the statement, in seconds; 0 for none. */
  private int callerSeconds;

  /**
   * Wraps a statement.
   *
   * @param delegate the driver's statement
   * @param boundary the boundary of its data source
   * @param parent the wrapper that returned it, normally its connection
   */
  protected StatementWrapper(Object delegate, JdbcBoundary boundary, @Nullable JdbcWrapper parent) {
    super(delegate, boundary, parent);
  }

  /**
   * Keeps the time limit the code sets, in seconds, or passes it to the driver on a data source
   * with no statement limits.
   *
   * @param seconds the time limit; 0 for none
   * @throws SQLException if it is negative, or the clean copy of the driver's error
   */
  public void setQueryTimeout(int seconds) throws SQLException {
    if (limits() == null) {
      try {
        ((Statement) delegate).setQueryTimeout(seconds);
      } catch (SQLException failure) {
        throw boundary.cleaned(failure);
      }
      return;
    }
    if (seconds < 0) {
      throw new SQLException("The query timeout must be 0 or more seconds", INVALID_ARGUMENT);
    }
    callerSeconds = seconds;
  }

  /**
   * Returns the time limit the code set, in seconds.
   *
   * @return the time limit; 0 for none
   * @throws SQLException the clean copy of the driver's error, on a data source with no statement
   *     limits
   */
  public int getQueryTimeout() throws SQLException {
    if (limits() != null) {
      return callerSeconds;
    }
    try {
      return ((Statement) delegate).getQueryTimeout();
    } catch (SQLException failure) {
      throw boundary.cleaned(failure);
    }
  }

  /**
   * Starts the limit of an execute, for the generated code.
   *
   * @return the slot's value, or {@link StatementLimits#NO_LIMIT}
   * @throws SQLException {@code SX-DATA-1003} when no time is left
   */
  long enterLimit() throws SQLException {
    var limits = limits();
    if (limits == null || !(parent instanceof ConnectionWrapper connection)) {
      return StatementLimits.NO_LIMIT;
    }
    var slot = connection.getSlot();
    return slot == null
        ? StatementLimits.NO_LIMIT
        : limits.enter(connection, slot, (Statement) delegate, callerSeconds);
  }

  /**
   * Ends the limit of an execute, for the generated code.
   *
   * @param running the value {@link #enterLimit} returned
   * @param thrown what the execute threw, already clean, or {@code null}
   * @return what the execute throws, or {@code null} when it returns
   */
  @Nullable Throwable exitLimit(long running, @Nullable Throwable thrown) {
    var limits = limits();
    if (running == StatementLimits.NO_LIMIT
        || limits == null
        || !(parent instanceof ConnectionWrapper connection)
        || connection.getSlot() == null) {
      return thrown;
    }
    return limits.exit(connection.getSlot(), running, thrown);
  }

  /** Returns the limits of the statement's data source, or {@code null} when it has none. */
  private @Nullable StatementLimits limits() {
    return boundary.getLimits();
  }
}
