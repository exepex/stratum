package com.exepex.stratum.core.properties;

import java.util.Map;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;
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
   * .setDefaultProperties}.
   *
   * @param environment the application's environment
   * @param defaults Spring Boot property keys and the values Stratum gives them
   */
  public static void addBelowServiceValues(
      ConfigurableEnvironment environment, Map<String, Object> defaults) {
    var propertySources = environment.getPropertySources();
    var serviceDefaults = propertySources.get(DefaultPropertiesPropertySource.NAME);
    var unsetDefaults =
        defaults.entrySet().stream()
            .filter(
                defaultEntry ->
                    serviceDefaults == null
                        || !serviceDefaults.containsProperty(defaultEntry.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    DefaultPropertiesPropertySource.addOrMerge(unsetDefaults, propertySources);
  }
}
