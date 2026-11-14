package com.exepex.stratum.data;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.exepex.stratum.core.request.RequestTimeLeft;
import com.exepex.stratum.data.StatementLimitRule.LimitSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A data source with statement limits around mocks of a pool, a connection and a statement, on a
 * clock the test moves, with a sweeper that runs each cancel at once, and a web request whose time
 * left the test sets: none until then. It sits in the boundary's package, since the limits and the
 * sweeper are built there, and is shared by the tests of the limits and of the connection.
 */
final class StatementLimitsFixture {

  /** The data source bean's name. */
  static final String POOL = "ordersDataSource";

  /** The pool's query-timeout: 30 seconds. */
  static final long POOL_MILLIS = 30_000;

  /** The deadline margin: 150 milliseconds, the default. */
  static final long MARGIN_MILLIS = 150;

  /** The time left of the web request on the thread; empty for no request limit. */
  final AtomicReference<Optional<Duration>> requestLeft = new AtomicReference<>(Optional.empty());

  /** The pool, which hands out the connection. */
  final DataSource pool = mock(DataSource.class);

  /** The sweeper's clock, which only moves when a test moves it. */
  final AtomicLong now = new AtomicLong(1_000);

  /** The cancelled statements counted, as {@code pool/cause}. */
  final List<String> counted = new ArrayList<>();

  /** The pool's connection. */
  final Connection driverConnection = mock(Connection.class);

  /** The driver's statement. */
  final PreparedStatement driverStatement = mock(PreparedStatement.class);

  /** The sweeper, which starts each cancel as the test says. */
  final StatementSweeper sweeper;

  /** The data source the service sees, with the boundary and its limits. */
  final DataSource dataSource;

  /**
   * Builds the data source with the pool's limit of 30 seconds, whose sweeper runs each cancel at
   * once, on the sweeping thread.
   *
   * @param backwardCompatible whether {@code stratum.backward-compatible} is on
   * @throws SQLException never; the mocks declare it
   */
  StatementLimitsFixture(boolean backwardCompatible) throws SQLException {
    this(backwardCompatible, StatementLimits.NETWORK_MARGIN_MILLIS, Runnable::run);
  }

  /**
   * Builds the data source with the pool's limit of 30 seconds.
   *
   * @param backwardCompatible whether {@code stratum.backward-compatible} is on
   * @param cancelWaitMillis how long a cancelled statement waits for its cancel
   * @param cancelStarter starts each cancel the sweeper claims
   * @throws SQLException never; the mocks declare it
   */
  StatementLimitsFixture(
      boolean backwardCompatible, long cancelWaitMillis, Consumer<Runnable> cancelStarter)
      throws SQLException {
    this(backwardCompatible, cancelWaitMillis, cancelStarter, StatementSpans.NONE);
  }

  /**
   * Builds the data source with the pool's limit of 30 seconds, whose sweeper runs each cancel at
   * once, and whose statements get the given spans.
   *
   * @param spans the statements' spans
   * @throws SQLException never; the mocks declare it
   */
  StatementLimitsFixture(StatementSpans spans) throws SQLException {
    this(false, StatementLimits.NETWORK_MARGIN_MILLIS, Runnable::run, spans);
  }

  /**
   * Builds the data source with the pool's limit of 30 seconds.
   *
   * @param backwardCompatible whether {@code stratum.backward-compatible} is on
   * @param cancelWaitMillis how long a cancelled statement waits for its cancel
   * @param cancelStarter starts each cancel the sweeper claims
   * @param spans the statements' spans
   * @throws SQLException never; the mocks declare it
   */
  private StatementLimitsFixture(
      boolean backwardCompatible,
      long cancelWaitMillis,
      Consumer<Runnable> cancelStarter,
      StatementSpans spans)
      throws SQLException {
    sweeper = new StatementSweeper(now::get, cancelStarter);
    when(pool.getConnection()).thenReturn(driverConnection);
    when(driverConnection.prepareStatement(anyString())).thenReturn(driverStatement);
    var wrapped = new AtomicReference<DataSource>();
    var limits =
        StatementLimits.builder()
            .pool(POOL)
            .outermost(wrapped::get)
            .poolMillis(POOL_MILLIS)
            .backwardCompatible(backwardCompatible)
            .sweeper(sweeper)
            .counter((name, source) -> counted.add(name + "/" + source.getTag()))
            .wallClock(Clock.systemUTC())
            .cancelWaitMillis(cancelWaitMillis)
            .requestTimeLeft(() -> Optional.<RequestTimeLeft>of(requestLeft::get))
            .marginMillis(MARGIN_MILLIS)
            .build();
    dataSource =
        new JdbcBoundary(new JdbcWrapperClasses(), List.of())
            .withLimits(limits)
            .withSpans(spans)
            .wrap(pool);
    wrapped.set(dataSource);
  }

  /**
   * Moves the clock past a limit and runs one sweep, as happens while a statement runs too long.
   *
   * @param limitMillis the statement's limit
   */
  void passLimitAndSweep(long limitMillis) {
    now.addAndGet(limitMillis + 1);
    sweeper.sweep();
  }

  /**
   * Runs work with the pool's connection bound to a transaction whose deadline is the given time
   * away, as Spring's transaction manager binds it, and unbinds it afterwards.
   *
   * @param deadlineMillis how far away the transaction's deadline is; negative when it has passed
   * @param work the work to run in the transaction
   * @param <T> what the work returns
   * @return what the work returned
   */
  <T> T inTransaction(long deadlineMillis, Supplier<T> work) {
    var holder = new ConnectionHolder(driverConnection);
    holder.setTimeoutInMillis(deadlineMillis);
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.bindResource(dataSource, holder);
    try {
      return work.get();
    } finally {
      TransactionSynchronizationManager.unbindResource(dataSource);
      TransactionSynchronizationManager.clear();
    }
  }

  /**
   * Returns the count of cancelled statements whose limit came from a source.
   *
   * @param source what set the limit
   * @return the expected entry
   */
  String countOf(LimitSource source) {
    return POOL + "/" + source.getTag();
  }
}
