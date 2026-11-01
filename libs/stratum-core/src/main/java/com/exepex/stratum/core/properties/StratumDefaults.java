package com.exepex.stratum.core.properties;

import com.exepex.stratum.core.condition.StratumModuleConstants;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertyState;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Adds a module's defaults for Spring Boot properties so that every value the service sets wins,
 * from files, environment variables, the command line, {@code @PropertySource} or Java.
 */
@UtilityClass
public class StratumDefaults {

  /**
   * Adds the defaults to Spring Boot's {@code defaultProperties}, the property source that comes
   * last, keeping any value the service put there with {@code SpringApplication
   * .setDefaultProperties}, in any form Spring Boot binds, such as a list as {@code key[0]}.
   *
   * @param environment the application's environment
   * @param defaults Spring Boot property keys and the values Stratum gives them
   */
  public static void addBelowServiceValues(
      ConfigurableEnvironment environment, Map<String, Object> defaults) {
    var propertySources = environment.getPropertySources();
    var serviceDefaultsSource = propertySources.get(DefaultPropertiesPropertySource.NAME);
    var serviceDefaults =
        serviceDefaultsSource == null
            ? null
            : ConfigurationPropertySource.from(serviceDefaultsSource);
    var unsetDefaults =
        defaults.entrySet().stream()
            .filter(defaultEntry -> !isSet(serviceDefaults, defaultEntry.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    DefaultPropertiesPropertySource.addOrMerge(unsetDefaults, propertySources);
  }

  /**
   * Spring Boot's settings that the logging and observability modules give their default: its
   * executors carry the logging and tracing context, so that {@code @Async} methods keep the trace
   * and workflow fields, and Reactor carries it into every operator, so that log lines in WebFlux
   * handlers and in operators such as {@code map} keep them too. A service changes either by
   * setting the Spring Boot key.
   */
  public static final Map<String, Object> CONTEXT_PROPAGATION =
      Map.of(
          StratumModuleConstants.PROPAGATE_TASK_CONTEXT,
          true,
          StratumModuleConstants.REACTOR_CONTEXT_PROPAGATION,
          StratumModuleConstants.REACTOR_CONTEXT_IN_EVERY_OPERATOR);

  /** Returns whether the service's default properties hold the key or any element of it. */
  private static boolean isSet(@Nullable ConfigurationPropertySource serviceDefaults, String key) {
    if (serviceDefaults == null) {
      return false;
    }
    var name = ConfigurationPropertyName.adapt(key, '.');
    return serviceDefaults.getConfigurationProperty(name) != null
        || serviceDefaults.containsDescendantOf(name) == ConfigurationPropertyState.PRESENT;
  }
}
