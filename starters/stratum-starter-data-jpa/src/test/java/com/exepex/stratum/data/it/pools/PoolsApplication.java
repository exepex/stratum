package com.exepex.stratum.data.it.pools;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.map.repository.config.EnableMapRepositories;

/**
 * A service with one JPA entity, whose repositories are mapped to main and the pool batch, and one
 * map repository in the mapped package, which Spring Data gives to its map module by its type.
 */
@SpringBootApplication
@EnableMapRepositories
public class PoolsApplication {

  /**
   * Returns the meter registry Spring Boot's pool metrics bind to, as the observability starter
   * gives a service one.
   *
   * @return the registry
   */
  @Bean
  MeterRegistry meterRegistry() {
    return new SimpleMeterRegistry();
  }
}
