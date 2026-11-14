package com.exepex.stratum.data.it;

import static com.exepex.stratum.test.DatabaseFailureAssertions.PERSONAL_VALUE;
import static com.exepex.stratum.test.QuerySpanAssertions.NAMESPACE;
import static com.exepex.stratum.test.QuerySpanAssertions.SYSTEM;
import static com.exepex.stratum.test.QuerySpanAssertions.assertStatementSpan;
import static com.exepex.stratum.test.QuerySpanAssertions.statementSpans;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.data.it.orders.OrdersApplication;
import com.exepex.stratum.data.it.orders.PlacedOrder;
import com.exepex.stratum.test.PostgresContainerConfiguration;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
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

  /** The spans that ended, kept in memory. */
  private static final InMemorySpanExporter EXPORTER = InMemorySpanExporter.create();

  /** The order a test looks up. */
  private static final long ORDER_ID = 987_654_321L;

  @Autowired private EntityManagerFactory entityManagerFactory;

  @Autowired private Tracer tracer;

  /** Forgets the spans of earlier tests and of startup. */
  @BeforeEach
  void forgetSpans() {
    EXPORTER.reset();
  }

  /** Runs work with an entity manager inside a sampled request, and returns the statement spans. */
  private List<SpanData> spansOf(Consumer<EntityManager> work) {
    var request = tracer.nextSpan().name("request").start();
    try (var _ = tracer.withSpan(request);
        var entityManager = entityManagerFactory.createEntityManager()) {
      work.accept(entityManager);
    } finally {
      request.end();
    }
    return statementSpans(EXPORTER.getFinishedSpanItems());
  }

  @Test
  @DisplayName("Hibernate's lookup of an entity gets one SELECT span with the database, no id")
  void lookupHasDatabaseAndOperation() {
    var spans = spansOf(entityManager -> entityManager.find(PlacedOrder.class, ORDER_ID));

    assertThat(spans)
        .singleElement()
        .satisfies(span -> assertStatementSpan(span, "SELECT", Long.toString(ORDER_ID)));
    assertThat(spans.getFirst().getAttributes().get(SYSTEM)).isEqualTo("postgresql");
    assertThat(spans.getFirst().getAttributes().get(NAMESPACE)).isEqualTo("test");
  }

  @Test
  @DisplayName("a native query with a literal gets a SELECT span with no value")
  void nativeQueryHasOperation() {
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

  /**
   * Micrometer's tracer over OpenTelemetry's SDK, which samples every trace and keeps its spans.
   */
  @TestConfiguration(proxyBeanMethods = false)
  static class Spans {

    /**
     * Returns the SDK's tracer provider, which exports each span as it ends, into memory.
     *
     * @return the provider
     */
    @Bean(destroyMethod = "close")
    SdkTracerProvider tracerProvider() {
      return SdkTracerProvider.builder()
          .addSpanProcessor(SimpleSpanProcessor.create(EXPORTER))
          .build();
    }

    /**
     * Returns Micrometer's tracer over the SDK, as the observability starter sets one up.
     *
     * @param tracerProvider the SDK's tracer provider
     * @return the tracer
     */
    @Bean
    Tracer tracer(SdkTracerProvider tracerProvider) {
      return new OtelTracer(tracerProvider.get("test"), new OtelCurrentTraceContext(), event -> {});
    }
  }
}
