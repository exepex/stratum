package com.exepex.stratum.resilience;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.StratumModuleSwitch;
import com.exepex.stratum.core.properties.StratumDefaults;
import com.exepex.stratum.web.WebConstants;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.http.HttpStatus;

/**
 * Gives a refused request, {@code SX-RESILIENCE-0001}, its default status 503 in {@code
 * stratum.web.error.statuses}, unless {@code stratum.resilience.enabled=false}; a status the
 * service sets, such as 429, wins. The web starter answers the refusal with it.
 */
@StratumInternal
public class ResilienceEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  /** Stratum's default for the status of a refused request. */
  private static final Map<String, Object> DEFAULTS =
      Map.of(
          WebConstants.ERROR_STATUSES + "[" + ResilienceErrorCode.REQUEST_REFUSED.getCode() + "]",
          HttpStatus.SERVICE_UNAVAILABLE.value());

  /**
   * Runs right after the configuration files are read, so that the module switch and the service's
   * values are known.
   *
   * @return the order
   */
  @Override
  public int getOrder() {
    return ConfigDataEnvironmentPostProcessor.ORDER + 1;
  }

  /**
   * Adds the default below the service's own values, if the module is on.
   *
   * @param environment the application's environment
   * @param application the application being started
   */
  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (StratumModuleSwitch.isEnabled(environment, ResilienceConstants.MODULE)) {
      StratumDefaults.addBelowServiceValues(environment, DEFAULTS);
    }
  }
}
