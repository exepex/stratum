package com.exepex.stratum.data;

import static com.exepex.stratum.data.StatementLimitsFixture.MARGIN_MILLIS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.exepex.stratum.core.error.FailureCauses;
import com.exepex.stratum.data.StatementLimitRule.LimitSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

@DisplayName("Statements and borrows end by the web request's time left minus the margin")
class RequestDeadlineLimitsTest {

  /** The SQL every test runs; the driver is a mock, so it is never parsed. */
  private static final String QUERY = "SELECT pg_sleep(60)";

  /** A request with one second left. */
  private static final Duration ONE_SECOND = Duration.ofSeconds(1);

  /** The statement's limit in a request with one second left: the second minus the margin. */
  private static final long LIMIT_IN_ONE_SECOND = ONE_SECOND.toMillis() - MARGIN_MILLIS;

  /** A time left the margin uses up. */
  private static final Duration UNDER_MARGIN = Duration.ofMillis(100);

  /** Times left that leave no time once the margin is taken off: the margin, less, and passed. */
  private static final List<Duration> NO_TIME_LEFT =
      List.of(Duration.ofMillis(MARGIN_MILLIS), UNDER_MARGIN, Duration.ofMillis(-5));

  /** A transaction deadline that passed one second ago. */
  private static final long PASSED_TRANSACTION_MILLIS = -1_000;

  /** A transaction deadline a minute away. */
  private static final long MINUTE_OF_TRANSACTION_MILLIS = 60_000;

  /** A user name and password the service passes to the two-argument borrow; never real. */
  private static final String CREDENTIAL = "orders";

  /** What the driver reports for a statement the sweeper cancelled. */
  private static final SQLException CANCELLED =
      new SQLException(
          "canceling statement due to user request", StatementTimeoutException.CANCELLED);

  private final StatementLimitsFixture fixture = new StatementLimitsFixture(false);

  /**
   * Builds the fixture, whose mocks declare {@code SQLException}.
   *
   * @throws SQLException never
   */
  RequestDeadlineLimitsTest() throws SQLException {}

  /** Stops the sweeper's thread, if it started one. */
  @AfterEach
  void stopSweeper() {
    fixture.sweeper.close();
  }

  /** Runs the query through the boundary and returns what it threw. */
  private Throwable failureOfQuery() {
    return catchThrowable(this::runQuery);
  }

  /** Runs the query through the boundary, closing the statement and the connection. */
  private void runQuery() throws SQLException {
    try (var connection = fixture.dataSource.getConnection();
        var statement = connection.prepareStatement(QUERY)) {
      statement.executeQuery();
    }
  }

  /** Lets the request's limit pass and the sweeper cancel, then fails as the driver does. */
  private ResultSet passLimitThenFail() throws SQLException {
    fixture.passLimitAndSweep(LIMIT_IN_ONE_SECOND);
    throw CANCELLED;
  }

  /** Lets the time of a statement with that limit pass, then returns no rows. */
  private @Nullable ResultSet passLimitThenReturn() {
    fixture.passLimitAndSweep(LIMIT_IN_ONE_SECOND);
    return null;
  }

  /** Hands out the connection after the request's time ran out during the wait. */
  private Connection handOutLate() {
    fixture.requestLeft.set(Optional.of(Duration.ofMillis(MARGIN_MILLIS - 1)));
    return fixture.driverConnection;
  }

  /**
   * Runs the query on a connection bound to a transaction whose deadline is the given time away,
   * and returns what it threw.
   */
  private Throwable failureOfQueryInTransaction(long transactionMillis) {
    return fixture.inTransaction(transactionMillis, this::failureOfQuery);
  }

  /** Borrows a connection through the boundary and returns what the borrow threw. */
  private Throwable failureOfBorrow() {
    return catchThrowable(() -> fixture.dataSource.getConnection().close());
  }

  /** Checks that a failure is SX-DATA-1003 with the given message, counted for the request. */
  private void assertRequestTimeout(Throwable failure, String message) {
    assertThat(failure).isInstanceOf(StatementTimeoutException.class).hasMessageContaining(message);
    assertThat(FailureCauses.findFailureWithErrorCode(failure))
        .hasValueSatisfying(
            coded -> assertThat(coded.getErrorCode()).isEqualTo(DataErrorCode.STATEMENT_TIMED_OUT));
    assertThat(fixture.counted).containsExactly(fixture.countOf(LimitSource.REQUEST));
  }

  @Nested
  @DisplayName("a statement")
  class Statements {

    @Test
    @DisplayName("is cancelled at the request's time left minus the margin, counted as request")
    void cancelledAtRequestLimit() throws SQLException {
      fixture.requestLeft.set(Optional.of(ONE_SECOND));
      when(fixture.driverStatement.executeQuery()).thenAnswer(invocation -> passLimitThenFail());

      assertRequestTimeout(
          failureOfQuery(), "past its limit of " + LIMIT_IN_ONE_SECOND + " ms set by request");
    }

    @ParameterizedTest(name = "time left {0}")
    @FieldSource("com.exepex.stratum.data.RequestDeadlineLimitsTest#NO_TIME_LEFT")
    @DisplayName("fails at once when the margin leaves no time, and never reaches the driver")
    void failsBeforeDriver(Duration left) throws SQLException {
      fixture.requestLeft.set(Optional.of(ONE_SECOND));
      try (var connection = fixture.dataSource.getConnection();
          var statement = connection.prepareStatement(QUERY)) {
        fixture.requestLeft.set(Optional.of(left));

        assertRequestTimeout(catchThrowable(statement::executeQuery), "set by request");
      }
      verify(fixture.driverStatement, never()).executeQuery();
    }

    @Test
    @DisplayName("has no request limit outside a request with a limit")
    void noRequestLimit() throws SQLException {
      when(fixture.driverStatement.executeQuery()).thenAnswer(invocation -> passLimitThenReturn());

      assertThat(failureOfQuery()).isNull();
      assertThat(fixture.counted).isEmpty();
    }
  }

  @Nested
  @DisplayName("a statement under both a transaction's and the request's deadline")
  class BothDeadlines {

    @Test
    @DisplayName("ends by the transaction's when it ends first, counted as transaction")
    void transactionFirst() {
      fixture.requestLeft.set(Optional.of(ONE_SECOND));

      assertThat(failureOfQueryInTransaction(PASSED_TRANSACTION_MILLIS))
          .isInstanceOf(StatementTimeoutException.class)
          .hasMessageContaining("set by transaction");
      assertThat(fixture.counted).containsExactly(fixture.countOf(LimitSource.TRANSACTION));
    }

    @Test
    @DisplayName("ends by the request's when it ends first, counted as request")
    void requestFirst() throws SQLException {
      fixture.requestLeft.set(Optional.of(ONE_SECOND));
      when(fixture.driverStatement.executeQuery()).thenAnswer(invocation -> passLimitThenFail());

      assertRequestTimeout(
          failureOfQueryInTransaction(MINUTE_OF_TRANSACTION_MILLIS),
          "past its limit of " + LIMIT_IN_ONE_SECOND + " ms set by request");
    }
  }

  @Nested
  @DisplayName("a borrow")
  class Borrows {

    @Test
    @DisplayName(
        "with a user and password also fails before the pool is asked when no time is left")
    void failsBeforePoolWithCredentials() throws SQLException {
      fixture.requestLeft.set(Optional.of(UNDER_MARGIN));

      assertRequestTimeout(
          catchThrowable(() -> fixture.dataSource.getConnection(CREDENTIAL, CREDENTIAL)),
          "no time was left to borrow a connection");
      verify(fixture.pool, never()).getConnection(anyString(), anyString());
    }

    @ParameterizedTest(name = "time left {0}")
    @FieldSource("com.exepex.stratum.data.RequestDeadlineLimitsTest#NO_TIME_LEFT")
    @DisplayName("fails before the pool is asked when the margin leaves no time")
    void failsBeforePool(Duration left) throws SQLException {
      fixture.requestLeft.set(Optional.of(left));

      assertRequestTimeout(failureOfBorrow(), "no time was left to borrow a connection");
      verify(fixture.pool, never()).getConnection();
    }

    @Test
    @DisplayName("that ran out of time while waiting returns the connection and sends no SQL")
    void returnsConnectionWhenLate() throws SQLException {
      fixture.requestLeft.set(Optional.of(ONE_SECOND));
      when(fixture.pool.getConnection()).thenAnswer(invocation -> handOutLate());

      assertRequestTimeout(failureOfBorrow(), "no time was left to use a connection");
      verify(fixture.driverConnection).close();
      verify(fixture.driverConnection, never()).prepareStatement(QUERY);
      assertThat(fixture.sweeper.size()).isZero();
    }

    @Test
    @DisplayName("goes through while time is left")
    void goesThroughInTime() {
      fixture.requestLeft.set(Optional.of(ONE_SECOND));

      assertThat(failureOfBorrow()).isNull();
      assertThat(fixture.counted).isEmpty();
    }
  }

  @Test
  @DisplayName(
      "in backward-compatible mode a request limit still applies, with the code in its message")
  void backwardCompatible() throws SQLException {
    var compatible = new StatementLimitsFixture(true);
    compatible.requestLeft.set(Optional.of(UNDER_MARGIN));
    try (var _ = compatible.sweeper) {
      var failure = catchThrowable(() -> compatible.dataSource.getConnection().close());

      assertThat(failure)
          .isInstanceOf(StatementTimeoutException.class)
          .hasMessageStartingWith(DataErrorCode.STATEMENT_TIMED_OUT.getCode());
      assertThat(FailureCauses.findFailureWithErrorCode(failure)).isEmpty();
      verify(compatible.pool, never()).getConnection();
    }
  }
}
