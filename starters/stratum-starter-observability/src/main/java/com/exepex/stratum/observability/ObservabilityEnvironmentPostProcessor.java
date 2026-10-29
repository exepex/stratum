package com.exepex.stratum.observability;

import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.core.condition.StratumModuleSwitch;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * Gives Spring Boot's actuator, metrics and tracing Stratum's defaults before the application
 * starts. With {@code stratum.observability.enabled=true} it exposes only {@code health} and {@code
 * info} and tags every metric; otherwise it turns export off, so a bundle that brings this starter
 * sends nothing. A service's own values win, because these come last. {@link
 * ObservabilityExportAddressCheck} checks the export addresses.
 */
public class ObservabilityEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  /** The name of the property source that holds Stratum's observability defaults. */
  static final String DEFAULTS_SOURCE = "stratumObservabilityDefaults";

  /** Turns off sending metrics and spans, for a module that is off. */
  private static final Map<String, Object> EXPORT_OFF =
      Map.of(
          ObservabilityConstants.METRICS_EXPORT_ENABLED, false,
          ObservabilityConstants.TRACING_EXPORT_ENABLED, false);

  /**
   * Runs right after the configuration files are read, so that the profiles and every property are
   * known.
   *
   * @return the order
   */
  @Override
  public int getOrder() {
    return ConfigDataEnvironmentPostProcessor.ORDER + 1;
  }

  /**
   * Adds Stratum's defaults: export off when the module is off, or the module's defaults when it is
   * on.
   *
   * @param environment the application's environment
   * @param application the application being started
   */
  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    var defaults =
        StratumModuleSwitch.isEnabled(environment, ObservabilityConstants.MODULE)
            ? defaultsWithModuleOn(environment)
            : EXPORT_OFF;
    environment.getPropertySources().addLast(new MapPropertySource(DEFAULTS_SOURCE, defaults));
  }

  /** Returns the exposure, tag and resource defaults. */
  private Map<String, Object> defaultsWithModuleOn(ConfigurableEnvironment environment) {
    var serviceEnvironment = environment.getProperty(StratumModuleConstants.SERVICE_ENVIRONMENT);
    var defaults = new HashMap<String, Object>();
    defaults.put(ObservabilityConstants.WEB_EXPOSURE, ObservabilityConstants.SAFE_ENDPOINTS);
    putIfSet(
        defaults,
        ObservabilityConstants.METRIC_TAGS + ObservabilityConstants.APPLICATION_TAG,
        environment.getProperty(ObservabilityConstants.APPLICATION_NAME));
    putIfSet(
        defaults,
        ObservabilityConstants.METRIC_TAGS + ObservabilityConstants.ENVIRONMENT_TAG,
        serviceEnvironment);
    putIfSet(defaults, ObservabilityConstants.ENVIRONMENT_RESOURCE_ATTRIBUTE, serviceEnvironment);
    putIfSet(
        defaults,
        ObservabilityConstants.METRIC_TAGS + ObservabilityConstants.HOST_TAG,
        environment.getProperty(ObservabilityConstants.HOSTNAME));
    return defaults;
  }

  /** Adds a default only when its value is set and not blank. */
  private void putIfSet(Map<String, Object> defaults, String key, @Nullable String value) {
    if (StringUtils.hasText(value)) {
      defaults.put(key, value);
    }
  }
}
