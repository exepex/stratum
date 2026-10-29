package com.exepex.stratum.observability;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The settings of the observability module, bound to {@code stratum.observability.*}. A {@code
 * StratumPropertiesCustomizer<ObservabilityProperties>} bean can change them in Java, and wins.
 *
 * @param tags which metric tags are refused
 */
@Builder(toBuilder = true)
@ConfigurationProperties(prefix = ObservabilityConstants.PREFIX)
public record ObservabilityProperties(@DefaultValue @Valid Tags tags) {

  /**
   * Which metric tags are refused, on top of Stratum's built-in ID names, which cannot be removed.
   *
   * @param extraForbidden more tag names, such as {@code tenantId}; a metric with one of them is
   *     dropped. Case, {@code _} and {@code -} are ignored. Defaults to none.
   */
  @Builder(toBuilder = true)
  public record Tags(@DefaultValue({}) List<@NotBlank String> extraForbidden) {

    /** Keeps an unchangeable copy of the names. */
    public Tags {
      extraForbidden = List.copyOf(extraForbidden);
    }
  }
}
