package com.exepex.stratum.data.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.PostgresLoginTimeoutPostProcessor;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("JdbcBoundaryAutoConfiguration gives every PostgreSQL pool a login limit")
class PostgresLoginTimeoutAutoConfigurationTest {

  /** Stratum's default wait for a pooled connection, in milliseconds. */
  private static final long STRATUM_WAIT_MS = 3000;

  /** A context with the data module's boundary configuration and one PostgreSQL pool. */
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(JdbcBoundaryAutoConfiguration.class))
          .withBean("dataSource", HikariDataSource.class, this::postgresPool);

  /** Returns a PostgreSQL pool that has not started, with Stratum's wait of 3 seconds. */
  private HikariDataSource postgresPool() {
    var pool = new HikariDataSource();
    pool.setJdbcUrl("jdbc:postgresql://orders-db:5432/orders");
    pool.setConnectionTimeout(STRATUM_WAIT_MS);
    return pool;
  }

  @Test
  @DisplayName("the post-processor is a bean named stratumPostgresLoginTimeout and limits the pool")
  void limitsPool() {
    contextRunner.run(
        context ->
            assertThat(context)
                .hasNotFailed()
                .getBean(DataConstants.LOGIN_TIMEOUT)
                .isInstanceOf(PostgresLoginTimeoutPostProcessor.class));
    contextRunner.run(this::assertPoolLimited);
  }

  /** Checks that the pool, behind the JDBC boundary, got the login limit. */
  private void assertPoolLimited(AssertableApplicationContext context) throws SQLException {
    assertThat(
            context
                .getBean("dataSource", DataSource.class)
                .unwrap(HikariDataSource.class)
                .getDataSourceProperties())
        .containsEntry("loginTimeout", "3");
  }

  @Test
  @DisplayName("a service's bean of the same name replaces it")
  void serviceReplacesIt() {
    contextRunner
        .withBean(DataConstants.LOGIN_TIMEOUT, String.class, () -> "no limit")
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(PostgresLoginTimeoutPostProcessor.class));
  }

  @Test
  @DisplayName("without the PostgreSQL driver, or with the module off, there is none")
  void noneWithoutDriverOrModule() {
    contextRunner
        .withClassLoader(new FilteredClassLoader("org.postgresql"))
        .run(
            context ->
                assertThat(context).doesNotHaveBean(PostgresLoginTimeoutPostProcessor.class));
    contextRunner
        .withPropertyValues(DataConstants.ENABLED + "=false")
        .run(
            context ->
                assertThat(context).doesNotHaveBean(PostgresLoginTimeoutPostProcessor.class));
  }
}
