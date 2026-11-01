package com.exepex.stratum.core.condition;

import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

/**
 * Records, before the application starts, the names of the property sources that the modules' own
 * startup steps can read. {@link OnStratumModuleCondition} uses the record to stop startup when a
 * module is switched on only by a source added later, such as a {@code @PropertySource} file: that
 * module's beans would run, but its startup defaults and checks would have been skipped.
 */
public class EarlyPropertySources implements EnvironmentPostProcessor, Ordered {

  /**
   * The name of the property source that holds the record, and of its one property. It is not a
   * {@code stratum.*} key, so no module's settings read it.
   */
  static final String RECORD = EarlyPropertySources.class.getName();

  /**
   * Runs right after the configuration files are read, at the same point as the modules' own
   * startup steps.
   *
   * @return the order
   */
  @Override
  public int getOrder() {
    return ConfigDataEnvironmentPostProcessor.ORDER + 1;
  }

  /**
   * Adds the record of the property sources known now.
   *
   * @param environment the application's environment
   * @param application the application being started
   */
  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    var sources = environment.getPropertySources();
    var names =
        sources.stream().map(PropertySource::getName).collect(Collectors.toUnmodifiableSet());
    sources.addLast(new MapPropertySource(RECORD, Map.of(RECORD, names)));
  }
}
