package com.exepex.stratum.data.testsupport;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import javax.sql.DataSource;
import lombok.experimental.UtilityClass;

/** Databases that answer only who they are, shared by the data module's tests. */
@UtilityClass
public class DataTestData {

  /**
   * Returns a PostgreSQL database on a server, which Spring Boot does not count as in memory.
   *
   * @return the database
   * @throws SQLException never; the mock declares it
   */
  public static DataSource serverDatabase() throws SQLException {
    return database("PostgreSQL", "jdbc:postgresql://orders-db:5432/orders");
  }

  /**
   * Returns an H2 database in memory, which Spring Boot counts as embedded.
   *
   * @return the database
   * @throws SQLException never; the mock declares it
   */
  public static DataSource inMemoryDatabase() throws SQLException {
    return database("H2", "jdbc:h2:mem:orders");
  }

  /** Returns a database whose connection names the product and the URL. */
  private static DataSource database(String productName, String url) throws SQLException {
    var metaData = mock(DatabaseMetaData.class);
    when(metaData.getDatabaseProductName()).thenReturn(productName);
    when(metaData.getURL()).thenReturn(url);
    var connection = mock(Connection.class);
    when(connection.getMetaData()).thenReturn(metaData);
    var dataSource = mock(DataSource.class);
    when(dataSource.getConnection()).thenReturn(connection);
    return dataSource;
  }
}
