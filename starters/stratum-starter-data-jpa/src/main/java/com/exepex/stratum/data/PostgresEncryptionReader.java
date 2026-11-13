package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Asks PostgreSQL, through the {@code pg_stat_ssl} view, whether the session of a connection uses
 * TLS. Behind PgBouncer it sees PgBouncer's connection to the server.
 */
@StratumInternal
public class PostgresEncryptionReader implements EncryptionReader {

  /** The name the PostgreSQL driver gives the database. */
  private static final String POSTGRESQL = "PostgreSQL";

  /** Reads whether the session of this connection uses TLS. */
  private static final String QUERY = "SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()";

  @Override
  public boolean reads(String databaseProductName) {
    return POSTGRESQL.equals(databaseProductName);
  }

  /**
   * Reads the session's row of {@code pg_stat_ssl}, within {@link #QUERY_TIMEOUT_SECONDS}.
   *
   * @param connection a connection to PostgreSQL
   * @return whether the session uses TLS
   * @throws SQLException if the view is missing or has no row for the session
   */
  @Override
  public boolean encrypted(Connection connection) throws SQLException {
    try (var statement = connection.prepareStatement(QUERY)) {
      statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
      try (var row = statement.executeQuery()) {
        if (!row.next()) {
          throw new SQLException("pg_stat_ssl has no row for this session");
        }
        return row.getBoolean(1);
      }
    }
  }
}
