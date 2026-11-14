package com.exepex.stratum.data.it;

import static com.exepex.stratum.test.DatabaseFailureAssertions.PERSONAL_VALUE;
import static com.exepex.stratum.test.QuerySpanAssertions.NAMESPACE;
import static com.exepex.stratum.test.QuerySpanAssertions.SYSTEM;
import static com.exepex.stratum.test.QuerySpanAssertions.assertStatementSpan;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.data.it.orders.OrdersApplication;
import com.exepex.stratum.data.it.orders.PlacedOrder;
import com.exepex.stratum.test.PostgresContainerConfiguration;
import com.exepex.stratum.test.TracedRequests;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.trace.data.SpanData;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootTest(
    classes = {OrdersApplication.class, QuerySpansIntegrationTest.Spans.class},
    properties = {"spring.sql.init.mode=always"})
@Import(PostgresContainerConfiguration.class)
@DisplayName("On PostgreSQL, a traced JPA service's statements get client spans with no SQL text")
class QuerySpansIntegrationTest {

  /** The order a test looks up. */
  private static final long ORDER_ID = 987_654_321L;

  @Autowired private EntityManagerFactory entityManagerFactory;

  @Autowired private TracedRequests traced;

  /** Forgets the spans of earlier tests and of startup. */
  @BeforeEach
  void forgetSpans() {
    traced.reset();
  }

  /** Runs work with an entity manager inside a sampled request, and returns the statement spans. */
  private List<SpanData> spansOf(Consumer<EntityManager> work) throws SQLException {
    traced.inRequest(() -> withEntityManager(work));
    return traced.statementSpans();
  }

  /** Runs work with a new entity manager, which it closes. */
  private void withEntityManager(Consumer<EntityManager> work) {
    try (var entityManager = entityManagerFactory.createEntityManager()) {
      work.accept(entityManager);
    }
  }

  @Test
  @DisplayName("Hibernate's lookup of an entity gets one SELECT span with the database, no id")
  void lookupHasDatabaseAndOperation() throws SQLException {
    var spans = spansOf(entityManager -> entityManager.find(PlacedOrder.class, ORDER_ID));

    assertThat(spans)
        .singleElement()
        .satisfies(span -> assertStatementSpan(span, "SELECT", Long.toString(ORDER_ID)));
    assertThat(spans.getFirst().getAttributes().get(SYSTEM)).isEqualTo("postgresql");
    assertThat(spans.getFirst().getAttributes().get(NAMESPACE)).isEqualTo("test");
  }

  @Test
  @DisplayName("a native query with a literal gets a SELECT span with no value")
  void nativeQueryHasOperation() throws SQLException {
    var spans =
        spansOf(
            entityManager ->
                entityManager
                    .createNativeQuery(
                        "SELECT count(*) FROM orders WHERE status = '" + PERSONAL_VALUE + "'")
                    .getSingleResult());

    assertThat(spans)
        .singleElement()
        .satisfies(span -> assertStatementSpan(span, "SELECT", PERSONAL_VALUE));
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
