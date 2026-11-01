package com.exepex.stratum.web.autoconfigure;

import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.web.StackTraceExposureWarning;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * The startup checks of backward-compatible mode, shared by the error configurations of both web
 * starters.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnStratumModule(WebConstants.MODULE)
@ConditionalOnWebApplication
@Import(WebSettingsConfiguration.class)
public class BackwardCompatibleModeConfiguration {

  /**
   * Warns once at startup when Spring Boot's error body, kept in backward-compatible mode, shows
   * stack traces or exception classes. It cannot be replaced or turned off, so a leak is always
   * reported; a team marks a false positive in its monitoring.
   *
   * @param webProperties the web module's settings
   * @param environment where Spring Boot's error settings are read from
   * @return the warning
   */
  @Bean
  public StackTraceExposureWarning stackTraceExposureWarning(
      WebProperties webProperties, Environment environment) {
    return new StackTraceExposureWarning(webProperties, environment);
  }
}
