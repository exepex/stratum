package com.exepex.stratum.logging;

import lombok.experimental.UtilityClass;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Reads the logging module's switch and settings straight from the environment, because logging
 * starts before any bean exists.
 */
@UtilityClass
public class LoggingSettings {

  /**
   * Returns whether {@code stratum.logging.enabled} is {@code true}. Unset means off.
   *
   * @param environment the application's environment
   * @return whether the module is on
   */
  public boolean isEnabled(Environment environment) {
    return Boolean.TRUE.equals(
        Binder.get(environment).bind(LoggingConstants.ENABLED, Boolean.class).orElse(false));
  }

  /**
   * Binds {@code stratum.logging.*}, with the defaults for anything unset.
   *
   * @param environment the application's environment
   * @return the settings
   */
  public LoggingProperties bind(Environment environment) {
    return Binder.get(environment).bindOrCreate(LoggingConstants.PREFIX, LoggingProperties.class);
  }
}
