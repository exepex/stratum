package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import lombok.RequiredArgsConstructor;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;

/**
 * Gives the connection that Spring Boot builds for Flyway the clean errors of the JDBC boundary.
 * Spring Boot builds it from Flyway's own {@code spring.flyway.url} or {@code user}, or from
 * Flyway's connection details, such as those of {@code @ServiceConnection} or Docker Compose.
 * Flyway's use of a data source bean needs nothing more: that bean already has its boundary.
 */
@StratumInternal
@RequiredArgsConstructor
public class FlywayBoundaryCustomizer implements FlywayConfigurationCustomizer {

  /** The post-processor whose boundary wraps the data source bean; none means no wrapping. */
  private final ObjectProvider<JdbcBoundaryPostProcessor> boundary;

  /**
   * Replaces the connection Spring Boot built for Flyway with its boundary.
   *
   * @param configuration Flyway's configuration, with the data source Spring Boot chose
   */
  @Override
  public void customize(FluentConfiguration configuration) {
    boundary.ifUnique(
        postProcessor ->
            configuration.dataSource(
                postProcessor.migrationDataSource(configuration.getDataSource())));
  }
}
