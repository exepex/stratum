package com.exepex.stratum.data.routing;

import static com.exepex.stratum.data.testsupport.NamedPoolTestData.BATCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.data.DataErrorCode;
import com.exepex.stratum.data.PoolRouter;
import com.exepex.stratum.data.PoolRouters;
import com.exepex.stratum.data.testsupport.MockDatabase;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.assertj.core.api.ThrowingConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

@DisplayName("RoutingDataSource opens each connection on the pool its call wants, and checks it")
class RoutingDataSourceTest {

  private final PoolRouting routing = new PoolRouting();

  private final MockDatabase main;

  private final MockDatabase batch;

  private final RoutingDataSource router;

  /**
   * Builds the router over two pools of mocks.
   *
   * @throws SQLException never; the mocks declare it
   */
  RoutingDataSourceTest() throws SQLException {
    main = new MockDatabase();
    batch = new MockDatabase();
    router = new RoutingDataSource(routing, Map.of(BATCH, batch.pool), main.pool);
  }

  /** Opens a connection while a repository of a pool runs. */
  private Connection openOn(String pool) throws SQLException {
    var previous = routing.enter(pool);
    try {
      return router.getConnection();
    } finally {
      routing.exit(previous);
    }
  }

  @Nested
  @DisplayName("the pool")
  class ChosenPool {

    @Test
    @DisplayName("is main outside a repository call, and the repository's pool inside one")
    void followsTheCall() throws SQLException {
      router.getConnection().close();
      openOn(BATCH).close();

      verify(main.pool).getConnection();
      verify(batch.pool).getConnection();
    }

    @Test
    @DisplayName("is the same with a user name and password")
    void sameWithCredentials() throws SQLException {
      when(batch.pool.getConnection("app", "pw")).thenReturn(batch.connection);
      var previous = routing.enter(BATCH);
      try {
        router.getConnection("app", "pw").close();
      } finally {
        routing.exit(previous);
      }

      verify(batch.connection).close();
    }

    @Test
    @DisplayName("must be known: a pool the router does not hold fails, rather than using main")
    void unknownPoolFails() {
      assertThatThrownBy(() -> openOn("reporting")).isInstanceOf(IllegalStateException.class);
    }
  }

  /** Returns how often the pool's connection was asked for a statement, by name and arity. */
  private long statementCalls(String method, int arity) {
    return mockingDetails(batch.connection).getInvocations().stream()
        .filter(call -> call.getMethod().getName().equals(method))
        .filter(call -> call.getArguments().length == arity)
        .count();
  }

  @Nested
  @DisplayName("a statement")
  class Statements {

    /** The method that creates a plain statement. */
    private static final String CREATE = "createStatement";

    /** The method that prepares a statement. */
    private static final String PREPARE = "prepareStatement";

    /** The method that prepares a call. */
    private static final String PREPARE_CALL = "prepareCall";

    /** The SQL of each prepared statement. */
    private static final String SQL = "SELECT 1";

    /** The call of each callable statement. */
    private static final String CALL = "{call p()}";

    /** A result set read forward only. */
    private static final int FORWARD = ResultSet.TYPE_FORWARD_ONLY;

    /** A result set that cannot be changed. */
    private static final int READ_ONLY = ResultSet.CONCUR_READ_ONLY;

    /** A result set kept open over a commit. */
    private static final int HOLD = ResultSet.HOLD_CURSORS_OVER_COMMIT;

    /** The index of the first column, whose generated key the statement returns. */
    private static final int FIRST_COLUMN = 1;

    /**
     * Each of the 12 ways a connection makes a statement: the method, its number of arguments and
     * the call.
     */
    private static final List<Arguments> STATEMENT_KINDS =
        List.of(
            arguments(CREATE, 0, (Kind) Connection::createStatement),
            arguments(
                CREATE, 2, (Kind) connection -> connection.createStatement(FORWARD, READ_ONLY)),
            arguments(
                CREATE,
                3,
                (Kind) connection -> connection.createStatement(FORWARD, READ_ONLY, HOLD)),
            arguments(PREPARE, 1, (Kind) connection -> connection.prepareStatement(SQL)),
            arguments(
                PREPARE,
                2,
                (Kind)
                    connection ->
                        connection.prepareStatement(SQL, Statement.RETURN_GENERATED_KEYS)),
            arguments(
                PREPARE,
                2,
                (Kind) connection -> connection.prepareStatement(SQL, new int[] {FIRST_COLUMN})),
            arguments(
                PREPARE,
                2,
                (Kind) connection -> connection.prepareStatement(SQL, new String[] {"id"})),
            arguments(
                PREPARE,
                3,
                (Kind) connection -> connection.prepareStatement(SQL, FORWARD, READ_ONLY)),
            arguments(
                PREPARE,
                4,
                (Kind) connection -> connection.prepareStatement(SQL, FORWARD, READ_ONLY, HOLD)),
            arguments(PREPARE_CALL, 1, (Kind) connection -> connection.prepareCall(CALL)),
            arguments(
                PREPARE_CALL,
                3,
                (Kind) connection -> connection.prepareCall(CALL, FORWARD, READ_ONLY)),
            arguments(
                PREPARE_CALL,
                4,
                (Kind) connection -> connection.prepareCall(CALL, FORWARD, READ_ONLY, HOLD)));

    @ParameterizedTest(name = "[{index}] {0} with {1} arguments")
    @FieldSource("STATEMENT_KINDS")
    @DisplayName("on a connection of the pool its call wants, reaches the same driver method")
    void samePoolRuns(String method, int arity, Kind statement) throws SQLException {
      var previous = routing.enter(BATCH);
      try (var connection = router.getConnection()) {
        statement.accept(connection);
      } finally {
        routing.exit(previous);
      }

      assertThat(statementCalls(method, arity)).isOne();
    }

    @ParameterizedTest(name = "[{index}] {0} with {1} arguments")
    @FieldSource("STATEMENT_KINDS")
    @DisplayName(
        "on a connection of another pool fails with SX-DATA-1008 before the driver sees it")
    void otherPoolFails(String method, int arity, Kind statement) throws SQLException {
      try (var connection = openOn(BATCH)) {
        assertThatThrownBy(() -> statement.accept(connection))
            .isInstanceOf(StratumException.class)
            .hasMessageContaining(DataErrorCode.POOL_MIXED.getCode());
      }

      assertThat(statementCalls(method, arity)).isZero();
    }
  }

  /** One way a connection makes a statement. */
  @FunctionalInterface
  interface Kind extends ThrowingConsumer<Connection> {}

  @Nested
  @DisplayName("the connection it returns")
  class ReturnedConnection {

    @Test
    @DisplayName("passes every other call to the pool's connection, and its failure unchanged")
    void delegates() throws SQLException {
      var failure = new SQLException("commit failed");
      doThrow(failure).when(batch.connection).commit();
      try (var connection = openOn(BATCH)) {
        assertThat(connection.getCatalog()).isEqualTo(MockDatabase.DATABASE);
        assertThatThrownBy(connection::commit).isSameAs(failure);
      }
    }

    @Test
    @DisplayName("equals a connection holding the same pool connection only, with its hash")
    void equality() throws SQLException {
      try (var first = openOn(BATCH);
          var again = openOn(BATCH);
          var other = router.getConnection()) {
        assertThat(first).isEqualTo(again).hasSameHashCodeAs(again).isNotEqualTo(other);
        assertThat(first.equals(batch.connection)).isFalse();
      }
    }
  }

  @Nested
  @DisplayName("what it wraps")
  class Wrapping {

    @Test
    @DisplayName("is only itself, so the pools behind it are not counted twice")
    void onlyItself() throws SQLException {
      assertThat(router.isWrapperFor(PoolRouter.class)).isTrue();
      assertThat(router.unwrap(RoutingDataSource.class)).isSameAs(router);
      assertThat(router.isWrapperFor(HikariDataSource.class)).isFalse();
      assertThatThrownBy(() -> router.unwrap(HikariDataSource.class))
          .isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("marks it, and a lazy proxy over it, as Stratum's router, and no pool")
    void markedAsRouter() throws SQLException {
      var failing = mock(DataSource.class);
      when(failing.isWrapperFor(PoolRouter.class)).thenThrow(new SQLException("closed"));

      assertThat(PoolRouters.routes(router)).isTrue();
      assertThat(PoolRouters.routes(new LazyConnectionDataSourceProxy(router))).isTrue();
      assertThat(PoolRouters.routes(main.pool)).isFalse();
      assertThat(PoolRouters.routes(failing)).isFalse();
    }
  }
}
