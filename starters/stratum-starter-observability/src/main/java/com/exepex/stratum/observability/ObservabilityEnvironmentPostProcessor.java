package com.exepex.stratum.observability;

import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.core.condition.StratumModuleSwitch;
import com.exepex.stratum.core.error.StratumException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
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
 * info}, tags every metric and checks the export addresses; otherwise it turns export off, so a
 * bundle that brings this starter sends nothing. A service's own values win, because these come
 * last.
 */
public class ObservabilityEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  /** The name of the property source that holds Stratum's observability defaults. */
  static final String DEFAULTS_SOURCE = "stratumObservabilityDefaults";

  /** Turns off sending metrics and spans, for a module that is off. */
  private static final Map<String, Object> EXPORT_OFF =
      Map.of(
          ObservabilityConstants.METRICS_EXPORT_ENABLED, false,
          ObservabilityConstants.TRACING_EXPORT_ENABLED, false);

  /** Each export address, with the switches that must all be on for it to be used. */
  private static final Map<String, List<String>> EXPORT_SWITCHES_BY_ADDRESS =
      Map.of(
          ObservabilityConstants.METRICS_EXPORT_URL,
          List.of(ObservabilityConstants.METRICS_EXPORT_ENABLED),
          ObservabilityConstants.TRACING_EXPORT_ENDPOINT,
          List.of(
              ObservabilityConstants.TRACING_EXPORT_ENABLED,
              ObservabilityConstants.TRACING_OTLP_EXPORT_ENABLED));

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
   * @throws StratumException {@code SX-OBSERVABILITY-1001} if the module is on outside the local
   *     and test profiles and an export address is not set
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

  /** Checks the export addresses and returns the exposure, tag and resource defaults. */
  private Map<String, Object> defaultsWithModuleOn(ConfigurableEnvironment environment) {
    requireExportAddresses(environment);
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

  /**
   * Stops startup with {@code SX-OBSERVABILITY-1001} when an export that is on has no address,
   * except in the local and test profiles, where no Collector is expected.
   */
  private void requireExportAddresses(ConfigurableEnvironment environment) {
    if (onlyLocalProfiles(environment)) {
      return;
    }
    var missingAddresses =
        EXPORT_SWITCHES_BY_ADDRESS.entrySet().stream()
            .filter(exportSwitches -> isOn(environment, exportSwitches.getValue()))
            .map(Map.Entry::getKey)
            .filter(address -> !StringUtils.hasText(environment.getProperty(address)))
            .sorted()
            .toList();
    if (!missingAddresses.isEmpty()) {
      throw new StratumException(
          ObservabilityErrorCode.EXPORT_ENDPOINT_MISSING,
          "set " + String.join(" and ", missingAddresses) + ", or turn that export off");
    }
  }

  /** Returns whether every switch is on; Spring Boot turns each export on by default. */
  private boolean isOn(ConfigurableEnvironment environment, List<String> switches) {
    return switches.stream()
        .allMatch(exportSwitch -> environment.getProperty(exportSwitch, Boolean.class, true));
  }

  /** Returns whether every active profile is one in which no Collector is expected. */
  private boolean onlyLocalProfiles(ConfigurableEnvironment environment) {
    var profiles = environment.getActiveProfiles();
    return profiles.length > 0
        && Arrays.stream(profiles).allMatch(ObservabilityConstants.LOCAL_PROFILES::contains);
  }

  /** Adds a default only when its value is set and not blank. */
  private void putIfSet(Map<String, Object> defaults, String key, @Nullable String value) {
    if (StringUtils.hasText(value)) {
      defaults.put(key, value);
    }
  }
}
