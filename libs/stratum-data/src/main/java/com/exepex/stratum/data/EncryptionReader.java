package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import java.sql.Connection;
import java.sql.SQLException;

/** Tells whether a connection to one kind of database is encrypted. */
@StratumInternal
public interface EncryptionReader {

  /** How long the query that asks the database may run, in seconds. */
  int QUERY_TIMEOUT_SECONDS = 5;

  /**
   * Returns whether this reader knows the database.
   *
   * @param databaseProductName the name the driver gives the database
   * @return whether it can ask that database
   */
  boolean reads(String databaseProductName);

  /**
   * Asks the database whether this connection's session is encrypted.
   *
   * @param connection a connection to the database this reader knows
   * @return whether the session is encrypted
   * @throws SQLException if the database cannot answer
   */
  boolean encrypted(Connection connection) throws SQLException;
}
