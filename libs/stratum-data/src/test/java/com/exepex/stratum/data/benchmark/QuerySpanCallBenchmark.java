package com.exepex.stratum.data.benchmark;

import com.exepex.stratum.data.autoconfigure.JdbcBoundaryConfiguration;
import com.exepex.stratum.test.PostgresContainerConfiguration;
import io.micrometer.tracing.Tracer;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Measures what a statement's span costs on a PostgreSQL round trip, with Spring Boot's
 * OpenTelemetry tracing and an exporter that drops every span. Each call runs in a request's root
 * span, sampled or not. Run with {@code -prof gc}, it gates the span's memory budget of 5 KB per
 * traced statement; its time has the round trip's noise, so the time budget is gated by {@link
 * StatementSpansBenchmark}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class QuerySpanCallBenchmark {

  /** The query the cached-query cases run. */
  private static final String QUERY = "SELECT id, status FROM orders WHERE id = ? AND status = ?";

  /** The pool size, so 16 threads measure spans rather than waiting for a connection. */
  private static final int POOL_SIZE = 16;

  /**
   * Runs the same prepared query in a request, on one thread.
   *
   * @param service the started service of this side
   * @return whether the result had a row, which JMH consumes
   * @throws SQLException if the database fails
   */
  @Benchmark
  @Threads(1)
  public boolean sameQuery(Service service) throws SQLException {
    return service.inRequest(QUERY);
  }

  /**
   * Runs the same prepared query in a request, on 16 threads at once.
   *
   * @param service the started service of this side
   * @return whether the result had a row, which JMH consumes
   * @throws SQLException if the database fails
   */
  @Benchmark
  @Threads(16)
  public boolean sameQuery16(Service service) throws SQLException {
    return service.inRequest(QUERY);
  }

  /**
   * Runs a query PostgreSQL has never seen in a request, on one thread.
   *
   * @param service the started service of this side
   * @return whether the result had a row, which JMH consumes
   * @throws SQLException if the database fails
   */
  @Benchmark
  @Threads(1)
  public boolean newQuery(Service service) throws SQLException {
    return service.inRequest(QUERY + " AND id > " + service.queries.incrementAndGet());
  }

  /** The started service, whose requests are sampled or not. */
  @State(Scope.Benchmark)
  public static class Service {

    /** Whether each request is sampled. */
    @Param({"unsampled", "sampled"})
    public String side;

    /** Numbers each new query, so PostgreSQL never saw it. */
    final AtomicLong queries = new AtomicLong();

    /** The service's application context. */
    private ConfigurableApplicationContext context;

    /** The data source bean. */
    private DataSource dataSource;

    /** The service's tracer, for the request's root span. */
    private Tracer tracer;

    /**
     * Starts the service and creates the table.
     *
     * @throws SQLException if the table cannot be created
     */
    @Setup(Level.Trial)
    public void start() throws SQLException {
      context =
          new SpringApplicationBuilder(TracedDataService.class)
              .web(WebApplicationType.NONE)
              .run(
                  "--management.tracing.sampling.probability=" + ("sampled".equals(side) ? 1 : 0),
                  "--spring.datasource.hikari.maximum-pool-size=" + POOL_SIZE);
      dataSource = context.getBean(DataSource.class);
      tracer = context.getBean(Tracer.class);
      try (var connection = dataSource.getConnection();
          var statement = connection.createStatement()) {
        statement.execute("CREATE TABLE orders (id bigint PRIMARY KEY, status text)");
      }
    }

    /**
     * Runs a query with its two values bound, inside a request's root span.
     *
     * @param sql the query
     * @return whether the result had a row
     * @throws SQLException if the database fails
     */
    boolean inRequest(String sql) throws SQLException {
      var request = tracer.nextSpan().name("request").start();
      try (var _ = tracer.withSpan(request);
          var connection = dataSource.getConnection();
          var statement = connection.prepareStatement(sql)) {
        statement.setLong(1, 1L);
        statement.setString(2, "PLACED");
        try (var rows = statement.executeQuery()) {
          return rows.next();
        }
      } finally {
        request.end();
      }
    }

    /** Stops the service, the pool and the container. */
    @TearDown(Level.Trial)
    public void stop() {
      context.close();
    }
  }

  /** A traced data service on PostgreSQL, whose spans are dropped at export. */
  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({JdbcBoundaryConfiguration.class, PostgresContainerConfiguration.class})
  static class TracedDataService {

    /**
     * Returns an exporter that drops every span.
     *
     * @return the exporter
     */
    @Bean
    StatementSpansBenchmark.DroppingExporter droppingExporter() {
      return new StatementSpansBenchmark.DroppingExporter();
    }
  }
}
