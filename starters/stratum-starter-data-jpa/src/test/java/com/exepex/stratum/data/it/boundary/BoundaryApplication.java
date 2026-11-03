package com.exepex.stratum.data.it.boundary;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** A service on the tables of boundary-schema.sql, with a meter registry for the pool metrics. */
@SpringBootApplication
public class BoundaryApplication {

  /** Empties every table of boundary-schema.sql, so that each test starts from no rows. */
  public static final String EMPTY_TABLES =
      "TRUNCATE customers, payments, invoices, readings, tags";

  /**
   * Returns the registry that Spring Boot's pool metrics bind to.
   *
   * @return the registry
   */
  @Bean
  MeterRegistry meterRegistry() {
    return new SimpleMeterRegistry();
  }
}
