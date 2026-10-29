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
import org.springframework.core.env.Environment;

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
   * Returns Spring Boot's switch that makes its executors carry the logging and tracing context, so
   * that {@code @Async} methods and work on Spring's executors keep the trace and workflow fields,
   * set from {@code stratum.context-propagation.async-threads}, which defaults to {@code true}.
   *
   * @param environment the application's environment
   * @return the settings and their values
   */
  public static Map<String, Object> asyncThreadContextPropagation(Environment environment) {
    return Map.of(
        StratumModuleConstants.PROPAGATE_TASK_CONTEXT,
        environment.getProperty(
            StratumModuleConstants.PROPAGATE_CONTEXT_TO_ASYNC_THREADS, Boolean.class, true));
  }

  /**
   * Returns Spring Boot's setting that makes Reactor carry the logging and tracing context into
   * every operator, so that log lines in WebFlux handlers and in operators such as {@code map} keep
   * the trace and workflow fields and WebClient sends the correlation ID on, set from {@code
   * stratum.context-propagation.reactor}, which defaults to {@code true}.
   *
   * @param environment the application's environment
   * @return the setting and its value
   */
  public static Map<String, Object> reactorOperatorContextPropagation(Environment environment) {
    var everyReactorOperator =
        environment.getProperty(
            StratumModuleConstants.PROPAGATE_CONTEXT_TO_EVERY_REACTOR_OPERATOR,
            Boolean.class,
            true);
    return Map.of(
        StratumModuleConstants.REACTOR_CONTEXT_PROPAGATION,
        everyReactorOperator
            ? StratumModuleConstants.REACTOR_CONTEXT_IN_EVERY_OPERATOR
            : StratumModuleConstants.REACTOR_CONTEXT_IN_HANDLE_AND_TAP_ONLY);
  }

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
