package com.exepex.stratum.test.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.test.PostgresContainerConfiguration;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(classes = PostgresContainerConfigurationIntegrationTest.DatabaseApplication.class)
@Import(PostgresContainerConfiguration.class)
@DisplayName("PostgresContainerConfiguration connects a service's tests to PostgreSQL")
class PostgresContainerConfigurationIntegrationTest {

  /** The application's data source, which no {@code spring.datasource.*} property configures. */
  @Autowired private DataSource dataSource;

  @Test
  @DisplayName("The data source reaches the pinned PostgreSQL 18.6 with no connection properties")
  void connectsToPinnedPostgresWithoutConnectionProperties() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
      assertThat(connection.getMetaData().getDatabaseProductVersion()).isEqualTo("18.6");
    }
  }

  /** A service with a database and nothing else. */
  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class DatabaseApplication {}
}
