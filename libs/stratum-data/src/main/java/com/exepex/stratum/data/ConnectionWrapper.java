package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.Executor;
import lombok.AccessLevel;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

/**
 * The base of the boundary's connection wrapper. On a data source with statement limits, it keeps
 * the connection's {@link StatementSlot} from borrow to return, and the network timeout it last
 * set, so that the normal path makes no driver call. Its subclass is generated at startup.
 */
@StratumInternal
public abstract class ConnectionWrapper extends JdbcWrapper {

  /** Runs the driver's network timeout work on the calling thread, as HikariCP does. */
  private static final Executor CALLING_THREAD = Runnable::run;

  /** The network timeout of a driver that does not support one: never set again. */
  private static final int UNSUPPORTED = -2;

  /** The network timeout before the boundary or the service sets one: not known. */
  private static final int UNKNOWN = -1;

  /** The slot the sweeper checks, or {@code null} on a data source with no statement limits. */
  @Getter(AccessLevel.PACKAGE)
  private final @Nullable StatementSlot slot;

  /** The network timeout last set on the connection, in milliseconds, or one of the markers. */
  private int networkTimeoutMillis = UNKNOWN;

  /**
   * Wraps a borrowed connection and, on a data source with statement limits, registers its slot.
   *
   * @param delegate the pool's or driver's connection
   * @param boundary the boundary of its data source
   * @param parent the wrapper that returned it
   */
  protected ConnectionWrapper(
      Object delegate, JdbcBoundary boundary, @Nullable JdbcWrapper parent) {
    super(delegate, boundary, parent);
    var limits = boundary.getLimits();
    this.slot = limits == null ? null : limits.openSlot();
  }

  /**
   * Returns the connection to the pool and releases its slot, even when the pool fails.
   *
   * @throws SQLException the clean copy of the pool's error
   */
  public void close() throws SQLException {
    try {
      ((Connection) delegate).close();
    } catch (SQLException failure) {
      throw boundary.cleaned(failure);
    } finally {
      release();
    }
  }

  /**
   * Aborts the connection and releases its slot, even when the pool fails.
   *
   * @param executor runs the abort, as {@link Connection#abort} describes
   * @throws SQLException the clean copy of the pool's error
   */
  public void abort(Executor executor) throws SQLException {
    try {
      ((Connection) delegate).abort(executor);
    } catch (SQLException failure) {
      throw boundary.cleaned(failure);
    } finally {
      release();
    }
  }

  /**
   * Sets the network timeout the service asks for and remembers it.
   *
   * @param executor runs the driver's work, as {@link Connection#setNetworkTimeout} describes
   * @param milliseconds the network timeout
   * @throws SQLException the clean copy of the driver's error
   */
  public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
    try {
      ((Connection) delegate).setNetworkTimeout(executor, milliseconds);
      networkTimeoutMillis = milliseconds;
    } catch (SQLException failure) {
      throw boundary.cleaned(failure);
    }
  }

  /**
   * Gives a just borrowed connection the pool's network timeout, and fails the borrow when the web
   * request it serves ran out of time while it waited for the pool; on either failure, the
   * connection is returned before the error is thrown, so no SQL is sent.
   *
   * @throws SQLException {@code SX-DATA-1003} when no time is left, or the clean copy of the
   *     driver's error
   */
  void onBorrow() throws SQLException {
    var limits = boundary.getLimits();
    if (limits == null) {
      return;
    }
    try {
      limits.checkBorrow(true);
      raiseNetworkTimeout(limits.borrowNetworkTimeout());
    } catch (SQLException failure) {
      close();
      throw failure;
    }
  }

  /**
   * Raises the network timeout to the given value when it is lower, never lowering it, and makes no
   * driver call when it is already high enough.
   *
   * @param milliseconds the network timeout needed; 0 needs none
   * @throws SQLException the clean copy of the driver's error
   */
  void raiseNetworkTimeout(int milliseconds) throws SQLException {
    if (milliseconds <= 0
        || networkTimeoutMillis == UNSUPPORTED
        || networkTimeoutMillis == 0
        || networkTimeoutMillis >= milliseconds) {
      return;
    }
    try {
      setNetworkTimeout(CALLING_THREAD, milliseconds);
    } catch (SQLFeatureNotSupportedException _) {
      networkTimeoutMillis = UNSUPPORTED;
    }
  }

  /** Stops the sweeper checking the slot; doing it twice changes nothing. */
  private void release() {
    var limits = boundary.getLimits();
    if (limits != null && slot != null) {
      limits.release(slot);
    }
  }
}
