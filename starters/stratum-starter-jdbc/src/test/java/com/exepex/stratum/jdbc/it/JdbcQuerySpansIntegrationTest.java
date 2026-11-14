package com.exepex.stratum.jdbc.it;

import static com.exepex.stratum.test.DatabaseFailureAssertions.PERSONAL_VALUE;
import static com.exepex.stratum.test.QuerySpanAssertions.NAMESPACE;
import static com.exepex.stratum.test.QuerySpanAssertions.SYSTEM;
import static com.exepex.stratum.test.QuerySpanAssertions.assertStatementSpan;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.test.PostgresContainerConfiguration;
import com.exepex.stratum.test.TracedRequests;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(
    classes = {JdbcApplication.class, JdbcQuerySpansIntegrationTest.Spans.class},
    properties = {
      "spring.sql.init.mode=always",
      "spring.sql.init.schema-locations=classpath:jdbc-schema.sql"
    })
@Import(PostgresContainerConfiguration.class)
@DisplayName("On PostgreSQL, a traced JDBC service's statements get client spans with no SQL text")
class JdbcQuerySpansIntegrationTest {

  /** The database the Testcontainers PostgreSQL image creates. */
  private static final String DATABASE = "test";

  /** The customer the insert test adds. */
  private static final long CUSTOMER_ID = 7L;

  @Autowired private JdbcClient jdbcClient;

  @Autowired private TracedRequests traced;

  /** Forgets the spans of earlier tests and of startup. */
  @BeforeEach
  void forgetSpans() {
    traced.reset();
  }

  /** Runs work inside a sampled request's root span and returns the statement spans. */
  private List<SpanData> spansOf(TracedRequests.Work work) throws SQLException {
    traced.inRequest(work);
    return traced.statementSpans();
  }

  @Test
  @DisplayName("a query with a literal gets one SELECT span with the database and no value")
  void queryHasDatabaseAndOperation() throws SQLException {
    var spans =
        spansOf(
            () ->
                jdbcClient
                    .sql("SELECT count(*) FROM customers WHERE email = '" + PERSONAL_VALUE + "'")
                    .query(Long.class)
                    .single());

    assertThat(spans)
        .singleElement()
        .satisfies(span -> assertStatementSpan(span, "SELECT", PERSONAL_VALUE));
    assertThat(spans.getFirst().getAttributes().get(SYSTEM)).isEqualTo("postgresql");
    assertThat(spans.getFirst().getAttributes().get(NAMESPACE)).isEqualTo(DATABASE);
  }

  @Test
  @DisplayName("an insert with bound values gets an INSERT span with no value")
  void insertHasOperation() throws SQLException {
    var spans =
        spansOf(
            () ->
                jdbcClient
                    .sql("INSERT INTO customers VALUES (?, ?) ON CONFLICT DO NOTHING")
                    .params(CUSTOMER_ID, PERSONAL_VALUE)
                    .update());

    assertThat(spans)
        .singleElement()
        .satisfies(span -> assertStatementSpan(span, "INSERT", PERSONAL_VALUE));
  }

  @Test
  @DisplayName("a statement outside a request gets no span")
  void untracedHasNoSpan() {
    jdbcClient.sql("SELECT count(*) FROM customers").query(Long.class).single();

    assertThat(traced.statementSpans()).isEmpty();
  }

  /** A real tracer over OpenTelemetry's SDK, which samples every trace and keeps its spans. */
  @TestConfiguration(proxyBeanMethods = false)
  static class Spans {

    /**
     * Returns the traced requests, which sample every trace and keep its spans in memory.
     *
     * @return the traced requests
     */
    @Bean(destroyMethod = "close")
    TracedRequests tracedRequests() {
      return TracedRequests.sampled();
    }

    /**
     * Returns Micrometer's tracer over the SDK, as the observability starter sets one up.
     *
     * @param traced the traced requests
     * @return the tracer
     */
    @Bean
    Tracer tracer(TracedRequests traced) {
      return traced.tracer();
    }
  }
}
