package com.exepex.stratum.httpclient;

import com.exepex.stratum.core.condition.StratumModuleSwitch;
import com.exepex.stratum.core.properties.StratumDefaults;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Gives every HTTP client a connect timeout of 2s and a read timeout of 5s when {@code
 * stratum.http-client.enabled=true}, so that no call waits forever; Spring Boot sets none. They are
 * Spring Boot's own {@code spring.http.clients.*} keys, so every value the service sets wins, and a
 * client's {@code spring.http.serviceclient.<name>.*} keys win over both.
 */
public class HttpClientEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  /** Spring Boot's timeout keys and Stratum's defaults for them. */
  private static final Map<String, Object> TIMEOUT_DEFAULTS =
      Map.of(
          HttpClientConstants.CONNECT_TIMEOUT, HttpClientConstants.DEFAULT_CONNECT_TIMEOUT,
          HttpClientConstants.READ_TIMEOUT, HttpClientConstants.DEFAULT_READ_TIMEOUT);

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
   * Adds the timeout defaults when the module is on.
   *
   * @param environment the application's environment
   * @param application the application being started
   */
  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (StratumModuleSwitch.isEnabled(environment, HttpClientConstants.MODULE)) {
      StratumDefaults.addBelowServiceValues(environment, TIMEOUT_DEFAULTS);
    }
  }
}
