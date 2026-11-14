package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
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

  /** The SQL the statement was prepared with, or {@code null} for a plain statement. */
  private @Nullable String preparedSql;

  /** The SQL of a plain execute or the first of a plain batch, until the execute ends; or empty. */
  private String plainSql = "";

  /** {@code BATCH} when a plain batch mixes operations, until the execute ends; or empty. */
  private String batchOperation = "";

  /** The span of the execute that is running, or {@link StatementSpans#NO_SPAN}. */
  private Object span = StatementSpans.NO_SPAN;

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
    span = boundary.getSpans().start(currentSql(), batchOperation, driverConnection());
    try {
      return limitOnEnter();
    } catch (SQLException refused) {
      finishExecute(refused);
      throw refused;
    }
  }

  /** Starts the limit of an execute; see {@link #enterLimit}. */
  private long limitOnEnter() throws SQLException {
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
    var result = limitOnExit(running, thrown);
    finishExecute(result);
    return result;
  }

  /** Ends the limit of an execute; see {@link #exitLimit}. */
  private @Nullable Throwable limitOnExit(long running, @Nullable Throwable thrown) {
    var limits = limits();
    if (running == StatementLimits.NO_LIMIT
        || limits == null
        || !(parent instanceof ConnectionWrapper connection)
        || connection.getSlot() == null) {
      return thrown;
    }
    return limits.exit(connection.getSlot(), running, thrown);
  }

  /**
   * Keeps the SQL the statement was prepared with, for its spans' operation.
   *
   * @param sql the statement's SQL
   */
  void prepared(String sql) {
    preparedSql = sql;
  }

  /**
   * Keeps the SQL of a plain execute, for its span's operation.
   *
   * @param sql the SQL the execute runs
   */
  void executes(@Nullable String sql) {
    plainSql = Objects.requireNonNullElse(sql, "");
  }

  /**
   * Keeps the first SQL of a plain batch, and marks the batch {@code BATCH} when a later one starts
   * with another operation.
   *
   * @param sql the SQL added to the batch
   */
  void batches(@Nullable String sql) {
    var added = Objects.requireNonNullElse(sql, "");
    if (plainSql.isEmpty()) {
      plainSql = added;
    } else if (batchOperation.isEmpty()
        && !plainSql.equals(added)
        && !QueryOperation.of(plainSql).equals(QueryOperation.of(added))) {
      batchOperation = QueryOperation.BATCH;
    }
  }

  /** Returns the SQL the running execute runs: a plain one, or the prepared one. */
  private String currentSql() {
    if (!plainSql.isEmpty()) {
      return plainSql;
    }
    return preparedSql == null ? "" : preparedSql;
  }

  /** Returns the driver's connection behind the statement, or {@code null}. */
  private @Nullable Connection driverConnection() {
    return parent instanceof ConnectionWrapper connection ? (Connection) connection.delegate : null;
  }

  /** Ends the running execute's span, if it has one, and forgets its plain SQL. */
  private void finishExecute(@Nullable Throwable thrown) {
    plainSql = "";
    batchOperation = "";
    var running = span;
    if (running != StatementSpans.NO_SPAN) {
      span = StatementSpans.NO_SPAN;
      boundary.getSpans().end(running, thrown);
    }
  }

  /** Returns the limits of the statement's data source, or {@code null} when it has none. */
  private @Nullable StatementLimits limits() {
    return boundary.getLimits();
  }
}
