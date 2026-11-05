package com.exepex.stratum.test;

import com.exepex.stratum.core.api.StratumInternal;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Starts the same PostgreSQL database as {@link PostgresContainerConfiguration} but gives Spring
 * Boot only the {@code DataSource}'s connection, so that Flyway and Liquibase connect the way a
 * service's {@code spring.flyway.*} and {@code spring.liquibase.*} keys say. Only for Stratum's
 * tests of how migrations connect; a service's tests use {@link PostgresContainerConfiguration}.
 */
@StratumInternal
@TestConfiguration(proxyBeanMethods = false)
public class PostgresJdbcContainerConfiguration {

  /**
   * Creates the PostgreSQL container, which Spring Boot starts and connects the data source to.
   *
   * @return the container
   */
  @Bean
  @ServiceConnection(type = JdbcConnectionDetails.class)
  PostgreSQLContainer postgresContainer() {
    return new PostgreSQLContainer(ContainerImages.POSTGRES_IMAGE);
  }
}
