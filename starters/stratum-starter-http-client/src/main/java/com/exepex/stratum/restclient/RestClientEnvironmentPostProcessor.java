package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.StratumModuleSwitch;
import com.exepex.stratum.core.properties.StratumDefaults;
import com.exepex.stratum.httpclient.HttpClientConstants;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Keeps the service's own {@code RestClient}s on Java's HTTP client, as in plain Spring Boot, now
 * that this starter puts Apache HttpClient 5 on the class path for Stratum's clients: it gives
 * {@code spring.http.clients.imperative.factory} the default {@code jdk}, unless {@code
 * stratum.http-client.enabled=false}. A value the service sets wins.
 */
@StratumInternal
public class RestClientEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  /** Stratum's default for Spring Boot's key. */
  private static final Map<String, Object> DEFAULTS =
      Map.of(HttpClientConstants.BOOT_IMPERATIVE_FACTORY, HttpClientConstants.JDK_FACTORY);

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
    if (StratumModuleSwitch.isEnabled(environment, HttpClientConstants.MODULE)) {
      StratumDefaults.addBelowServiceValues(environment, DEFAULTS);
    }
  }
}
