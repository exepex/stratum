package com.exepex.stratum.restclient.testsupport.timed;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * An application whose package holds only the reports client, so that the other tests' applications
 * need no address for it, with a meter registry, as a service with Spring Boot's Actuator has.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class TimedApplication {

  /**
   * Creates the registry the client's meters are made in.
   *
   * @return the registry
   */
  @Bean
  public MeterRegistry meterRegistry() {
    return new SimpleMeterRegistry();
  }
}
