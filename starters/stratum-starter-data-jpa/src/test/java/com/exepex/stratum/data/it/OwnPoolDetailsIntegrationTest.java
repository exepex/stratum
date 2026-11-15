package com.exepex.stratum.data.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.data.it.boundary.BoundaryApplication;
import com.exepex.stratum.test.PostgresContainerConfiguration;
import javax.sql.DataSource;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    classes = {
      BoundaryApplication.class,
      PostgresContainerConfiguration.class,
      OwnPoolDetailsIntegrationTest.BatchFromVault.class,
      OwnPoolDetailsIntegrationTest.MainPool.class
    },
    properties = "spring.jpa.hibernate.ddl-auto=none")
@DisplayName(
    "On PostgreSQL, a service with its own batchJdbcConnectionDetails connects main in tests")
class OwnPoolDetailsIntegrationTest {

  /** The database the pool batch connects to, which PostgreSQL always has. */
  private static final String BATCH_DATABASE = "postgres";

  /** The service, with batch's connection from its own bean and main's from the test's. */
  @Autowired private ApplicationContext application;

  /** The main pool's container. */
  @Autowired private PostgreSQLContainer postgresContainer;

  /** Returns the name of the database a data source bean connects to. */
  private String databaseOf(String beanName) {
    return JdbcClient.create(application.getBean(beanName, DataSource.class))
        .sql("SELECT current_database()")
        .query(String.class)
        .single();
  }

  @Nested
  @DisplayName("connections")
  class Connections {

    @Test
    @DisplayName("the main pool connects to the container through the test's own bean")
    void mainConnects() {
      assertThat(databaseOf("dataSource")).isEqualTo(postgresContainer.getDatabaseName());
    }

    @Test
    @DisplayName("the pool batch connects through the service's own bean")
    void batchConnects() {
      assertThat(databaseOf("batchDataSource")).isEqualTo(BATCH_DATABASE);
    }
  }

  /** A connection, as a service writes its own. A test database's password is no secret. */
  @Getter
  @RequiredArgsConstructor
  static final class Connection implements JdbcConnectionDetails {

    /** The JDBC URL. */
    private final String jdbcUrl;

    /** The user name. */
    private final String username;

    /** The password. */
    private final String password;

    /**
     * Returns the connection to a database of the container.
     *
     * @param container the container
     * @param database the database's name
     * @return the connection
     */
    static Connection to(PostgreSQLContainer container, String database) {
      return new Connection(
          container.getJdbcUrl().replace("/" + container.getDatabaseName(), "/" + database),
          container.getUsername(),
          container.getPassword());
    }
  }

  /** The service's own connection of the pool batch, as it declares it in production. */
  @Configuration(proxyBeanMethods = false)
  static class BatchFromVault {

    /**
     * Returns the pool batch's connection, which is no default candidate.
     *
     * @param postgresContainer the container, standing in for the service's vault
     * @return the connection
     */
    @Bean(defaultCandidate = false)
    JdbcConnectionDetails batchJdbcConnectionDetails(PostgreSQLContainer postgresContainer) {
      return Connection.to(postgresContainer, BATCH_DATABASE);
    }
  }

  /**
   * The main pool's connection in the test: Spring Boot gives the container none while the
   * service's batchJdbcConnectionDetails exists.
   */
  @TestConfiguration(proxyBeanMethods = false)
  static class MainPool {

    /**
     * Returns the main pool's connection to the container.
     *
     * @param postgresContainer the container
     * @return the connection
     */
    @Bean
    JdbcConnectionDetails mainConnectionDetails(PostgreSQLContainer postgresContainer) {
      return Connection.to(postgresContainer, postgresContainer.getDatabaseName());
    }
  }
}
