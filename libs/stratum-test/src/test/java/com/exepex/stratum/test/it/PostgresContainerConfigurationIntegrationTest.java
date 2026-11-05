package com.exepex.stratum.test.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.test.PostgresContainerConfiguration;
import com.exepex.stratum.test.PostgresJdbcContainerConfiguration;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@DisplayName(
    "Stratum's PostgreSQL container configurations connect a test application to the pinned"
        + " PostgreSQL")
class PostgresContainerConfigurationIntegrationTest {

  /**
   * Checks that the data source, which no {@code spring.datasource.*} property configures, reaches
   * PostgreSQL.
   *
   * @param dataSource the application's data source
   * @throws SQLException when no connection can be opened
   */
  void assertReachesPinnedPostgres(DataSource dataSource) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
    }
  }

  @Nested
  @SpringBootTest(classes = DatabaseApplication.class)
  @Import(PostgresContainerConfiguration.class)
  @DisplayName("with PostgresContainerConfiguration")
  class WithPostgresContainerConfiguration {

    @Autowired private DataSource dataSource;

    @Test
    @DisplayName("The data source reaches the pinned PostgreSQL with no connection properties")
    void connectsToPinnedPostgresWithoutConnectionProperties() throws SQLException {
      assertReachesPinnedPostgres(dataSource);
    }
  }

  @Nested
  @SpringBootTest(classes = DatabaseApplication.class)
  @Import(PostgresJdbcContainerConfiguration.class)
  @DisplayName("with PostgresJdbcContainerConfiguration")
  class WithPostgresJdbcContainerConfiguration {

    @Autowired private DataSource dataSource;

    @Test
    @DisplayName("The data source reaches the pinned PostgreSQL with no connection properties")
    void connectsToPinnedPostgresWithoutConnectionProperties() throws SQLException {
      assertReachesPinnedPostgres(dataSource);
    }
  }

  /** A service with a database and nothing else. */
  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class DatabaseApplication {}
}
