package com.exepex.stratum.data;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.exepex.stratum.data.StatementLimitRule.LimitSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.sql.DataSource;

/**
 * A data source with statement limits around mocks of a pool, a connection and a statement, on a
 * clock the test moves, with a sweeper that runs each cancel at once. It sits in the boundary's
 * package, since the limits and the sweeper are built there, and is shared by the tests of the
 * limits and of the connection.
 */
final class StatementLimitsFixture {

  /** The data source bean's name. */
  static final String POOL = "ordersDataSource";

  /** The pool's query-timeout: 30 seconds. */
  static final long POOL_MILLIS = 30_000;

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
    sweeper = new StatementSweeper(now::get, cancelStarter);
    var pool = mock(DataSource.class);
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
            .build();
    dataSource =
        new JdbcBoundary(new JdbcWrapperClasses(), List.of()).withLimits(limits).wrap(pool);
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
   * Returns the count of cancelled statements whose limit came from a source.
   *
   * @param source what set the limit
   * @return the expected entry
   */
  static String countOf(LimitSource source) {
    return POOL + "/" + source.getTag();
  }
}
