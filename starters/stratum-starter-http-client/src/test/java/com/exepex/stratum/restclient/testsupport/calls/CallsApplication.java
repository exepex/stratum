package com.exepex.stratum.restclient.testsupport.calls;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * An application whose package holds the clients of the call log and meter tests, with a meter
 * registry, as a service with Spring Boot's Actuator has.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class CallsApplication {

  /**
   * Creates the registry the clients' meters are made in.
   *
   * @return the registry
   */
  @Bean
  public MeterRegistry meterRegistry() {
    return new SimpleMeterRegistry();
  }
}
