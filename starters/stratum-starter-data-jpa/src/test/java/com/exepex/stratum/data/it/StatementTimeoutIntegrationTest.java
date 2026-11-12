package com.exepex.stratum.data.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.exepex.stratum.core.error.FailureCauses;
import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.DataErrorCode;
import com.exepex.stratum.data.StatementTimeoutException;
import com.exepex.stratum.data.it.boundary.BoundaryApplication;
import com.exepex.stratum.data.it.boundary.BoundaryServiceTest;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@BoundaryServiceTest
@TestPropertySource(properties = DataConstants.QUERY_TIMEOUT + "=500ms")
@DisplayName("On PostgreSQL, Stratum cancels a statement past its limit with SX-DATA-1003")
class StatementTimeoutIntegrationTest {

  /** A statement that runs for 5 seconds, ten times its limit. */
  private static final String SLOW = "SELECT pg_sleep(5)";

  /** How long the slow statements sleep, in seconds: ten times the limit. */
  private static final double SLOW_SECONDS = 5;

  /** How long a statement within the limit sleeps, in seconds. */
  private static final double QUICK_SECONDS = 0.3;

  /** Sleeps that end just before, at and just after the 500ms limit, in seconds. */
  private static final List<Double> AROUND_LIMIT_SECONDS =
      List.of(0.46, 0.48, 0.50, 0.52, 0.54, 0.56);

  /** A statement that runs as long as its parameter says, in seconds. */
  private static final String SLEEP = "SELECT pg_sleep(?)";

  /** The cause of a limit set by {@code stratum.data.query-timeout}, as the counter tags it. */
  private static final String QUERY_TIMEOUT_CAUSE = "query-timeout";

  /** The data source bean's name, which the counter's {@code pool} tag shows. */
  private static final String POOL = "dataSource";

  @Autowired private DataSource dataSource;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private TransactionTemplate transactionTemplate;

  @Autowired private EntityManager entityManager;

  @Autowired private MeterRegistry meterRegistry;

  /** Empties the tables. */
  @BeforeEach
  void emptyTables() {
    jdbcTemplate.execute(BoundaryApplication.EMPTY_TABLES);
  }

  /** Returns how many statements were cancelled because of a cause. */
  private double cancelledBy(String cause) {
    var counter =
        meterRegistry
            .find(DataConstants.TIMEOUTS_METER)
            .tags("pool", POOL, "cause", cause)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  /** Runs a query and returns whether it gave a row. */
  private static boolean returnsRow(PreparedStatement statement) throws SQLException {
    try (var resultSet = statement.executeQuery()) {
      return resultSet.next();
    }
  }

  /** Checks that a failure is Stratum's cancel, with the code and the pool's limit. */
  private static void assertStratumTimeout(Throwable failure, String source) {
    assertThat(FailureCauses.causesOf(failure))
        .anySatisfy(
            cause ->
                assertThat(cause)
                    .isInstanceOf(StatementTimeoutException.class)
                    .hasMessageContaining("SX-DATA-1003")
                    .hasMessageContaining("set by " + source));
    assertThat(FailureCauses.findFailureWithErrorCode(failure))
        .hasValueSatisfying(
            coded -> assertThat(coded.getErrorCode()).isEqualTo(DataErrorCode.STATEMENT_TIMED_OUT));
  }

  @Nested
  @DisplayName("a statement past stratum.data.query-timeout")
  class PastQueryTimeout {

    @Test
    @DisplayName("fails through JdbcTemplate as Spring's QueryTimeoutException, and is counted")
    void jdbcTemplate() {
      double before = cancelledBy(QUERY_TIMEOUT_CAUSE);

      var failure = catchThrowable(() -> jdbcTemplate.execute(SLOW));

      assertThat(failure).isInstanceOf(QueryTimeoutException.class);
      assertStratumTimeout(failure, QUERY_TIMEOUT_CAUSE);
      assertThat(cancelledBy(QUERY_TIMEOUT_CAUSE)).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("fails through Hibernate as a query timeout, with SX-DATA-1003 among its causes")
    void hibernate() {
      var failure =
          catchThrowable(
              () ->
                  transactionTemplate.executeWithoutResult(
                      status -> entityManager.createNativeQuery(SLOW).getResultList()));

      assertStratumTimeout(failure, QUERY_TIMEOUT_CAUSE);
    }

    @Test
    @DisplayName("in a batch fails, and the batch's rows are not written")
    void batch() {
      var failure =
          catchThrowable(
              () ->
                  jdbcTemplate.batchUpdate(
                      "INSERT INTO readings (id, reading) SELECT ?, 'reading' FROM pg_sleep(?)",
                      List.of(new Object[] {1, 0}, new Object[] {2, SLOW_SECONDS})));

      assertStratumTimeout(failure, QUERY_TIMEOUT_CAUSE);
      assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM readings", Integer.class))
          .isZero();
    }
  }

  @Nested
  @DisplayName("the next statement on the same connection")
  class NextStatement {

    @Test
    @DisplayName("runs with its full limit after the same prepared statement was cancelled")
    void reusedStatement() throws SQLException {
      try (var connection = dataSource.getConnection();
          var statement = connection.prepareStatement(SLEEP)) {
        statement.setDouble(1, SLOW_SECONDS);
        assertStratumTimeout(catchThrowable(statement::executeQuery), QUERY_TIMEOUT_CAUSE);

        statement.setDouble(1, QUICK_SECONDS);
        assertThat(returnsRow(statement)).isTrue();
      }
    }

    @Test
    @DisplayName("is never cancelled by a cancel that came as the statement before it ended")
    void cancelAtTheEnd() throws SQLException {
      try (var connection = dataSource.getConnection();
          var sleep = connection.prepareStatement(SLEEP);
          var next = connection.prepareStatement("SELECT 1")) {
        for (var seconds : AROUND_LIMIT_SECONDS) {
          sleep.setDouble(1, seconds);
          assertThat(catchThrowable(() -> returnsRow(sleep)))
              .satisfiesAnyOf(
                  failure -> assertThat(failure).isNull(),
                  failure -> assertStratumTimeout(failure, QUERY_TIMEOUT_CAUSE));

          assertThat(returnsRow(next)).isTrue();
        }
      }
    }
  }

  @Test
  @DisplayName("the database's own statement_timeout stays PostgreSQL's error, not SX-DATA-1003")
  void databaseTimeout() throws SQLException {
    double before = cancelledBy(QUERY_TIMEOUT_CAUSE);
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("SET statement_timeout = 100");
      try {
        var failure = catchThrowable(() -> statement.execute("SELECT pg_sleep(0.3)"));

        assertThat(failure)
            .isInstanceOf(SQLException.class)
            .isNotInstanceOf(StatementTimeoutException.class);
        assertThat(((SQLException) failure).getSQLState()).isEqualTo("57014");
        assertThat(FailureCauses.findFailureWithErrorCode(failure)).isEmpty();
      } finally {
        statement.execute("RESET statement_timeout");
      }
    }
    assertThat(cancelledBy(QUERY_TIMEOUT_CAUSE)).isEqualTo(before);
  }

  @Test
  @DisplayName("a transaction's time limit ends its statement, as SX-DATA-1003 too")
  void transactionDeadline() {
    var shortTransaction = new TransactionTemplate(transactionTemplate.getTransactionManager());
    shortTransaction.setTimeout(1);

    var failure =
        catchThrowable(
            () -> shortTransaction.executeWithoutResult(status -> jdbcTemplate.execute(SLOW)));

    assertThat(FailureCauses.causesOf(failure))
        .anyMatch(StatementTimeoutException.class::isInstance);
  }
}
