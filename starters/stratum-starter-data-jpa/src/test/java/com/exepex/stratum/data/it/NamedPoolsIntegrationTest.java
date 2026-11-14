package com.exepex.stratum.data.it;

import static com.exepex.stratum.test.QuerySpanAssertions.POOL;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.data.it.boundary.BoundaryApplication;
import com.exepex.stratum.test.NamedPoolConnections;
import com.exepex.stratum.test.PostgresContainerConfiguration;
import com.exepex.stratum.test.TracedRequests;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName(
    "On PostgreSQL, a named pool beside Spring Boot's is found by health, metrics and spans")
class NamedPoolsIntegrationTest {

  /** The pool's name, which its own database is named after. */
  private static final String BATCH_DATABASE = "batch";

  /** The data source bean of the main pool, which Spring Boot creates. */
  private static final String MAIN_DATA_SOURCE = "dataSource";

  /** The data source bean of the pool {@code batch}. */
  private static final String BATCH_DATA_SOURCE = "batchDataSource";

  /** The service, started once for every test. */
  private ConfigurableApplicationContext application;

  /**
   * Starts the service, with the main pool on its own database and {@code batch} on another, named
   * after the pool.
   */
  @BeforeAll
  void start() {
    application =
        new SpringApplicationBuilder(
                BoundaryApplication.class,
                PostgresContainerConfiguration.class,
                QuerySpansIntegrationTest.Spans.class)
            .web(WebApplicationType.NONE)
            .initializers(NamedPoolConnections.postgres(BATCH_DATABASE))
            .properties("spring.jpa.hibernate.ddl-auto=none")
            .run();
  }

  /** Stops the service and both databases. */
  @AfterAll
  void stop() {
    application.close();
  }

  /** Forgets the spans of earlier tests and of startup. */
  @BeforeEach
  void forgetSpans() {
    tracedRequests().reset();
  }

  /** Returns the service's traced requests, which the application context owns and closes. */
  private TracedRequests tracedRequests() {
    return application.getBean(TracedRequests.class);
  }

  /** Returns the name of the database a data source bean connects to. */
  private String databaseOf(String beanName) {
    return JdbcClient.create(application.getBean(beanName, DataSource.class))
        .sql("SELECT current_database()")
        .query(String.class)
        .single();
  }

  @Test
  @DisplayName("the pool connects to its own database, and the plain data source to main's")
  void connectsToItsDatabase() {
    assertThat(databaseOf(BATCH_DATA_SOURCE)).isEqualTo(BATCH_DATABASE);
    assertThat(databaseOf(MAIN_DATA_SOURCE)).isNotEqualTo(BATCH_DATABASE);
  }

  @Test
  @DisplayName("Spring Boot's db health lists both pools, each up")
  void healthListsBoth() {
    var health = application.getBean("dbHealthContributor", CompositeHealthContributor.class);

    assertThat(((HealthIndicator) health.getContributor(MAIN_DATA_SOURCE)).health().getStatus())
        .isEqualTo(Status.UP);
    assertThat(((HealthIndicator) health.getContributor(BATCH_DATA_SOURCE)).health().getStatus())
        .isEqualTo(Status.UP);
  }

  @Test
  @DisplayName("Spring Boot's pool metrics tag each pool with its one name: main and batch")
  void metricsNameEachPool() {
    var registry = application.getBean(MeterRegistry.class);
    application
        .getBeansOfType(MeterBinder.class)
        .values()
        .forEach(binder -> binder.bindTo(registry));
    databaseOf(BATCH_DATA_SOURCE);
    databaseOf(MAIN_DATA_SOURCE);

    assertThat(registry.find("hikaricp.connections.max").tag("pool", "batch").gauge()).isNotNull();
    assertThat(registry.find("hikaricp.connections.max").tag("pool", "main").gauge()).isNotNull();
  }

  @Test
  @DisplayName("each statement's span carries its pool's name")
  void spansNameThePool() throws SQLException {
    tracedRequests().inRequest(this::queryBothPools);

    assertThat(tracedRequests().statementSpans())
        .extracting(span -> span.getAttributes().get(POOL))
        .containsExactly("batch", "main");
  }

  /** Runs one statement on the pool {@code batch}, then one on the main pool. */
  private void queryBothPools() {
    databaseOf(BATCH_DATA_SOURCE);
    databaseOf(MAIN_DATA_SOURCE);
  }
}
