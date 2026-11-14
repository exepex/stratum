package com.exepex.stratum.data.autoconfigure;

import static com.exepex.stratum.test.DatabaseFailureAssertions.PERSONAL_VALUE;
import static com.exepex.stratum.test.QuerySpanAssertions.assertStatementSpan;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.JdbcWrapper;
import com.exepex.stratum.data.testsupport.DataStarterStandIns;
import com.exepex.stratum.data.testsupport.MockDatabase;
import com.exepex.stratum.test.TracedRequests;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("A data service with tracing gets a span per statement; without it, none")
class StatementSpansConfigurationTest {

  /** A query that holds the personal value as a literal. */
  private static final String QUERY =
      "SELECT id FROM customers WHERE email = '" + PERSONAL_VALUE + "'";

  private final TracedRequests traced = TracedRequests.sampled();

  /** A data service with the JDBC boundary and a pool, and no tracer yet. */
  private final ApplicationContextRunner dataService =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(DataStarterStandIns.JdbcBoundaryStandIn.class))
          .withBean("dataSource", DataSource.class, this::pool);

  /** The same service with the observability starter's tracer. */
  private final ApplicationContextRunner tracedService =
      dataService.withBean(Tracer.class, traced::tracer);

  /** Closes the tracer. */
  @AfterEach
  void close() {
    traced.close();
  }

  /** Returns a mock pool, as a bean supplier needs it. */
  private DataSource pool() {
    try {
      return new MockDatabase().pool;
    } catch (SQLException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /** Runs the query in a sampled request and returns the number of statement spans. */
  private int spansOfOneQuery(AssertableApplicationContext context) throws Exception {
    assertThat(context).hasNotFailed();
    traced.inRequest(() -> runQuery(context.getBean(DataSource.class)));
    return traced.statementSpans().size();
  }

  /** Runs the query once. */
  private void runQuery(DataSource dataSource) throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.executeQuery(QUERY);
    }
  }

  @Test
  @DisplayName("with the module on and Micrometer Tracing's OpenTelemetry bridge: one span")
  void tracedServiceGetsSpan() {
    tracedService.run(context -> assertThat(spansOfOneQuery(context)).isEqualTo(1));
    assertStatementSpan(traced.statementSpans().getFirst(), "SELECT", PERSONAL_VALUE);
  }

  @Test
  @DisplayName("without the OpenTelemetry bridge on the class path: no span")
  void noBridge() {
    tracedService
        .withClassLoader(new FilteredClassLoader("io.micrometer.tracing.otel"))
        .run(context -> assertThat(spansOfOneQuery(context)).isZero());
  }

  @Test
  @DisplayName("with no tracer bean: no span")
  void noTracer() {
    dataService.run(context -> assertThat(spansOfOneQuery(context)).isZero());
  }

  @Test
  @DisplayName("with stratum.data.enabled=false: no boundary and no span")
  void moduleOff() {
    tracedService
        .withPropertyValues(DataConstants.ENABLED + "=false")
        .run(context -> assertUnwrappedWithoutSpans(context));
  }

  @Test
  @DisplayName("in backward-compatible mode: the same span")
  void compatibleMode() {
    tracedService
        .withPropertyValues(StratumModuleConstants.BACKWARD_COMPATIBLE + "=true")
        .run(context -> assertThat(spansOfOneQuery(context)).isEqualTo(1));
    assertStatementSpan(traced.statementSpans().getFirst(), "SELECT", PERSONAL_VALUE);
  }

  @Test
  @DisplayName("with a meter registry: the span adds no meter")
  void noMeter() {
    tracedService
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .run(context -> assertNoMeterAdded(context));
  }

  /** Checks that the data source has no boundary and its statement gets no span. */
  private void assertUnwrappedWithoutSpans(AssertableApplicationContext context) throws Exception {
    assertThat(context.getBean(DataSource.class)).isNotInstanceOf(JdbcWrapper.class);
    assertThat(spansOfOneQuery(context)).isZero();
  }

  /** Checks that a traced statement leaves the meter registry as it was. */
  private void assertNoMeterAdded(AssertableApplicationContext context) throws Exception {
    var registry = context.getBean(MeterRegistry.class);
    var metersBefore = registry.getMeters().size();
    assertThat(spansOfOneQuery(context)).isEqualTo(1);
    assertThat(registry.getMeters()).hasSize(metersBefore);
  }
}
