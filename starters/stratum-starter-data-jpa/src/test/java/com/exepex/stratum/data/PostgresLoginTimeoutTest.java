package com.exepex.stratum.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.Properties;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("A connection to PostgreSQL gets a login limit unless the service set its own")
class PostgresLoginTimeoutTest {

  /** The driver's property, by the only name it reads. */
  private static final String LOGIN_TIMEOUT = "loginTimeout";

  /** A PostgreSQL URL with no login limit of its own. */
  private static final String POSTGRES_URL = "jdbc:postgresql://orders-db:5432/orders";

  /** Stratum's default wait for a pooled connection, in milliseconds. */
  private static final long STRATUM_WAIT_MS = 3000;

  /** The same wait, as the environment holds it. */
  private static final String STRATUM_WAIT = String.valueOf(STRATUM_WAIT_MS);

  /** Each pool wait and the login limit it gives, in whole seconds as the driver reads them. */
  private static final List<Arguments> WAITS =
      List.of(
          arguments(STRATUM_WAIT_MS, "3"),
          arguments(2600L, "3"),
          arguments(30_000L, "30"),
          arguments(250L, "1"));

  /** Returns a pool that has not started, with a URL and a wait. */
  private HikariDataSource pool(String url, long connectionTimeoutMs) {
    var pool = new HikariDataSource();
    pool.setJdbcUrl(url);
    pool.setConnectionTimeout(connectionTimeoutMs);
    return pool;
  }

  /** Runs Stratum's post-processor on a bean named dataSource. */
  private Object processed(Object bean) {
    return new PostgresLoginTimeoutPostProcessor()
        .postProcessAfterInitialization(bean, "dataSource");
  }

  /** Returns the login limit in a pool's data-source properties, or null. */
  private Object loginTimeoutOf(HikariDataSource pool) {
    return pool.getDataSourceProperties().get(LOGIN_TIMEOUT);
  }

  @Nested
  @DisplayName("on a HikariCP pool")
  class Pool {

    @ParameterizedTest(name = "{0} ms gives \"{1}\"")
    @FieldSource("com.exepex.stratum.data.PostgresLoginTimeoutTest#WAITS")
    @DisplayName("the limit is the pool's own wait in whole seconds, stored as a string")
    void limitFollowsPoolWait(long connectionTimeoutMs, String seconds) {
      try (var pool = pool(POSTGRES_URL, connectionTimeoutMs)) {
        assertThat(processed(pool)).isSameAs(pool);

        assertThat(loginTimeoutOf(pool)).isInstanceOf(String.class).isEqualTo(seconds);
      }
    }

    @Test
    @DisplayName("a loginTimeout in the data-source properties is kept, 0 included")
    void keepsServiceLimit() {
      try (var pool = pool(POSTGRES_URL, STRATUM_WAIT_MS)) {
        pool.addDataSourceProperty(LOGIN_TIMEOUT, "0");

        processed(pool);

        assertThat(loginTimeoutOf(pool)).isEqualTo("0");
      }
    }

    @Test
    @DisplayName("a key in another case, which the driver does not read, still gets the limit")
    void otherCaseStillLimited() {
      try (var pool = pool(POSTGRES_URL, STRATUM_WAIT_MS)) {
        pool.addDataSourceProperty("logintimeout", "60");

        processed(pool);

        assertThat(loginTimeoutOf(pool)).isEqualTo("3");
      }
    }

    @Test
    @DisplayName("a loginTimeout in the URL stays in the URL, where the driver reads it first")
    void keepsUrlLimit() {
      var url = POSTGRES_URL + "?loginTimeout=10";
      try (var pool = pool(url, STRATUM_WAIT_MS)) {
        processed(pool);

        assertThat(pool.getJdbcUrl()).isEqualTo(url);
      }
    }

    @Test
    @DisplayName("a pool whose wait has no limit gets no login limit either, as in HikariCP")
    void noLimitForUnlimitedWait() {
      try (var pool = pool(POSTGRES_URL, 0)) {
        processed(pool);

        assertThat(loginTimeoutOf(pool)).isNull();
      }
    }

    @Test
    @DisplayName("a pool of another database, or without a URL, is left as it is")
    void leavesOtherDatabases() {
      try (var other = pool("jdbc:h2:mem:orders", STRATUM_WAIT_MS);
          var noUrl = new HikariDataSource()) {
        processed(other);
        processed(noUrl);

        assertThat(other.getDataSourceProperties()).isEmpty();
        assertThat(noUrl.getDataSourceProperties()).isEmpty();
      }
    }

    @Test
    @DisplayName("a data source that is not a HikariCP pool is returned as it is")
    void leavesOtherDataSources() {
      var dataSource = mock(DataSource.class);

      assertThat(processed(dataSource)).isSameAs(dataSource);
    }

    @Test
    @DisplayName("a pool that has started, which HikariCP seals, is skipped without an error")
    void skipsStartedPool() {
      var started = mock(HikariDataSource.class);
      when(started.getJdbcUrl()).thenReturn(POSTGRES_URL);
      when(started.getDataSourceProperties()).thenReturn(new Properties());
      when(started.getConnectionTimeout()).thenReturn(STRATUM_WAIT_MS);
      doThrow(new IllegalStateException("sealed"))
          .when(started)
          .addDataSourceProperty(any(), any());

      assertThat(processed(started)).isSameAs(started);
    }

    @Test
    @DisplayName("it runs before the JDBC boundary wraps the pool")
    void runsBeforeBoundary() {
      assertThat(new PostgresLoginTimeoutPostProcessor().getOrder())
          .isLessThan(new JdbcBoundaryPostProcessor(name -> true).getOrder());
    }
  }

  @Nested
  @DisplayName("on the connection Spring Boot builds for Flyway or Liquibase")
  class Migration {

    /** Returns a connection as Spring Boot builds it: a URL and no properties at all. */
    private SimpleDriverDataSource migrationConnection(String url) {
      var connection = new SimpleDriverDataSource();
      connection.setUrl(url);
      return connection;
    }

    /** Returns an environment with Boot's pool wait set. */
    private MockEnvironment poolWait(String connectionTimeoutMs) {
      return new MockEnvironment()
          .withProperty(DataConstants.CONNECTION_TIMEOUT, connectionTimeoutMs);
    }

    @Test
    @DisplayName("gets the limit of Boot's pool, in properties Stratum creates")
    void getsPoolLimit() {
      var connection = migrationConnection(POSTGRES_URL);

      new MigrationLoginTimeout(poolWait(STRATUM_WAIT)).limit(connection);

      assertThat(connection.getConnectionProperties())
          .isNotNull()
          .containsEntry(LOGIN_TIMEOUT, "3");
    }

    @Test
    @DisplayName("gets HikariCP's 30 seconds when Boot's pool wait is not set")
    void getsHikariDefault() {
      var unset = migrationConnection(POSTGRES_URL);
      var noEnvironment = migrationConnection(POSTGRES_URL);

      new MigrationLoginTimeout(new MockEnvironment()).limit(unset);
      new MigrationLoginTimeout(null).limit(noEnvironment);

      assertThat(unset.getConnectionProperties()).containsEntry(LOGIN_TIMEOUT, "30");
      assertThat(noEnvironment.getConnectionProperties()).containsEntry(LOGIN_TIMEOUT, "30");
    }

    @Test
    @DisplayName("keeps its own loginTimeout, and its other properties next to the limit")
    void keepsOwnProperties() {
      var own = migrationConnection(POSTGRES_URL);
      var ownLimit = new Properties();
      ownLimit.setProperty(LOGIN_TIMEOUT, "10");
      own.setConnectionProperties(ownLimit);
      var other = migrationConnection(POSTGRES_URL);
      var otherProperty = new Properties();
      otherProperty.setProperty("ApplicationName", "orders");
      other.setConnectionProperties(otherProperty);

      new MigrationLoginTimeout(poolWait(STRATUM_WAIT)).limit(own);
      new MigrationLoginTimeout(poolWait(STRATUM_WAIT)).limit(other);

      assertThat(own.getConnectionProperties()).containsOnly(entry(LOGIN_TIMEOUT, "10"));
      assertThat(other.getConnectionProperties())
          .containsOnly(entry("ApplicationName", "orders"), entry(LOGIN_TIMEOUT, "3"));
    }

    @Test
    @DisplayName("gets no limit when Boot's pool wait has none, or the database is another")
    void leavesUnlimitedAndOtherDatabases() {
      var unlimited = migrationConnection(POSTGRES_URL);
      var other = migrationConnection("jdbc:h2:mem:orders");

      new MigrationLoginTimeout(poolWait("0")).limit(unlimited);
      new MigrationLoginTimeout(poolWait(STRATUM_WAIT)).limit(other);

      assertThat(unlimited.getConnectionProperties()).isNull();
      assertThat(other.getConnectionProperties()).isNull();
    }

    @Test
    @DisplayName("gets the limit through the boundary's hook, which Flyway and Liquibase call")
    void hookLimits() {
      var postProcessor = new JdbcBoundaryPostProcessor(name -> true);
      postProcessor.setBeanClassLoader(Thread.currentThread().getContextClassLoader());
      postProcessor.setEnvironment(poolWait(STRATUM_WAIT));
      var connection = migrationConnection(POSTGRES_URL);

      postProcessor.migrationDataSource(connection);

      assertThat(connection.getConnectionProperties()).containsEntry(LOGIN_TIMEOUT, "3");
    }
  }
}
