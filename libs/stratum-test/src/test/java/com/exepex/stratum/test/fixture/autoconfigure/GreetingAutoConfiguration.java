package com.exepex.stratum.test.fixture.autoconfigure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Follows the rules: a configuration class that only declares beans. */
@Configuration(proxyBeanMethods = false)
public class GreetingAutoConfiguration {

  /**
   * Declares the greeting.
   *
   * @return the greeting
   */
  @Bean
  String greeting() {
    return "Hello";
  }
}
