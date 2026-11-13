package com.exepex.stratum.data;

import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

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
  static final long HIKARI_DEFAULT_MS = 30_000;

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
}
