package com.exepex.stratum.data;

import static com.exepex.stratum.data.testsupport.SqlFailureTestData.CONNECTION_FAILURE;
import static com.exepex.stratum.data.testsupport.SqlFailureTestData.postgresFailure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.concurrent.Executor;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName(
    "A connection with statement limits keeps the code's limit and a network timeout above it")
class LimitedConnectionTest {

  /** The SQL every test prepares; the driver is a mock, so it is never parsed. */
  private static final String QUERY = "SELECT 1";

  /** A limit the code sets, in seconds. */
  private static final int CALLER_SECONDS = 5;

  /** A limit the code sets that is longer than the pool's, in seconds. */
  private static final int LONG_CALLER_SECONDS = 120;

  /** The network timeout for that longer limit: 120 seconds plus 30, in milliseconds. */
  private static final int LONG_NETWORK_TIMEOUT = 150_000;

  /** The network timeout a borrow sets: the pool's 30 seconds plus 30. */
  private static final int BORROW_NETWORK_TIMEOUT = 60_000;

  private final StatementLimitsFixture fixture = new StatementLimitsFixture(false);

  /**
   * Builds the fixture, whose mocks declare {@code SQLException}.
   *
   * @throws SQLException never
   */
  LimitedConnectionTest() throws SQLException {}

  /** Stops the sweeper's thread, if it started one. */
  @AfterEach
  void stopSweeper() {
    fixture.sweeper.close();
  }

  @Nested
  @DisplayName("the time limit the code sets on a statement")
  class CallerLimit {

    @Test
    @DisplayName("is kept by the boundary and never reaches the driver")
    void keptNotForwarded() throws SQLException {
      try (var connection = fixture.dataSource.getConnection();
          var statement = connection.prepareStatement(QUERY)) {
        statement.setQueryTimeout(CALLER_SECONDS);

        assertThat(statement.getQueryTimeout()).isEqualTo(CALLER_SECONDS);
      }
      verify(fixture.driverStatement, never()).setQueryTimeout(anyInt());
    }

    @Test
    @DisplayName("is refused when negative, as the driver does, and nothing is kept")
    void negativeRefused() throws SQLException {
      try (var connection = fixture.dataSource.getConnection();
          var statement = connection.prepareStatement(QUERY)) {
        var refusal = catchThrowableOfType(SQLException.class, () -> statement.setQueryTimeout(-1));

        assertThat(refusal.getSQLState()).isEqualTo("22023");
        assertThat(statement.getQueryTimeout()).isZero();
      }
    }

    @Test
    @DisplayName("at its largest raises the network timeout to the largest int, without overflow")
    void largestLimit() throws SQLException {
      try (var connection = fixture.dataSource.getConnection();
          var statement = connection.prepareStatement(QUERY)) {
        statement.setQueryTimeout(Integer.MAX_VALUE);
        statement.executeQuery();
      }
      verify(fixture.driverConnection).setNetworkTimeout(any(), eq(Integer.MAX_VALUE));
    }
  }

  @Nested
  @DisplayName("the network timeout")
  class NetworkTimeout {

    @Test
    @DisplayName("is the pool's limit plus 30 seconds from the borrow on")
    void setOnBorrow() throws SQLException {
      try (var connection = fixture.dataSource.getConnection()) {
        assertThat(connection).isNotNull();
        verify(fixture.driverConnection).setNetworkTimeout(any(), eq(BORROW_NETWORK_TIMEOUT));
      }
    }

    @Test
    @DisplayName(
        "is raised above a longer limit, never lowered, and not set again when high enough")
    void raisedNeverLowered() throws SQLException {
      try (var connection = fixture.dataSource.getConnection();
          var statement = connection.prepareStatement(QUERY)) {
        statement.setQueryTimeout(LONG_CALLER_SECONDS);
        statement.executeQuery();
        statement.setQueryTimeout(1);
        statement.executeQuery();
        statement.setQueryTimeout(0);
        statement.executeQuery();
      }
      verify(fixture.driverConnection).setNetworkTimeout(any(), eq(LONG_NETWORK_TIMEOUT));
      verify(fixture.driverConnection, times(2)).setNetworkTimeout(any(), anyInt());
    }

    @Test
    @DisplayName("is set once only on a driver that does not support it")
    void unsupportedDriver() throws SQLException {
      doThrow(new SQLFeatureNotSupportedException("no network timeout"))
          .when(fixture.driverConnection)
          .setNetworkTimeout(any(), anyInt());

      try (var connection = fixture.dataSource.getConnection();
          var statement = connection.prepareStatement(QUERY)) {
        statement.setQueryTimeout(LONG_CALLER_SECONDS);
        statement.executeQuery();
      }
      verify(fixture.driverConnection).setNetworkTimeout(any(), anyInt());
    }

    @Test
    @DisplayName("that fails on borrow returns the connection and fails the borrow")
    void failedOnBorrow() throws SQLException {
      doThrow(postgresFailure(CONNECTION_FAILURE))
          .when(fixture.driverConnection)
          .setNetworkTimeout(any(), anyInt());

      var failure = catchThrowableOfType(SQLException.class, fixture.dataSource::getConnection);

      assertThat(failure.getSQLState()).isEqualTo(CONNECTION_FAILURE);
      verify(fixture.driverConnection).close();
      assertThat(fixture.sweeper.size()).isZero();
    }
  }

  @Nested
  @DisplayName("the connection's slot")
  class Slot {

    @Test
    @DisplayName("is checked from the borrow until the connection is returned")
    void borrowToReturn() throws SQLException {
      try (var connection = fixture.dataSource.getConnection()) {
        assertThat(connection).isNotNull();
        assertThat(fixture.sweeper.size()).isOne();
      }

      assertThat(fixture.sweeper.size()).isZero();
    }

    @Test
    @DisplayName("is released when a broken connection is returned and the pool's close fails")
    void brokenConnectionReturned() throws SQLException {
      doThrow(postgresFailure(CONNECTION_FAILURE)).when(fixture.driverConnection).close();
      var failure =
          catchThrowableOfType(
              SQLException.class,
              () -> {
                try (var connection = fixture.dataSource.getConnection()) {
                  assertThat(connection).isNotNull();
                }
              });

      assertThat(failure.getSQLState()).isEqualTo(CONNECTION_FAILURE);
      assertThat(fixture.sweeper.size()).isZero();
    }

    @Test
    @DisplayName("is released when the connection is aborted")
    void aborted() throws SQLException {
      try (var connection = fixture.dataSource.getConnection()) {
        connection.abort(Runnable::run);

        verify(fixture.driverConnection).abort(any(Executor.class));
        assertThat(fixture.sweeper.size()).isZero();
      }
    }
  }

  @Test
  @DisplayName(
      "without statement limits, as for Flyway's own connection, the driver keeps the limit")
  void withoutLimits() throws SQLException {
    var pool = mock(DataSource.class);
    var driverConnection = mock(Connection.class);
    var driverStatement = mock(PreparedStatement.class);
    when(pool.getConnection()).thenReturn(driverConnection);
    when(driverConnection.prepareStatement(anyString())).thenReturn(driverStatement);
    var dataSource = new JdbcBoundary(new JdbcWrapperClasses(), List.of()).wrap(pool);

    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement(QUERY)) {
      statement.setQueryTimeout(CALLER_SECONDS);
      statement.executeQuery();
    }

    verify(driverStatement).setQueryTimeout(CALLER_SECONDS);
    verify(driverConnection, never()).setNetworkTimeout(any(), anyInt());
  }
}
