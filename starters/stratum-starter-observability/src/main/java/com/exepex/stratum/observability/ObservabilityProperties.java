package com.exepex.stratum.observability;

import com.exepex.stratum.core.properties.StratumCustomizers;
import com.exepex.stratum.core.properties.StratumPropertiesResolver;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.env.Environment;

/**
 * The settings of the observability module, bound to {@code stratum.observability.*} and then
 * changed by the service's {@link ObservabilityPropertiesCustomizer} classes, which win. The
 * descriptions of the components are what IDEs show, so they are plain sentences. A Spring Boot
 * property the service sets itself wins over the setting that gives it Stratum's default.
 *
 * @param actuatorEndpointsExposedOverHttp The actuator endpoints reachable over HTTP. Sets Spring
 *     Boot's management.endpoints.web.exposure.include. Defaults to health and info; add others
 *     only behind authentication.
 * @param recordTimingsAsHistograms Whether every timer and distribution summary is stored as an
 *     exponential histogram, so any percentile can be read for one pod or for all pods; false keeps
 *     only count, sum and max. Sets Spring Boot's
 *     management.metrics.distribution.percentiles-histogram.all. Defaults to true.
 * @param traceKafkaAndRabbitmqMessages Whether Kafka and RabbitMQ messages are traced and timed
 *     when they are sent and received. Sets Spring Boot's observation-enabled switches of the Kafka
 *     and RabbitMQ templates and listeners. Defaults to true.
 * @param tags Which metric tags are refused.
 */
@Builder(toBuilder = true)
@ConfigurationProperties(prefix = ObservabilityConstants.PREFIX)
public record ObservabilityProperties(
    @DefaultValue({"health", "info"}) List<@NotBlank String> actuatorEndpointsExposedOverHttp,
    @DefaultValue("true") boolean recordTimingsAsHistograms,
    @DefaultValue("true") boolean traceKafkaAndRabbitmqMessages,
    @DefaultValue @Valid Tags tags) {

  /** Keeps an unchangeable copy of the endpoints. */
  public ObservabilityProperties {
    actuatorEndpointsExposedOverHttp = List.copyOf(actuatorEndpointsExposedOverHttp);
  }

  /**
   * Returns the settings the module uses: {@code stratum.observability.*} with the defaults for
   * anything unset, changed by every {@link ObservabilityPropertiesCustomizer} in {@code
   * META-INF/spring.factories}, then validated. Spring Boot's defaults are given before any bean
   * exists, so this makes its own validator.
   *
   * @param environment the application's environment
   * @return the settings
   * @throws jakarta.validation.ConstraintViolationException {@code SX-CORE-1003} if a setting
   *     breaks its rules
   */
  public static ObservabilityProperties resolve(Environment environment) {
    var boundProperties =
        Binder.get(environment)
            .bindOrCreate(ObservabilityConstants.PREFIX, ObservabilityProperties.class);
    var customizers = StratumCustomizers.load(ObservabilityPropertiesCustomizer.class);
    try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
      return new StratumPropertiesResolver(validatorFactory.getValidator())
          .resolve(boundProperties, customizers.stream());
    }
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
