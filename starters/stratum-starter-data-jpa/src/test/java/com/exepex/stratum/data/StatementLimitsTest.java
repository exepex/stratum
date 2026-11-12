package com.exepex.stratum.data;

import static com.exepex.stratum.data.StatementLimitsFixture.POOL_MILLIS;
import static com.exepex.stratum.data.StatementLimitsFixture.countOf;
import static com.exepex.stratum.data.testsupport.SqlFailureTestData.UNIQUE_VIOLATION;
import static com.exepex.stratum.data.testsupport.SqlFailureTestData.postgresFailure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.exepex.stratum.core.error.FailureCauses;
import com.exepex.stratum.core.error.FailureWithErrorCode;
import com.exepex.stratum.data.StatementLimitRule.LimitSource;
import java.sql.BatchUpdateException;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@DisplayName("A statement past its limit fails with SX-DATA-1003, and no other failure changes")
class StatementLimitsTest {

  /** The SQL every test runs; the driver is a mock, so it is never parsed. */
  private static final String QUERY = "SELECT pg_sleep(60)";

  /** How long a cancelled statement waits for its cancel, in the test that never ends it. */
  private static final long SHORT_WAIT_MILLIS = 20;

  /** A transaction deadline that passed one second ago. */
  private static final long PASSED_DEADLINE_MILLIS = -1_000;

  private StatementLimitsFixture fixture = new StatementLimitsFixture(false);

  /**
   * Builds the fixture, whose mocks declare {@code SQLException}.
   *
   * @throws SQLException never
   */
  StatementLimitsTest() throws SQLException {}

  /** Makes the driver's query run past the pool's limit, then end as the given answer says. */
  private void queryRunsPastLimitThen(Answer<ResultSet> end) throws SQLException {
    when(fixture.driverStatement.executeQuery())
        .thenAnswer(
            invocation -> {
              fixture.passLimitAndSweep(POOL_MILLIS);
              return end.answer(invocation);
            });
  }

  /** Runs the query through the boundary and returns what it threw. */
  private Throwable failureOfQuery() {
    return catchThrowable(
        () -> {
          try (var connection = fixture.dataSource.getConnection();
              var statement = connection.prepareStatement(QUERY)) {
            statement.executeQuery();
          }
        });
  }

  /** Stops the sweeper's thread, if it started one. */
  @AfterEach
  void stopSweeper() {
    fixture.sweeper.close();
  }

  @Nested
  @DisplayName("outside backward-compatible mode")
  class Standard {

    @Test
    @DisplayName("the driver's cancel becomes SX-DATA-1003 with no detail, and is counted")
    void cancelledStatement() throws SQLException {
      queryRunsPastLimitThen(
          invocation -> {
            throw postgresFailure(StatementTimeoutException.CANCELLED);
          });

      var failure = failureOfQuery();

      assertThat(failure)
          .isInstanceOf(StatementTimeoutException.class)
          .hasMessageContaining("SX-DATA-1003")
          .hasMessageContaining("limit of 30000 ms set by query-timeout")
          .hasMessageContaining(StatementLimitsFixture.POOL)
          .hasMessageNotContaining(QUERY);
      assertThat(((SQLException) failure).getSQLState()).isEqualTo("57014");
      var coded = FailureCauses.findFailureWithErrorCode(failure).orElseThrow();
      assertThat(coded.getErrorCode()).isEqualTo(DataErrorCode.STATEMENT_TIMED_OUT);
      assertThat(coded.getErrorDetail()).isEmpty();
      assertThat(fixture.counted).containsExactly(countOf(LimitSource.QUERY_TIMEOUT));
    }

    @Test
    @DisplayName("a batch the cancel stopped becomes SX-DATA-1003 and keeps the batch's counts")
    void cancelledBatch() throws SQLException {
      var batchFailure =
          new BatchUpdateException(
              "aborted", StatementTimeoutException.CANCELLED, 0, new int[] {1});
      when(fixture.driverStatement.executeBatch())
          .thenAnswer(
              invocation -> {
                fixture.passLimitAndSweep(POOL_MILLIS);
                throw batchFailure;
              });

      var failure =
          catchThrowable(
              () -> {
                try (var connection = fixture.dataSource.getConnection();
                    var statement = connection.prepareStatement(QUERY)) {
                  statement.executeBatch();
                }
              });

      assertThat(failure).isInstanceOf(StatementTimeoutException.class);
      assertThat(FailureCauses.causesOf(failure))
          .anySatisfy(
              cause ->
                  assertThat(cause)
                      .isInstanceOfSatisfying(
                          BatchUpdateException.class,
                          batch -> assertThat(batch.getUpdateCounts()).containsExactly(1)));
    }

    @Test
    @DisplayName("another failure while the cancel ran stays as it is, and is not counted")
    void otherFailureDuringCancel() throws SQLException {
      queryRunsPastLimitThen(
          invocation -> {
            throw postgresFailure(UNIQUE_VIOLATION);
          });

      var failure = failureOfQuery();

      assertThat(failure).isNotInstanceOf(StatementTimeoutException.class);
      assertThat(((SQLException) failure).getSQLState()).isEqualTo(UNIQUE_VIOLATION);
      assertThat(fixture.counted).isEmpty();
    }

    @Test
    @DisplayName("a statement that returns although the cancel ran returns its result")
    void returnsDespiteCancel() throws SQLException {
      var resultSet = mock(ResultSet.class);
      queryRunsPastLimitThen(invocation -> resultSet);

      assertThat(failureOfQuery()).isNull();
      assertThat(fixture.counted).isEmpty();
    }

    @Test
    @DisplayName("a cancel by the service or the database, not by Stratum, stays as it is")
    void othersCancel() throws SQLException {
      when(fixture.driverStatement.executeQuery())
          .thenThrow(postgresFailure(StatementTimeoutException.CANCELLED));

      var failure = failureOfQuery();

      assertThat(failure).isNotInstanceOf(StatementTimeoutException.class);
      assertThat(FailureCauses.findFailureWithErrorCode(failure)).isEmpty();
      assertThat(fixture.counted).isEmpty();
    }

    @Test
    @DisplayName("a statement whose cancel has not ended when the wait runs out fails anyway")
    void cancelNotEndedInTime() throws SQLException {
      fixture.sweeper.close();
      fixture = new StatementLimitsFixture(false, SHORT_WAIT_MILLIS, cancel -> {});
      var resultSet = mock(ResultSet.class);
      queryRunsPastLimitThen(invocation -> resultSet);

      assertThat(failureOfQuery())
          .isInstanceOf(StatementTimeoutException.class)
          .hasMessageContaining("SX-DATA-1003");
      assertThat(fixture.counted).containsExactly(countOf(LimitSource.QUERY_TIMEOUT));
    }
  }

  @Nested
  @DisplayName("in a transaction whose deadline has passed")
  class PassedDeadline {

    @Test
    @DisplayName("the statement fails at once with SX-DATA-1003, and never reaches the driver")
    void failsBeforeDriver() throws SQLException {
      var holder = new ConnectionHolder(fixture.driverConnection);
      holder.setTimeoutInMillis(PASSED_DEADLINE_MILLIS);
      TransactionSynchronizationManager.initSynchronization();
      TransactionSynchronizationManager.bindResource(fixture.dataSource, holder);
      Throwable failure;
      try {
        failure = failureOfQuery();
      } finally {
        TransactionSynchronizationManager.unbindResource(fixture.dataSource);
        TransactionSynchronizationManager.clear();
      }

      assertThat(failure)
          .isInstanceOf(StatementTimeoutException.class)
          .hasMessageContaining("set by transaction");
      verify(fixture.driverStatement, never()).executeQuery();
      assertThat(fixture.counted).containsExactly(countOf(LimitSource.TRANSACTION));
    }
  }

  @Nested
  @DisplayName("in backward-compatible mode")
  class BackwardCompatible {

    @Test
    @DisplayName("a cancelled statement carries SX-DATA-1003 only in its message, not as a code")
    void codeOnlyInMessage() throws SQLException {
      fixture.sweeper.close();
      fixture = new StatementLimitsFixture(true);
      queryRunsPastLimitThen(
          invocation -> {
            throw postgresFailure(StatementTimeoutException.CANCELLED);
          });

      var failure = failureOfQuery();

      assertThat(failure)
          .isInstanceOf(StatementTimeoutException.class)
          .hasMessageContaining("SX-DATA-1003");
      assertThat(FailureCauses.causesOf(failure)).noneMatch(FailureWithErrorCode.class::isInstance);
      assertThat(fixture.counted).containsExactly(countOf(LimitSource.QUERY_TIMEOUT));
    }
  }
}
