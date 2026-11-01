package com.exepex.stratum.observability;

import com.exepex.stratum.core.properties.StratumCustomizers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.env.Environment;

/**
 * The settings of the observability module, bound to {@code stratum.observability.*} and then
 * changed by the service's {@link ObservabilityPropertiesCustomizer} classes, which win. The
 * descriptions of the components are what IDEs show, so they are plain sentences. What Spring Boot
 * already has a key for, such as the exposed actuator endpoints, is set with Spring Boot's key.
 *
 * @param tags Which metric tags are refused.
 */
@Builder(toBuilder = true)
@ConfigurationProperties(prefix = ObservabilityConstants.PREFIX)
public record ObservabilityProperties(@DefaultValue @Valid Tags tags) {

  /**
   * Returns the settings the module uses: {@code stratum.observability.*} with the defaults for
   * anything unset, changed by every {@link ObservabilityPropertiesCustomizer} in {@code
   * META-INF/spring.factories}, then validated. Spring Boot's defaults are given before any bean
   * exists, so this makes its own validator.
   *
   * @param environment the application's environment
   * @return the settings
   * @throws com.exepex.stratum.core.error.StratumException {@code SX-CORE-1003} if a setting breaks
   *     its rules
   */
  public static ObservabilityProperties resolve(Environment environment) {
    return StratumCustomizers.resolveSettings(
        environment,
        ObservabilityConstants.PREFIX,
        ObservabilityProperties.class,
        ObservabilityPropertiesCustomizer.class);
  }

  /**
   * Which metric tags are refused, besides Stratum's built-in ID names. Case, {@code _}, {@code -}
   * and {@code .} are ignored in every name.
   *
   * @param extraForbidden More tag names, such as tenantId; a metric with one of them is dropped.
   *     Defaults to none.
   * @param allowed Tag names that are kept even though they are refused by default, such as
   *     customerId for a service with a few dozen customers. Defaults to none.
   */
  @Builder(toBuilder = true)
  public record Tags(
      @DefaultValue({}) List<@NotBlank String> extraForbidden,
      @DefaultValue({}) List<@NotBlank String> allowed) {

    /** Keeps unchangeable copies of the names. */
    public Tags {
      extraForbidden = List.copyOf(extraForbidden);
      allowed = List.copyOf(allowed);
    }
  }
}
