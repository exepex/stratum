package com.exepex.stratum.test.fixture.autoconfigure;

import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Follows the rules: a configuration class that only declares beans, behind the module switch. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnStratumModule("greeting")
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
