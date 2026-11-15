package com.exepex.stratum.data.routing;

import static com.exepex.stratum.data.testsupport.NamedPoolTestData.BATCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.data.DataErrorCode;
import com.exepex.stratum.data.PoolRouter;
import com.exepex.stratum.data.PoolRouters;
import com.exepex.stratum.data.testsupport.MockDatabase;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.assertj.core.api.ThrowingConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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

  @Nested
  @DisplayName("a statement")
  class Statements {

    /** Each way a connection makes a statement. */
    private static final List<ThrowingConsumer<Connection>> STATEMENT_KINDS =
        List.of(
            Connection::createStatement,
            connection -> connection.prepareStatement("SELECT 1"),
            connection -> connection.prepareCall("{call p()}"));

    @ParameterizedTest
    @FieldSource("STATEMENT_KINDS")
    @DisplayName("runs on a connection of the pool its call wants")
    void samePoolRuns(ThrowingConsumer<Connection> statement) throws SQLException {
      var previous = routing.enter(BATCH);
      try (var connection = router.getConnection()) {
        statement.accept(connection);

        assertThat(connection.isClosed()).isFalse();
      } finally {
        routing.exit(previous);
      }
    }

    @ParameterizedTest
    @FieldSource("STATEMENT_KINDS")
    @DisplayName(
        "on a connection of another pool fails with SX-DATA-1008 before the driver sees it")
    void otherPoolFails(ThrowingConsumer<Connection> statement) throws SQLException {
      try (var connection = openOn(BATCH)) {
        assertThatThrownBy(() -> statement.accept(connection))
            .isInstanceOf(StratumException.class)
            .hasMessageContaining(DataErrorCode.POOL_MIXED.getCode());
      }
      verify(batch.connection, never()).createStatement();
      verify(batch.connection, never()).prepareStatement(anyString());
      verify(batch.connection, never()).prepareCall(anyString());
    }
  }

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
