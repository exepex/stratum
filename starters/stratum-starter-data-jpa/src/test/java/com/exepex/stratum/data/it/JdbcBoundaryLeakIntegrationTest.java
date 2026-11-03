package com.exepex.stratum.data.it;

import static com.exepex.stratum.data.testsupport.DataAssertions.assertChainCarriesNo;
import static com.exepex.stratum.data.testsupport.DataAssertions.assertServiceSeesNo;
import static com.exepex.stratum.data.testsupport.DataAssertions.sqlExceptionIn;
import static com.exepex.stratum.data.testsupport.SqlFailureTestData.PERSONAL_VALUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.exepex.stratum.data.it.boundary.BoundaryApplication;
import com.exepex.stratum.data.it.boundary.BoundaryServiceTest;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.hibernate.dialect.PostgreSQLDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.transaction.support.TransactionTemplate;

@BoundaryServiceTest
@DisplayName("No value from a PostgreSQL error reaches the log or the exception the service sees")
class JdbcBoundaryLeakIntegrationTest {

  /** Rows read in each fetch of the cursor, so that a later fetch happens inside next(). */
  private static final int FETCH_SIZE = 2;

  @Autowired private DataSource dataSource;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private TransactionTemplate transactionTemplate;

  /** Empties the tables, so that each test starts from no rows. */
  @BeforeEach
  void emptyTables() {
    jdbcTemplate.execute(BoundaryApplication.EMPTY_TABLES);
  }

  @Test
  @DisplayName("a bad value met while next() fetches the cursor's second batch (22P02)")
  void cleansErrorDuringCursorFetch(CapturedOutput output) {
    jdbcTemplate.update(
        "INSERT INTO readings VALUES (1, '1'), (2, '2'), (3, '3'), (4, ?)", PERSONAL_VALUE);
    var rowsRead = new AtomicInteger();
    // No ORDER BY: a sort would compute every row before the first fetch, while a scan of this
    // small table returns the rows in the order they were written.
    var cursor = new JdbcTemplate(dataSource);
    cursor.setFetchSize(FETCH_SIZE);

    var failure =
        catchThrowable(
            () ->
                transactionTemplate.executeWithoutResult(
                    status ->
                        cursor.query(
                            "SELECT CAST(reading AS INTEGER) FROM readings",
                            (RowCallbackHandler) row -> rowsRead.incrementAndGet())));

    assertThat(rowsRead).hasValue(FETCH_SIZE);
    assertThat(sqlExceptionIn(failure).getSQLState()).isEqualTo("22P02");
    assertServiceSeesNo(failure, output, PERSONAL_VALUE);
  }

  @Test
  @DisplayName("a deferred foreign key violated at commit (23503) keeps only the constraint name")
  void cleansErrorAtCommit(CapturedOutput output) {
    var failure =
        catchThrowable(
            () ->
                transactionTemplate.executeWithoutResult(
                    status ->
                        jdbcTemplate.update("INSERT INTO invoices VALUES (1, ?)", PERSONAL_VALUE)));

    assertServiceSeesNo(failure, output, PERSONAL_VALUE);
    assertThat(constraintNameIn(failure)).isEqualTo("invoices_customer_fkey");
  }

  @Test
  @DisplayName("a text read with getInt (\"Bad value for type\") does not show the text")
  void cleansBadValueFromGetter(CapturedOutput output) {
    var failure =
        catchThrowable(
            () ->
                jdbcTemplate.queryForObject(
                    "SELECT CAST(? AS TEXT)", Integer.class, PERSONAL_VALUE));

    assertServiceSeesNo(failure, output, PERSONAL_VALUE);
  }

  @Test
  @DisplayName("a duplicate email (23505) gives DuplicateKeyException, naming only the constraint")
  void cleansDuplicateKey(CapturedOutput output) {
    jdbcTemplate.update("INSERT INTO customers VALUES (1, ?)", PERSONAL_VALUE);

    var failure =
        catchThrowable(
            () -> jdbcTemplate.update("INSERT INTO customers VALUES (2, ?)", PERSONAL_VALUE));

    assertThat(failure)
        .isInstanceOf(DuplicateKeyException.class)
        .hasMessageContaining("customers_email_key");
    assertServiceSeesNo(failure, output, PERSONAL_VALUE);
  }

  @Test
  @DisplayName("a NOTICE the server raises with a value comes back as a clean warning")
  void cleansWarning() throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("DO $$ BEGIN RAISE NOTICE '%', '" + PERSONAL_VALUE + "'; END $$");

      var warning = statement.getWarnings();

      assertThat((Throwable) warning).isNotNull();
      assertChainCarriesNo(warning, PERSONAL_VALUE);
    }
  }

  /** Returns the constraint name Hibernate's PostgreSQL dialect reads from a failure's chain. */
  private String constraintNameIn(Throwable failure) {
    return new PostgreSQLDialect()
        .getViolatedConstraintNameExtractor()
        .extractConstraintName(sqlExceptionIn(failure));
  }
}
