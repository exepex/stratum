package com.exepex.stratum.core.condition;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName.Form;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Records, before the application starts, which modules are switched on at the point where the
 * modules' own startup steps run. {@link OnStratumModuleCondition} uses the record to stop startup
 * when a module is on only later, as with a switch in a {@code @PropertySource} file: that module's
 * beans would run, but its startup defaults and checks would have been skipped.
 */
public class EarlyModuleSwitches implements EnvironmentPostProcessor, Ordered {

  /**
   * The name of the property source that holds the record, and of its one property: the modules
   * switched on, each in Spring Boot's uniform form, such as {@code httpclient}, so that {@code
   * stratum.http-client.enabled} and {@code STRATUM_HTTPCLIENT_ENABLED} name the same module. It is
   * not a {@code stratum.*} key, so no module's settings read it.
   */
  static final String RECORD = EarlyModuleSwitches.class.getName();

  /** The number of parts in {@code stratum.<module>.enabled}. */
  private static final int SWITCH_PARTS = 3;

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
   * Adds the record of the modules switched on now.
   *
   * @param environment the application's environment
   * @param application the application being started
   */
  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    var binder = Binder.get(environment);
    var modulesOn =
        StreamSupport.stream(ConfigurationPropertySources.get(environment).spliterator(), false)
            .filter(IterableConfigurationPropertySource.class::isInstance)
            .flatMap(source -> ((IterableConfigurationPropertySource) source).stream())
            .filter(this::isModuleSwitch)
            .filter(
                name ->
                    Boolean.TRUE.equals(
                        binder.bind(name, Bindable.of(Boolean.class)).orElse(false)))
            .map(name -> name.getElement(1, Form.UNIFORM))
            .collect(Collectors.toUnmodifiableSet());
    environment
        .getPropertySources()
        .addLast(new MapPropertySource(RECORD, Map.of(RECORD, modulesOn)));
  }

  /** Returns whether a property is a module switch, {@code stratum.<module>.enabled}. */
  private boolean isModuleSwitch(ConfigurationPropertyName name) {
    return name.getNumberOfElements() == SWITCH_PARTS
        && StratumModuleConstants.PREFIX.equals(name.getElement(0, Form.UNIFORM))
        && StratumModuleConstants.ENABLED.equals(name.getElement(2, Form.UNIFORM));
  }
}
