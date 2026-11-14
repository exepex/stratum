package com.exepex.stratum.data;

import static com.exepex.stratum.data.testsupport.SqlFailureTestData.postgresReaders;
import static com.exepex.stratum.test.DatabaseFailureAssertions.PERSONAL_VALUE;
import static com.exepex.stratum.test.QuerySpanAssertions.NAMESPACE;
import static com.exepex.stratum.test.QuerySpanAssertions.SYSTEM;
import static com.exepex.stratum.test.QuerySpanAssertions.assertStatementSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.exepex.stratum.data.testsupport.MockDatabase;
import com.exepex.stratum.data.testsupport.TracedRequests;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;

@DisplayName("In a sampled request, each statement's execute gets one client span with no SQL text")
class StatementSpansTest {

  /** The generated wrapper classes, shared by every test. */
  private static final JdbcWrapperClasses CLASSES = new JdbcWrapperClasses();

  /** A query that holds the personal value as a literal. */
  private static final String LITERAL_QUERY =
      "SELECT id FROM customers WHERE email = '" + PERSONAL_VALUE + "'";

  /** The operation of a query. */
  private static final String SELECT = "SELECT";

  /** The operation of an insert. */
  private static final String INSERT = "INSERT";

  /** The operation of SQL that starts with no known keyword. */
  private static final String UNKNOWN = QueryOperation.UNKNOWN.name();

  /** How many times the repeated statement runs. */
  private static final int EXECUTES = 3;

  /** A prepared query, whose value is bound. */
  private static final String PREPARED_QUERY = "SELECT id FROM customers WHERE email = ?";

  /** An insert that holds the personal value as a literal. */
  private static final String LITERAL_INSERT =
      "INSERT INTO customers (email) VALUES ('" + PERSONAL_VALUE + "')";

  private final TracedRequests traced = TracedRequests.sampled();

  private final MockDatabase database = new MockDatabase();

  private final DataSource dataSource =
      new JdbcBoundary(CLASSES, postgresReaders())
          .withSpans(new TracerStatementSpans(traced.tracers()))
          .wrap(database.pool);

  /**
   * Builds the mock database.
   *
   * @throws SQLException never; the mocks declare it
   */
  StatementSpansTest() throws SQLException {}

  /** Closes the tracer. */
  @AfterEach
  void close() {
    traced.close();
  }

  /** Returns the only statement span that ended. */
  private SpanData onlySpan() {
    assertThat(traced.statementSpans()).hasSize(1);
    return traced.statementSpans().getFirst();
  }

  @Nested
  @DisplayName("each kind of statement")
  class Kinds {

    @Test
    @DisplayName("a prepared query gets a SELECT span, a child of the request, with the database")
    void preparedQuery() throws Exception {
      traced.inRequest(this::runPrepared);

      var span = onlySpan();
      assertStatementSpan(span, SELECT, PERSONAL_VALUE);
      assertThat(span.getAttributes().get(SYSTEM)).isEqualTo("postgresql");
      assertThat(span.getAttributes().get(NAMESPACE)).isEqualTo(MockDatabase.DATABASE);
      assertThat(span.getParentSpanId()).isEqualTo(requestSpan().getSpanId());
    }

    @Test
    @DisplayName("a plain query and a plain update get the operation their own SQL starts with")
    void plainStatements() throws Exception {
      traced.inRequest(this::runPlain);

      assertThat(traced.statementSpans())
          .extracting(SpanData::getName)
          .containsExactly(SELECT, "UPDATE");
      traced
          .statementSpans()
          .forEach(
              span -> assertThat(span.getAttributes().toString()).doesNotContain(PERSONAL_VALUE));
    }

    @Test
    @DisplayName("a callable statement gets a CALL span")
    void callable() throws Exception {
      traced.inRequest(this::runCallable);

      assertStatementSpan(onlySpan(), "CALL", PERSONAL_VALUE);
    }

    @Test
    @DisplayName("a prepared statement run three times gets three spans")
    void eachExecute() throws Exception {
      traced.inRequest(this::runPreparedThreeTimes);

      assertThat(traced.statementSpans()).hasSize(EXECUTES);
    }

    /** Runs the prepared query once. */
    private void runPrepared() throws SQLException {
      try (var connection = dataSource.getConnection();
          var statement = connection.prepareStatement(PREPARED_QUERY)) {
        statement.setString(1, PERSONAL_VALUE);
        statement.executeQuery();
      }
    }

    /** Runs the prepared query three times. */
    private void runPreparedThreeTimes() throws SQLException {
      try (var connection = dataSource.getConnection();
          var statement = connection.prepareStatement(PREPARED_QUERY)) {
        statement.executeQuery();
        statement.executeQuery();
        statement.executeQuery();
      }
    }

    /** Runs a plain query and a plain update on one statement. */
    private void runPlain() throws SQLException {
      try (var connection = dataSource.getConnection();
          var statement = connection.createStatement()) {
        statement.executeQuery(LITERAL_QUERY);
        statement.executeUpdate("UPDATE customers SET email = '" + PERSONAL_VALUE + "'");
      }
    }

    /** Runs a stored procedure. */
    private void runCallable() throws SQLException {
      try (var connection = dataSource.getConnection();
          var statement = connection.prepareCall("CALL archive_customer(?)")) {
        statement.setString(1, PERSONAL_VALUE);
        statement.execute();
      }
    }
  }

  @Nested
  @DisplayName("a batch")
  class Batches {

    @Test
    @DisplayName("of plain inserts gets one INSERT span")
    void sameOperation() throws Exception {
      traced.inRequest(
          () -> runBatch(LITERAL_INSERT, "INSERT INTO customers (email) VALUES ('b')"));

      assertStatementSpan(onlySpan(), INSERT, PERSONAL_VALUE);
    }

    @Test
    @DisplayName("of plain statements with different operations gets one BATCH span")
    void mixedOperations() throws Exception {
      traced.inRequest(() -> runBatch(LITERAL_INSERT, "DELETE FROM customers WHERE id = 1"));

      assertStatementSpan(onlySpan(), "BATCH", PERSONAL_VALUE);
    }

    @Test
    @DisplayName("ends with its execute, so the next batch gets its own operation")
    void nextBatch() throws Exception {
      traced.inRequest(() -> runTwoBatches());

      assertThat(traced.statementSpans())
          .extracting(SpanData::getName)
          .containsExactly("BATCH", "UPDATE");
    }

    @Test
    @DisplayName("that was cleared keeps none of its SQL, so the next batch gets its own operation")
    void cleared() throws Exception {
      traced.inRequest(this::runClearedBatch);

      assertStatementSpan(onlySpan(), "UPDATE", PERSONAL_VALUE);
    }

    @Test
    @DisplayName("stays pending across a single execute, which gets its own operation")
    void pendingAcrossExecute() throws Exception {
      traced.inRequest(this::runQueryBetweenBatch);

      assertThat(traced.statementSpans())
          .extracting(SpanData::getName)
          .containsExactly(SELECT, INSERT);
    }

    @Test
    @DisplayName("of a prepared statement gets the operation it was prepared with")
    void prepared() throws Exception {
      traced.inRequest(this::runPreparedBatch);

      assertStatementSpan(onlySpan(), INSERT, PERSONAL_VALUE);
    }

    /** Adds two SQL texts to a plain batch and runs it. */
    private void runBatch(String first, String second) throws SQLException {
      try (var connection = dataSource.getConnection();
          var statement = connection.createStatement()) {
        statement.addBatch(first);
        statement.addBatch(second);
        statement.executeBatch();
      }
    }

    /** Runs a mixed batch, then a batch of updates, on one statement. */
    private void runTwoBatches() throws SQLException {
      try (var connection = dataSource.getConnection();
          var statement = connection.createStatement()) {
        statement.addBatch(LITERAL_INSERT);
        statement.addBatch("DELETE FROM customers");
        statement.executeBatch();
        statement.addBatch("UPDATE customers SET email = 'a'");
        statement.executeBatch();
      }
    }

    /** Adds an insert to a plain batch, clears it, then runs a batch of one update. */
    private void runClearedBatch() throws SQLException {
      try (var connection = dataSource.getConnection();
          var statement = connection.createStatement()) {
        statement.addBatch(LITERAL_INSERT);
        statement.clearBatch();
        statement.addBatch("UPDATE customers SET email = 'a'");
        statement.executeBatch();
      }
    }

    /** Adds an insert to a plain batch, runs a query, then runs the batch. */
    private void runQueryBetweenBatch() throws SQLException {
      try (var connection = dataSource.getConnection();
          var statement = connection.createStatement()) {
        statement.addBatch(LITERAL_INSERT);
        statement.executeQuery(LITERAL_QUERY);
        statement.executeBatch();
      }
    }

    /** Runs a prepared insert as a batch of two. */
    private void runPreparedBatch() throws SQLException {
      try (var connection = dataSource.getConnection();
          var statement = connection.prepareStatement("INSERT INTO customers (email) VALUES (?)")) {
        statement.setString(1, PERSONAL_VALUE);
        statement.addBatch();
        statement.addBatch();
        statement.executeBatch();
      }
    }
  }

  /** A personal value as an SQL literal, which no span may carry. */
  private static final String LITERAL = "'" + PERSONAL_VALUE + "'";

  /** Hostile SQL, each with the operation its span must carry; none carries the literal. */
  private static final List<Arguments> HOSTILE_SQL =
      List.of(
          arguments("/* DROP TABLE customers */ SELECT " + LITERAL, SELECT),
          arguments("-- DELETE FROM customers\nSELECT " + LITERAL, SELECT),
          arguments("/* unterminated SELECT " + LITERAL, UNKNOWN),
          arguments(LITERAL + "; DROP TABLE customers", UNKNOWN),
          arguments("SELECTED " + LITERAL, UNKNOWN),
          arguments("select " + LITERAL, SELECT),
          arguments(" \n\t INSERT INTO t VALUES (" + LITERAL + ")", INSERT),
          arguments("WITH secret AS (SELECT " + LITERAL + ") SELECT 1", "WITH"),
          arguments("{call archive(" + LITERAL + ")}", UNKNOWN),
          arguments(PERSONAL_VALUE, UNKNOWN));

  @ParameterizedTest(name = "{1} for {0}")
  @FieldSource("HOSTILE_SQL")
  @DisplayName("hostile SQL gets an allowed operation or UNKNOWN, and its span carries no value")
  void hostile(String sql, String operation) throws Exception {
    traced.inRequest(() -> runPlain(sql));

    assertStatementSpan(onlySpan(), operation, PERSONAL_VALUE);
  }

  /** Runs a plain query. */
  private void runPlain(String sql) throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.executeQuery(sql);
    }
  }

  /** Returns the request's root span. */
  private SpanData requestSpan() {
    List<SpanData> requests =
        traced.allSpans().stream()
            .filter(span -> TracedRequests.REQUEST.equals(span.getName()))
            .toList();
    assertThat(requests).hasSize(1);
    return requests.getFirst();
  }
}
