package com.exepex.stratum.data.benchmark;

import com.exepex.stratum.data.routing.PoolRouting;
import com.exepex.stratum.data.routing.RoutingDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

/**
 * Measures what routing a repository's call to its named pool adds to one statement, with no
 * database: the call's pool set and restored as the repository advice does it, Spring's lazy proxy,
 * the routing lookup, and the check of the statement against the pool its connection was opened on.
 * Each side borrows a stub connection, prepares and runs one statement, and closes both.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class RoutingBenchmark {

  /** The named pool every routed call wants. */
  private static final String POOL = "batch";

  /** The statement each call runs. */
  private static final String QUERY = "SELECT 1";

  /**
   * Borrows a connection, runs one statement and closes both, on the pool of this side.
   *
   * @param side the data source of this side
   * @return 1 once the statement ran, which JMH consumes
   * @throws SQLException never, from the stubs
   */
  @Benchmark
  public int call(Side side) throws SQLException {
    var previous = side.routed ? side.routing.enter(POOL) : null;
    try (Connection connection = side.dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(QUERY);
        ResultSet rows = statement.executeQuery()) {
      return rows == null ? 0 : 1;
    } finally {
      if (side.routed) {
        side.routing.exit(previous);
      }
    }
  }

  /** The data source of one side: the stub pool itself, or Stratum's routing over it. */
  @State(Scope.Benchmark)
  public static class Side {

    /** The side this fork measures: the pool called directly, or through Stratum's routing. */
    @Param({"direct", "routed"})
    public String path;

    /** The pool each call wants, which only the routed side sets. */
    final PoolRouting routing = new PoolRouting();

    /** Whether this side routes, so sets the call's pool as the repository advice does. */
    boolean routed;

    /** The data source the benchmark calls. */
    DataSource dataSource;

    /** Builds this side's data source over stub pools. */
    @Setup
    public void setUp() {
      var pool = JdbcStubs.stubPool();
      routed = "routed".equals(path);
      dataSource =
          routed
              ? new LazyConnectionDataSourceProxy(
                  new RoutingDataSource(routing, Map.of(POOL, pool), JdbcStubs.stubPool()))
              : pool;
    }
  }
}
