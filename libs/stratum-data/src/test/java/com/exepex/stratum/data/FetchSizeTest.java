package com.exepex.stratum.data;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.exepex.stratum.data.testsupport.MockDatabase;
import com.exepex.stratum.data.testsupport.SqlFailureTestData;
import com.exepex.stratum.test.DatabaseFailureAssertions;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("A named pool's fetch-size reaches each new statement before the code runs")
class FetchSizeTest {

  /** The generated wrapper classes, shared by every test. */
  private static final JdbcWrapperClasses CLASSES = new JdbcWrapperClasses();

  /** The pool's fetch size. */
  private static final int POOL_ROWS = 500;

  /** The fetch size the code sets itself. */
  private static final int CODE_ROWS = 10;

  /** A query. */
  private static final String QUERY = "SELECT id FROM orders";

  private final MockDatabase database = new MockDatabase();

  /**
   * Sets up the database's mocks.
   *
   * @throws SQLException never; the mocks declare it
   */
  FetchSizeTest() throws SQLException {}

  /** Returns the pool wrapped by a boundary with the given fetch size. */
  private DataSource wrappedWith(Integer rows) {
    return new JdbcBoundary(CLASSES, List.of()).withFetchSize(rows).wrap(database.pool);
  }

  @Test
  @DisplayName("plain, prepared and callable statements get the pool's fetch size")
  void setsFetchSize() throws SQLException {
    try (var connection = wrappedWith(POOL_ROWS).getConnection()) {
      connection.createStatement();
      connection.prepareStatement(QUERY);
      connection.prepareCall(QUERY);
    }

    verify(database.statement).setFetchSize(POOL_ROWS);
    verify(database.prepared).setFetchSize(POOL_ROWS);
    verify(database.callable).setFetchSize(POOL_ROWS);
  }

  @Test
  @DisplayName("a fetch size the code sets afterwards wins")
  void codeWins() throws SQLException {
    try (var connection = wrappedWith(POOL_ROWS).getConnection()) {
      connection.prepareStatement(QUERY).setFetchSize(CODE_ROWS);
    }

    var calls = inOrder(database.prepared);
    calls.verify(database.prepared).setFetchSize(POOL_ROWS);
    calls.verify(database.prepared).setFetchSize(CODE_ROWS);
  }

  @Test
  @DisplayName("with no fetch-size, the driver gets no call")
  void noCallWhenUnset() throws SQLException {
    try (var connection = wrappedWith(null).getConnection()) {
      connection.createStatement();
      connection.prepareStatement(QUERY);
    }

    verify(database.statement, never()).setFetchSize(anyInt());
    verify(database.prepared, never()).setFetchSize(anyInt());
  }

  @Test
  @DisplayName("a driver error while setting it is cleaned of data values")
  void cleansDriverError() throws SQLException {
    doThrow(SqlFailureTestData.postgresFailure("22023"))
        .when(database.prepared)
        .setFetchSize(POOL_ROWS);

    try (var connection = wrappedWith(POOL_ROWS).getConnection()) {
      assertThatThrownBy(() -> connection.prepareStatement(QUERY))
          .isInstanceOf(SQLException.class)
          .hasMessageNotContaining(DatabaseFailureAssertions.PERSONAL_VALUE);
    }
  }

  @Test
  @DisplayName("a statement whose fetch size the driver refuses is closed, as no code can close it")
  void closesRefusedStatement() throws SQLException {
    doThrow(SqlFailureTestData.postgresFailure("22023"))
        .when(database.prepared)
        .setFetchSize(POOL_ROWS);

    try (var connection = wrappedWith(POOL_ROWS).getConnection()) {
      assertThatThrownBy(() -> connection.prepareStatement(QUERY)).isInstanceOf(SQLException.class);
    }

    verify(database.prepared).close();
  }
}
