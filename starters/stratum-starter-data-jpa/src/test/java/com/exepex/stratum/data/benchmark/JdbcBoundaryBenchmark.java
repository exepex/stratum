package com.exepex.stratum.data.benchmark;

import com.exepex.stratum.test.PostgresContainerConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
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
import org.openjdk.jmh.annotations.Warmup;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

/**
 * Measures what the JDBC boundary adds to JDBC calls. Each benchmark runs once on the raw HikariCP
 * pool and once on the data source bean that wraps it, on a PostgreSQL container. JMH runs the two
 * sides in separate forks, each with its own context, pool and container.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(5)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class JdbcBoundaryBenchmark {

  /** The query roundTrip runs and OpenRow fetches: one row, one column, no data value. */
  private static final String QUERY = "SELECT 1";

  /**
   * Takes a connection, runs the query, reads its one value and closes everything: 8 calls through
   * the boundary, each with a round trip to the database where the driver needs one.
   *
   * @param pool the data source of this side
   * @return the value read, which JMH consumes
   * @throws SQLException if the database fails
   */
  @Benchmark
  public int roundTrip(Pool pool) throws SQLException {
    try (Connection connection = pool.dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(QUERY);
        ResultSet resultSet = statement.executeQuery()) {
      return resultSet.next() ? resultSet.getInt(1) : 0;
    }
  }

  /**
   * Reads the value of a row that is already fetched: 1 call through the boundary, with no round
   * trip, so it measures the wrapper's own cost.
   *
   * @param row the open result set of this side
   * @return the value read, which JMH consumes
   * @throws SQLException if the driver fails
   */
  @Benchmark
  public int localCall(OpenRow row) throws SQLException {
    return row.resultSet.getInt(1);
  }

  /**
   * Creates a statement on an open connection and closes it: 2 calls through the boundary, and the
   * first builds a wrapper for the statement. The driver makes no round trip for either.
   *
   * @param row the open connection of this side
   * @return the closed statement, which JMH consumes
   * @throws SQLException if the driver fails
   */
  @Benchmark
  public Statement localWrapCall(OpenRow row) throws SQLException {
    Statement statement = row.connection.createStatement();
    statement.close();
    return statement;
  }

  /** The data source of one side, from a context started once in each fork. */
  @State(Scope.Benchmark)
  public static class Pool {

    /** The side this fork measures: the raw pool or the boundary around it. */
    @Param({"raw", "boundary"})
    public String side;

    /** The context of this fork, with its PostgreSQL container. */
    private ConfigurableApplicationContext context;

    /** The data source the benchmarks call: the raw pool or the wrapped bean. */
    DataSource dataSource;

    /**
     * Starts the context and picks this side's data source.
     *
     * @throws SQLException if the pool cannot be reached behind the bean
     */
    @Setup(Level.Trial)
    public void start() throws SQLException {
      context =
          new SpringApplicationBuilder(BenchmarkApplication.class)
              .web(WebApplicationType.NONE)
              .run();
      DataSource bean = context.getBean(DataSource.class);
      dataSource = "raw".equals(side) ? bean.unwrap(HikariDataSource.class) : bean;
    }

    /** Closes the context, and with it the pool and the container. */
    @TearDown(Level.Trial)
    public void stop() {
      context.close();
    }
  }

  /** An open connection and one row that is already fetched, held open for the whole fork. */
  @State(Scope.Thread)
  public static class OpenRow {

    /** The connection that holds the row. */
    Connection connection;

    /** The statement that fetched the row. */
    private PreparedStatement statement;

    /** The result set, moved to its one row. */
    ResultSet resultSet;

    /**
     * Fetches the row through this side's data source.
     *
     * @param pool the data source of this side
     * @throws SQLException if the database fails
     */
    @Setup(Level.Trial)
    public void open(Pool pool) throws SQLException {
      connection = pool.dataSource.getConnection();
      statement = connection.prepareStatement(QUERY);
      resultSet = statement.executeQuery();
      if (!resultSet.next()) {
        throw new IllegalStateException("The query returned no row");
      }
    }

    /**
     * Closes the result set, the statement and the connection.
     *
     * @throws SQLException if the driver fails
     */
    @TearDown(Level.Trial)
    public void close() throws SQLException {
      resultSet.close();
      statement.close();
      connection.close();
    }
  }

  /** The starter's auto-configuration on the PostgreSQL container every Stratum test uses. */
  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import(PostgresContainerConfiguration.class)
  static class BenchmarkApplication {}
}
