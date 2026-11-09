package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.StratumModuleSwitch;
import com.exepex.stratum.core.properties.StratumDefaults;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientErrorCode;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.http.HttpStatus;

/**
 * Gives two defaults, unless {@code stratum.http-client.enabled=false}; a value the service sets
 * wins. It keeps the service's own {@code RestClient}s on Java's HTTP client, as in plain Spring
 * Boot, now that this starter puts Apache HttpClient 5 on the class path for Stratum's clients:
 * {@code spring.http.clients.imperative.factory} is {@code jdk}. And it gives each code a call to
 * another service can end with its HTTP status in {@code stratum.web.error.statuses}: 504 for a
 * timeout, 503 when the service cannot be reached or its circuit breaker is open, 500 when a client
 * that relays the caller's token has none.
 */
@StratumInternal
public class RestClientEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  /** Stratum's defaults for Spring Boot's key and for the statuses of the codes of a call. */
  private static final Map<String, Object> DEFAULTS =
      Map.of(
          HttpClientConstants.BOOT_IMPERATIVE_FACTORY,
          HttpClientConstants.JDK_FACTORY,
          HttpClientConstants.errorStatus(HttpClientErrorCode.DOWNSTREAM_TIMEOUT.getCode()),
          HttpStatus.GATEWAY_TIMEOUT.value(),
          HttpClientConstants.errorStatus(HttpClientErrorCode.DOWNSTREAM_UNAVAILABLE.getCode()),
          HttpStatus.SERVICE_UNAVAILABLE.value(),
          HttpClientConstants.errorStatus(HttpClientErrorCode.BREAKER_OPEN.getCode()),
          HttpStatus.SERVICE_UNAVAILABLE.value(),
          HttpClientConstants.errorStatus(HttpClientErrorCode.CALLER_TOKEN_MISSING.getCode()),
          HttpStatus.INTERNAL_SERVER_ERROR.value());

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
