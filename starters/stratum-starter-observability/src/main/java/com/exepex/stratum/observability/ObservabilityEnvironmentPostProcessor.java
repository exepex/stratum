package com.exepex.stratum.observability;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.core.condition.StratumModuleSwitch;
import com.exepex.stratum.core.properties.StratumDefaults;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.StringUtils;

/**
 * Gives Spring Boot's actuator, metrics and tracing Stratum's defaults before the application
 * starts. With {@code stratum.observability.enabled=true} it applies the module's settings, which
 * by default expose only {@code health} and {@code info}, store timings as histograms, trace Kafka
 * and RabbitMQ messages and carry traces into Spring Boot's executors, and it tags every metric;
 * otherwise it turns export off, so a bundle that brings this starter sends nothing. Every value
 * the service sets for those Spring Boot properties wins, Java default properties included. {@link
 * ObservabilityExportAddressCheck} checks the export addresses.
 */
@StratumInternal
public class ObservabilityEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

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
   * @throws com.exepex.stratum.core.error.StratumException {@code SX-CORE-1003} if a setting breaks
   *     its rules
   */
  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (!StratumModuleSwitch.isEnabled(environment, ObservabilityConstants.MODULE)) {
      StratumDefaults.addBelowServiceValues(environment, EXPORT_OFF);
      return;
    }
    StratumDefaults.addBelowServiceValues(environment, defaultsWithModuleOn(environment));
  }

  /** Returns the exposure, histogram, executor, messaging, tag and resource defaults. */
  private Map<String, Object> defaultsWithModuleOn(ConfigurableEnvironment environment) {
    var serviceEnvironment = environment.getProperty(StratumModuleConstants.SERVICE_ENVIRONMENT);
    var defaults = new HashMap<String, Object>();
    defaults.put(ObservabilityConstants.WEB_EXPOSURE, ObservabilityConstants.EXPOSED_OVER_HTTP);
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
    defaults.put(ObservabilityConstants.HISTOGRAM_FOR_EVERY_TIMING, true);
    defaults.put(
        ObservabilityConstants.HISTOGRAM_FLAVOR, ObservabilityConstants.EXPONENTIAL_HISTOGRAM);
    ObservabilityConstants.MESSAGING_OBSERVATION_SWITCHES.forEach(
        observationSwitch -> defaults.put(observationSwitch, true));
    defaults.putAll(StratumDefaults.CONTEXT_PROPAGATION);
    return defaults;
  }

  /** Adds a default only when its value is set and not blank. */
  private void putIfSet(Map<String, Object> defaults, String key, @Nullable String value) {
    if (StringUtils.hasText(value)) {
      defaults.put(key, value);
    }
  }
}
