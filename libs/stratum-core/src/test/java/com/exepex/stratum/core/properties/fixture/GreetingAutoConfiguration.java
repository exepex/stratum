package com.exepex.stratum.core.properties.fixture;

import com.exepex.stratum.core.properties.StratumPropertiesCustomizer;
import com.exepex.stratum.core.properties.StratumPropertiesResolver;
import jakarta.validation.Validator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Wires the test module the way a Stratum starter does: properties first, then Java. */
@AutoConfiguration
@EnableConfigurationProperties(GreetingProperties.class)
public class GreetingAutoConfiguration {

  /**
   * Builds the module's bean from its settings after the service's customizers have run.
   *
   * @param properties the settings bound from {@code stratum.greeting.*}
   * @param customizers the service's Java configuration for those settings
   * @param validator the application's validator
   * @return the module's bean
   */
  @Bean
  Greeter greeter(
      GreetingProperties properties,
      ObjectProvider<StratumPropertiesCustomizer<GreetingProperties>> customizers,
      Validator validator) {
    var resolver = new StratumPropertiesResolver(validator);
    return new Greeter(resolver.resolve(properties, customizers.orderedStream()));
  }
}
