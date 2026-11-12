package com.exepex.stratum.data.benchmark;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.Executor;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Measures, on the raw HikariCP pool and a PostgreSQL container, what each of three settings costs
 * before Stratum turns it on: HikariCP's leak detection and a network timeout on every borrow, and
 * pgjdbc's query timeout on every statement, and Stratum's own statement timer in its place. Each
 * runs once without the setting and once with it, on one thread and on 16, in separate forks.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class StatementLimitsBenchmark {

  /** The query each statement runs: one row, one column. */
  private static final String SELECT_ONE = "SELECT 1";

  /** The network timeout a borrow sets, in milliseconds: the 30s query timeout plus 30s. */
  private static final int NETWORK_TIMEOUT_MILLIS = 60_000;

  /** The query timeout a statement sets, in seconds: Stratum's planned default. */
  private static final int QUERY_TIMEOUT_SECONDS = 30;

  /** The limit Stratum's own timer arms, in milliseconds: the same 30s. */
  private static final long TIMER_LIMIT_MILLIS = 30_000;

  /** The leak alarm's threshold, in milliseconds: the 60s transaction timeout plus 10s. */
  private static final String LEAK_THRESHOLD = "70000";

  /** Connections in the pool: one per thread of the 16-thread runs, so no thread waits. */
  private static final String POOL_SIZE = "16";

  /** Runs the network timeout's work on the calling thread, as HikariCP's own executor does. */
  private static final Executor CALLING_THREAD = Runnable::run;

  /**
   * Takes a connection from the pool and returns it, on one thread.
   *
   * @param pool the pool of this side
   * @return the returned connection, which JMH consumes
   * @throws SQLException if the pool fails
   */
  @Benchmark
  @Threads(1)
  public Connection borrow(BorrowPool pool) throws SQLException {
    return pool.borrowAndReturn();
  }

  /**
   * Takes a connection from the pool and returns it, on 16 threads at once.
   *
   * @param pool the pool of this side
   * @return the returned connection, which JMH consumes
   * @throws SQLException if the pool fails
   */
  @Benchmark
  @Threads(16)
  public Connection borrowConcurrently(BorrowPool pool) throws SQLException {
    return pool.borrowAndReturn();
  }

  /**
   * Prepares, runs and closes a statement on a connection already borrowed, on one thread.
   *
   * @param connection the connection of this thread and side
   * @return the value read, which JMH consumes
   * @throws SQLException if the database fails
   */
  @Benchmark
  @Threads(1)
  public int query(QueryConnection connection) throws SQLException {
    return connection.runQuery();
  }

  /**
   * Prepares, runs and closes a statement on a connection already borrowed, on 16 threads at once.
   *
   * @param connection the connection of this thread and side
   * @return the value read, which JMH consumes
   * @throws SQLException if the database fails
   */
  @Benchmark
  @Threads(16)
  public int queryConcurrently(QueryConnection connection) throws SQLException {
    return connection.runQuery();
  }

  /**
   * A started context and its raw pool.
   *
   * @param context the context, with its PostgreSQL container
   * @param dataSource the raw HikariCP pool, without the boundary
   */
  private record Pool(ConfigurableApplicationContext context, HikariDataSource dataSource) {

    /**
     * Starts the context of one fork, with the given pool settings, and returns its raw pool.
     *
     * @param properties the pool settings of this side, as {@code key=value}
     * @return the started context and its raw pool
     * @throws SQLException if the pool cannot be reached behind the bean
     */
    static Pool start(String... properties) throws SQLException {
      var context =
          new SpringApplicationBuilder(JdbcBoundaryBenchmark.BenchmarkApplication.class)
              .web(WebApplicationType.NONE)
              .properties("spring.datasource.hikari.maximum-pool-size=" + POOL_SIZE)
              .properties(properties)
              .run();
      return new Pool(context, context.getBean(DataSource.class).unwrap(HikariDataSource.class));
    }
  }

  /** The pool of the borrow benchmarks, with or without the setting measured. */
  @State(Scope.Benchmark)
  public static class BorrowPool {

    /** What a borrow does besides taking the connection: nothing, or one of the settings. */
    @Param({"none", "leak-detection", "network-timeout"})
    public String setting;

    /** The context and pool of this fork. */
    private Pool pool;

    /** Whether each borrow sets the network timeout, which HikariCP resets on return. */
    private boolean setsNetworkTimeout;

    /**
     * Starts the context, with leak detection on for that side.
     *
     * @throws SQLException if the pool cannot be reached behind the bean
     */
    @Setup(Level.Trial)
    public void start() throws SQLException {
      pool =
          "leak-detection".equals(setting)
              ? Pool.start("spring.datasource.hikari.leak-detection-threshold=" + LEAK_THRESHOLD)
              : Pool.start();
      setsNetworkTimeout = "network-timeout".equals(setting);
    }

    /**
     * Takes a connection, sets its network timeout on that side, and returns it.
     *
     * @return the returned connection
     * @throws SQLException if the pool fails
     */
    Connection borrowAndReturn() throws SQLException {
      try (Connection connection = pool.dataSource().getConnection()) {
        if (setsNetworkTimeout) {
          connection.setNetworkTimeout(CALLING_THREAD, NETWORK_TIMEOUT_MILLIS);
        }
        return connection;
      }
    }

    /** Closes the context, and with it the pool and the container. */
    @TearDown(Level.Trial)
    public void stop() {
      pool.context().close();
    }
  }

  /**
   * The pool of the query benchmarks, with nothing, pgjdbc's query timeout or Stratum's own timer
   * on each statement.
   */
  @State(Scope.Benchmark)
  public static class QueryPool {

    /** What each statement does besides running: nothing, or one of the two timers. */
    @Param({"none", "query-timeout", "stratum-timer"})
    public String setting;

    /** The context and pool of this fork. */
    private Pool pool;

    /** Stratum's timer, which every thread of the fork shares. */
    private StatementTimer timer;

    /**
     * Starts the context and the timer.
     *
     * @throws SQLException if the pool cannot be reached behind the bean
     */
    @Setup(Level.Trial)
    public void start() throws SQLException {
      pool = Pool.start();
      timer = StatementTimer.start();
    }

    /** Stops the timer and closes the context, and with it the pool and the container. */
    @TearDown(Level.Trial)
    public void stop() {
      timer.stop();
      pool.context().close();
    }
  }

  /** One connection per thread, held for the whole fork, for the query benchmarks. */
  @State(Scope.Thread)
  public static class QueryConnection {

    /** The connection this thread's statements run on. */
    private Connection connection;

    /** Whether each statement sets a query timeout. */
    private boolean setsQueryTimeout;

    /** Stratum's timer, which the fork's threads share. */
    private StatementTimer timer;

    /** Whether each execute runs with Stratum's timer armed. */
    private boolean armsTimer;

    /**
     * Borrows this thread's connection.
     *
     * @param pool the pool of this side
     * @throws SQLException if the pool fails
     */
    @Setup(Level.Trial)
    public void open(QueryPool pool) throws SQLException {
      connection = pool.pool.dataSource().getConnection();
      setsQueryTimeout = "query-timeout".equals(pool.setting);
      timer = pool.timer;
      armsTimer = "stratum-timer".equals(pool.setting);
    }

    /**
     * Prepares the query, sets its timeout or arms Stratum's timer on that side, runs it and reads
     * its value.
     *
     * @return the value read
     * @throws SQLException if the database fails
     */
    int runQuery() throws SQLException {
      try (PreparedStatement statement = connection.prepareStatement(SELECT_ONE)) {
        if (setsQueryTimeout) {
          statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        }
        return armsTimer
            ? timer.around(TIMER_LIMIT_MILLIS, () -> readOne(statement))
            : readOne(statement);
      }
    }

    /**
     * Runs the query and reads its value.
     *
     * @param statement the prepared query
     * @return the value read
     * @throws SQLException if the database fails
     */
    private int readOne(PreparedStatement statement) throws SQLException {
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? resultSet.getInt(1) : 0;
      }
    }

    /**
     * Returns this thread's connection.
     *
     * @throws SQLException if the pool fails
     */
    @TearDown(Level.Trial)
    public void close() throws SQLException {
      connection.close();
    }
  }
}
