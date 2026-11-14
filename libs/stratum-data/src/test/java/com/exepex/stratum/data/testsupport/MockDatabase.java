package com.exepex.stratum.data.testsupport;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * A pool whose connection reports a PostgreSQL database named {@value #DATABASE}, and hands out
 * plain, prepared and callable statements that return an empty result.
 */
public final class MockDatabase {

  /** The database's name, which the connection reports as its catalog. */
  public static final String DATABASE = "orders";

  /** The product name the connection's metadata reports. */
  public static final String PRODUCT = "PostgreSQL";

  /** The pool. */
  public final DataSource pool = mock(DataSource.class);

  /** The pool's connection. */
  public final Connection connection = mock(Connection.class);

  /** The connection's metadata. */
  public final DatabaseMetaData metadata = mock(DatabaseMetaData.class);

  /** The plain statement the connection creates. */
  public final Statement statement = mock(Statement.class);

  /** The prepared statement the connection prepares. */
  public final PreparedStatement prepared = mock(PreparedStatement.class);

  /** The callable statement the connection prepares. */
  public final CallableStatement callable = mock(CallableStatement.class);

  /**
   * Sets up the mocks.
   *
   * @throws SQLException never; the mocks declare it
   */
  public MockDatabase() throws SQLException {
    var rows = mock(ResultSet.class);
    when(pool.getConnection()).thenReturn(connection);
    when(connection.getCatalog()).thenReturn(DATABASE);
    when(connection.getMetaData()).thenReturn(metadata);
    when(metadata.getDatabaseProductName()).thenReturn(PRODUCT);
    when(connection.createStatement()).thenReturn(statement);
    when(connection.prepareStatement(anyString())).thenReturn(prepared);
    when(connection.prepareCall(anyString())).thenReturn(callable);
    when(statement.executeQuery(anyString())).thenReturn(rows);
    when(prepared.executeQuery()).thenReturn(rows);
    when(callable.executeQuery()).thenReturn(rows);
  }
}
