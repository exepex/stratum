package com.exepex.stratum.data;

import java.util.Properties;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * Gives a connection to PostgreSQL a login limit, unless the service set its own. The PostgreSQL
 * driver's {@code loginTimeout} defaults to no limit and ignores the one HikariCP sets through
 * {@code DriverManager}, so a database that accepts the connection and never answers would hold the
 * caller for good. A {@code loginTimeout} in the URL still wins, since the driver reads the URL
 * first.
 */
@UtilityClass
class PostgresLoginTimeout {

  /** The driver's property, which it reads by this exact name only. */
  static final String LOGIN_TIMEOUT = "loginTimeout";

  /** The start of every PostgreSQL JDBC URL. */
  private static final String URL_PREFIX = "jdbc:postgresql:";

  /** HikariCP's own wait when the service sets none, in milliseconds. */
  private static final long HIKARI_DEFAULT_MS = 30_000;

  /** What HikariCP adds before it turns its wait into whole seconds of login limit. */
  private static final long ROUNDING_MS = 500;

  /** Milliseconds in one second. */
  private static final long MS_PER_SECOND = 1000;

  /**
   * Returns whether the URL leads to PostgreSQL.
   *
   * @param url a JDBC URL, or {@code null}
   * @return {@code true} for a PostgreSQL URL
   */
  boolean appliesTo(@Nullable String url) {
    return url != null && url.startsWith(URL_PREFIX);
  }

  /**
   * Returns whether a pool wait leaves the login without a limit: HikariCP reads 0 as no limit, and
   * stores it as {@link Integer#MAX_VALUE}.
   *
   * @param connectionTimeoutMs the pool's wait for a connection, in milliseconds
   * @return {@code true} when the service chose no limit
   */
  boolean unlimited(long connectionTimeoutMs) {
    return connectionTimeoutMs == 0 || connectionTimeoutMs == Integer.MAX_VALUE;
  }

  /**
   * Returns the login limit for a pool wait, rounded as HikariCP rounds its own and at least 1
   * second. It is a string, because the driver drops every property whose value is not one.
   *
   * @param connectionTimeoutMs the pool's wait for a connection, in milliseconds
   * @return the limit in whole seconds
   */
  String seconds(long connectionTimeoutMs) {
    return String.valueOf(Math.max(1, (ROUNDING_MS + connectionTimeoutMs) / MS_PER_SECOND));
  }

  /**
   * Gives the connection Spring Boot builds for Flyway or Liquibase the login limit of Boot's pool,
   * {@code spring.datasource.hikari.connection-timeout}, unless its properties hold their own.
   *
   * @param connection the migration's connection
   * @param environment the application's environment, or {@code null} for HikariCP's default wait
   */
  void limit(SimpleDriverDataSource connection, @Nullable Environment environment) {
    long connectionTimeoutMs =
        environment == null
            ? HIKARI_DEFAULT_MS
            : environment.getProperty(
                DataConstants.CONNECTION_TIMEOUT, Long.class, HIKARI_DEFAULT_MS);
    var current = connection.getConnectionProperties();
    if (!appliesTo(connection.getUrl())
        || unlimited(connectionTimeoutMs)
        || current != null && current.containsKey(LOGIN_TIMEOUT)) {
      return;
    }
    // Boot builds this connection with no properties at all, so they are created here.
    var properties = new Properties();
    if (current != null) {
      properties.putAll(current);
    }
    properties.setProperty(LOGIN_TIMEOUT, seconds(connectionTimeoutMs));
    connection.setConnectionProperties(properties);
  }
}
