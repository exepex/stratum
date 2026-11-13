package com.exepex.stratum.data;

import java.util.Properties;
import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * Gives the connection Spring Boot builds for Flyway or Liquibase the login limit of Boot's pool,
 * from {@code spring.datasource.hikari.connection-timeout}, unless its properties hold their own.
 */
final class MigrationLoginTimeout {

  /** Boot's pool wait, in milliseconds, which sets the limit. */
  private final long connectionTimeoutMs;

  /**
   * Reads Boot's pool wait.
   *
   * @param environment the application's environment, or {@code null} for HikariCP's default wait
   */
  MigrationLoginTimeout(@Nullable Environment environment) {
    connectionTimeoutMs =
        environment == null
            ? PostgresLoginTimeout.HIKARI_DEFAULT_MS
            : environment.getProperty(
                DataConstants.CONNECTION_TIMEOUT,
                Long.class,
                PostgresLoginTimeout.HIKARI_DEFAULT_MS);
  }

  /**
   * Adds the limit to a PostgreSQL migration connection that has none.
   *
   * @param connection the migration's connection
   */
  void limit(SimpleDriverDataSource connection) {
    var current = connection.getConnectionProperties();
    if (!PostgresLoginTimeout.appliesTo(connection.getUrl())
        || PostgresLoginTimeout.unlimited(connectionTimeoutMs)
        || current != null && current.containsKey(PostgresLoginTimeout.LOGIN_TIMEOUT)) {
      return;
    }
    // Boot builds this connection with no properties at all, so they are created here.
    var properties = new Properties();
    if (current != null) {
      properties.putAll(current);
    }
    properties.setProperty(
        PostgresLoginTimeout.LOGIN_TIMEOUT, PostgresLoginTimeout.seconds(connectionTimeoutMs));
    connection.setConnectionProperties(properties);
  }
}
