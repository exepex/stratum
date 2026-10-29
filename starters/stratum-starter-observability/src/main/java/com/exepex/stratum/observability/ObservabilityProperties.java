package com.exepex.stratum.observability;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The settings of the observability module, bound to {@code stratum.observability.*} and then
 * changed by the service's {@link ObservabilityPropertiesCustomizer} classes, which win.
 *
 * @param tags Which metric tags are refused.
 */
@Builder(toBuilder = true)
@ConfigurationProperties(prefix = ObservabilityConstants.PREFIX)
public record ObservabilityProperties(@DefaultValue @Valid Tags tags) {

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
