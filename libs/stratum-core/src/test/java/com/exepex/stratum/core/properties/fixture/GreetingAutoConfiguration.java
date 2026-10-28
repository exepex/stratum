package com.exepex.stratum.core.properties.fixture;

import com.exepex.stratum.core.properties.StratumPropertiesCustomizer;
import com.exepex.stratum.core.properties.StratumPropertiesResolver;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Builder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;

/** Wires the test module the way a Stratum starter does: properties first, then Java. */
@AutoConfiguration
@EnableConfigurationProperties(GreetingAutoConfiguration.GreetingProperties.class)
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

  /**
   * The settings of the test module, bound to {@code stratum.greeting.*}. It is not
   * {@code @Validated}: the resolver validates the settings once Java configuration has run.
   *
   * @param message the text the module greets with. Defaults to {@code Hello}.
   * @param timeout how long the module waits for an answer. Defaults to 2 seconds.
   */
  @Builder(toBuilder = true)
  @ConfigurationProperties(prefix = "stratum.greeting")
  public record GreetingProperties(
      @DefaultValue("Hello") @NotBlank String message,
      @DefaultValue("2s") @NotNull Duration timeout) {}

  /**
   * The bean the test module builds from its settings.
   *
   * @param settings the settings the module resolved and uses
   */
  public record Greeter(GreetingProperties settings) {}
}
