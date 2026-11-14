package com.exepex.stratum.data;

import static com.exepex.stratum.data.testsupport.SqlFailureTestData.UNIQUE_VIOLATION;
import static com.exepex.stratum.data.testsupport.SqlFailureTestData.postgresCleaner;
import static com.exepex.stratum.data.testsupport.SqlFailureTestData.postgresFailure;
import static com.exepex.stratum.data.testsupport.SqlFailureTestData.postgresReaders;
import static com.exepex.stratum.test.DatabaseFailureAssertions.PERSONAL_VALUE;
import static com.exepex.stratum.test.QuerySpanAssertions.NAMESPACE;
import static com.exepex.stratum.test.QuerySpanAssertions.SYSTEM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.exepex.stratum.data.testsupport.MockDatabase;
import com.exepex.stratum.data.testsupport.TracedRequests;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.trace.StatusCode;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

@DisplayName("Statement spans appear only in sampled requests and never change the JDBC call")
class StatementSpansSwitchesTest {

  /** The generated wrapper classes, shared by every test. */
  private static final JdbcWrapperClasses CLASSES = new JdbcWrapperClasses();

  /** The query every test runs. */
  private static final String QUERY = "SELECT id FROM customers WHERE email = ?";

  private final MockDatabase database = new MockDatabase();

  private TracedRequests traced = TracedRequests.sampled();

  /**
   * Builds the mock database.
   *
   * @throws SQLException never; the mocks declare it
   */
  StatementSpansSwitchesTest() throws SQLException {}

  /** Closes the tracer. */
  @AfterEach
  void close() {
    traced.close();
  }

  /** Returns the data source with spans that find their tracer through the provider. */
  private DataSource dataSource(ObjectProvider<Tracer> tracers) {
    return new JdbcBoundary(CLASSES, postgresReaders())
        .withSpans(new TracerStatementSpans(tracers))
        .wrap(database.pool);
  }

  /** Returns a provider of the given tracer, as a bean factory gives it. */
  private static ObjectProvider<Tracer> providerOf(Tracer tracer) {
    var beans = new StaticListableBeanFactory();
    beans.addBean("tracer", tracer);
    return beans.getBeanProvider(Tracer.class);
  }

  /** Runs the query once through the data source. */
  private static void runQuery(DataSource dataSource) throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement(QUERY)) {
      statement.executeQuery();
    }
  }

  /** Checks that no span work was done: the database's name and product were never read. */
  private void assertNoSpanWork() throws SQLException {
    verify(database.connection, never()).getCatalog();
    verify(database.connection, never()).getMetaData();
  }

  @Nested
  @DisplayName("no span, and the statement runs as before")
  class NoSpan {

    @Test
    @DisplayName("when the request is not sampled")
    void unsampled() throws Exception {
      traced.close();
      traced = TracedRequests.unsampled();

      traced.inRequest(() -> runQuery(dataSource(traced.tracers())));

      assertThat(traced.allSpans()).isEmpty();
      verify(database.prepared).executeQuery();
      assertNoSpanWork();
    }

    @Test
    @DisplayName("when the statement runs outside a request")
    void untraced() throws SQLException {
      runQuery(dataSource(traced.tracers()));

      assertThat(traced.allSpans()).isEmpty();
      verify(database.prepared).executeQuery();
    }

    @Test
    @DisplayName("with Spring Boot's no-op tracer, whose span has no sampled flag")
    void noopTracer() throws Exception {
      traced.inRequest(() -> runQuery(dataSource(providerOf(Tracer.NOOP))));

      assertThat(traced.statementSpans()).isEmpty();
      verify(database.prepared).executeQuery();
      assertNoSpanWork();
    }

    @Test
    @DisplayName("with no tracer bean")
    void noTracer() throws Exception {
      traced.inRequest(
          () ->
              runQuery(dataSource(new StaticListableBeanFactory().getBeanProvider(Tracer.class))));

      assertThat(traced.statementSpans()).isEmpty();
      verify(database.prepared).executeQuery();
    }

    @Test
    @DisplayName("when the tracer throws")
    void failingTracer() throws SQLException {
      var tracer = mock(Tracer.class);
      when(tracer.currentSpan()).thenThrow(new IllegalStateException("tracer failed"));

      runQuery(dataSource(providerOf(tracer)));

      verify(database.prepared).executeQuery();
    }
  }

  @Nested
  @DisplayName("a span still ends, with the clean error only")
  class Failures {

    @Test
    @DisplayName("when the driver fails: the span records the boundary's clean copy")
    void driverFails() throws Exception {
      when(database.prepared.executeQuery()).thenThrow(postgresFailure(UNIQUE_VIOLATION));

      traced.inRequest(() -> catchThrowable(() -> runQuery(dataSource(traced.tracers()))));

      var span = traced.statementSpans().getFirst();
      assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
      assertThat(span.getEvents().toString())
          .doesNotContain(PERSONAL_VALUE)
          .contains(postgresCleaner().cleaned(postgresFailure(UNIQUE_VIOLATION)).getMessage());
    }

    @Test
    @DisplayName("when the database name cannot be read: the span has no namespace")
    void catalogFails() throws Exception {
      when(database.connection.getCatalog()).thenThrow(new SQLException("aborted", "25P02"));

      traced.inRequest(() -> runQuery(dataSource(traced.tracers())));

      assertThat(traced.statementSpans().getFirst().getAttributes().get(NAMESPACE)).isNull();
    }

    @Test
    @DisplayName("when the product cannot be read: the span says other_sql")
    void metadataFails() throws Exception {
      when(database.connection.getMetaData()).thenThrow(new SQLException("closed", "08003"));

      traced.inRequest(() -> runQuery(dataSource(traced.tracers())));

      assertThat(traced.statementSpans().getFirst().getAttributes().get(SYSTEM))
          .isEqualTo("other_sql");
    }

    @Test
    @DisplayName("when the time limit refuses the execute: the span records SX-DATA-1003")
    void limitRefuses() throws Exception {
      var fixture = new StatementLimitsFixture(new TracerStatementSpans(traced.tracers()));

      traced.inRequest(() -> runPastDeadline(fixture));

      var span = traced.statementSpans().getFirst();
      assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
      assertThat(span.getEvents().toString()).contains("SX-DATA-1003");
    }

    @Test
    @DisplayName("when the span fails to end, or is not a span: the JDBC call never sees it")
    void endNeverThrows() {
      var span = mock(Span.class);
      doThrow(new IllegalStateException("exporter failed")).when(span).end();
      var spans = new TracerStatementSpans(traced.tracers());

      assertThatCode(() -> spans.end(span, null)).doesNotThrowAnyException();
      assertThatCode(() -> spans.end("not a span", null)).doesNotThrowAnyException();
    }

    /** Prepares the query, then runs it with no time left in the request. */
    private void runPastDeadline(StatementLimitsFixture fixture) throws SQLException {
      try (var connection = fixture.dataSource.getConnection();
          var statement = connection.prepareStatement(QUERY)) {
        fixture.requestLeft.set(Optional.of(Duration.ofMillis(1)));
        assertThat(catchThrowable(statement::executeQuery))
            .isInstanceOf(StatementTimeoutException.class);
      }
    }
  }

  @Test
  @DisplayName("a request on a virtual thread gets its statement's span as a child")
  void virtualThread() throws Exception {
    var dataSource = dataSource(traced.tracers());
    var thread = Thread.ofVirtual().start(() -> runRequestUnchecked(dataSource));
    thread.join();

    var span = traced.statementSpans().getFirst();
    var request =
        traced.allSpans().stream()
            .filter(ended -> TracedRequests.REQUEST.equals(ended.getName()))
            .findFirst()
            .orElseThrow();
    assertThat(span.getParentSpanId()).isEqualTo(request.getSpanId());
  }

  /** Runs the query inside a request, on the calling thread. */
  private void runRequestUnchecked(DataSource dataSource) {
    try {
      traced.inRequest(() -> runQuery(dataSource));
    } catch (SQLException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
