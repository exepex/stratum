package com.exepex.stratum.web;

import com.exepex.stratum.core.properties.StratumCustomizers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Map;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.env.Environment;

/**
 * The settings of the web module, bound to {@code stratum.web.*} and then changed by the service's
 * {@link WebPropertiesCustomizer} classes, which win. Both web starters read the same settings. The
 * descriptions of the components are what IDEs show, so they are plain sentences.
 *
 * @param error How errors are answered.
 */
@Builder(toBuilder = true)
@ConfigurationProperties(prefix = WebConstants.PREFIX)
public record WebProperties(@DefaultValue @Valid ErrorSettings error) {

  /**
   * Returns the settings the module uses: {@code stratum.web.*} with the defaults for anything
   * unset, changed by every {@link WebPropertiesCustomizer} in {@code META-INF/spring.factories},
   * then validated.
   *
   * @param environment the application's environment
   * @return the settings
   * @throws jakarta.validation.ConstraintViolationException {@code SX-CORE-1003} if a setting
   *     breaks its rules
   */
  public static WebProperties resolve(Environment environment) {
    return StratumCustomizers.resolveSettings(
        environment, WebConstants.PREFIX, WebProperties.class, WebPropertiesCustomizer.class);
  }

  /**
   * How errors are answered.
   *
   * @param statuses The HTTP status, from 400 to 599, of each of the service's error codes that a
   *     StratumException carries, such as ORD-0001 to 409. A code with no status is answered with
   *     500 and logged. Defaults to none.
   */
  @Builder(toBuilder = true)
  public record ErrorSettings(@DefaultValue Map<String, @Min(400) @Max(599) Integer> statuses) {

    /** Keeps an unchangeable copy of the statuses. */
    public ErrorSettings {
      statuses = Map.copyOf(statuses);
    }
  }
}
