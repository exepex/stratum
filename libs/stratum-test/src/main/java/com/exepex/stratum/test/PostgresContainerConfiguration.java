package com.exepex.stratum.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Starts a PostgreSQL database in Docker for a test and points the application's {@code DataSource}
 * at it, so a test sets no {@code spring.datasource.*} property. Import it with
 * {@code @Import(PostgresContainerConfiguration.class)}. The container lives as long as the test's
 * application context, which Spring reuses across test classes with the same configuration.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerConfiguration {

  /**
   * Creates the PostgreSQL container, which Spring Boot starts and connects the application to. Its
   * image is pinned in the root {@code pom.xml}, never {@code latest}.
   *
   * @return the container
   */
  @Bean
  @ServiceConnection
  PostgreSQLContainer postgresContainer() {
    return new PostgreSQLContainer(ContainerImages.POSTGRES);
  }
}
