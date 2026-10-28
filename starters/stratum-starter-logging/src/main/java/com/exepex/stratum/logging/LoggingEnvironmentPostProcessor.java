package com.exepex.stratum.logging;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.logging.redaction.Redactor;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * Sets up logging before the application starts, when {@code stratum.logging.enabled=true}: checks
 * that the service has a name and valid settings, and gives Spring Boot's structured logging
 * Stratum's defaults. A service's own {@code logging.*} values win, because these come last.
 */
@RequiredArgsConstructor
public class LoggingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  /** The name of the property source that holds Stratum's logging defaults. */
  static final String DEFAULTS_SOURCE = "stratumLoggingDefaults";

  /** Gives the log for the plain-text warning, which waits until logging is ready. */
  private final DeferredLogFactory logFactory;

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
   * Checks the service's name and settings and adds the logging defaults, if the module is on.
   *
   * @param environment the application's environment
   * @param application the application being started
   * @throws StratumException {@code SX-LOGGING-1001} if the service has no name, {@code
   *     SX-LOGGING-1002} or {@code SX-CORE-1003} if a setting is invalid
   */
  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (!LoggingSettings.isEnabled(environment)) {
      return;
    }
    if (!StringUtils.hasText(environment.getProperty(LoggingConstants.APPLICATION_NAME))) {
      throw new StratumException(LoggingErrorCode.APPLICATION_NAME_MISSING);
    }
    var settings = LoggingSettings.bind(environment);
    Redactor.from(settings);
    environment
        .getPropertySources()
        .addLast(new MapPropertySource(DEFAULTS_SOURCE, defaults(settings, environment)));
    if (settings.format() == LogFormat.TEXT && !onlyLocalProfiles(environment)) {
      logFactory
          .getLog(LoggingEnvironmentPostProcessor.class)
          .warn(
              "stratum.logging.format=text writes plain text, which is not redacted."
                  + " Use it only in the local or test profile.");
    }
  }

  /** Returns Spring Boot's logging properties that Stratum sets by default. */
  private static Map<String, Object> defaults(
      LoggingProperties settings, ConfigurableEnvironment environment) {
    var defaults = new HashMap<String, Object>();
    var structuredFormat = settings.format().getStructuredFormat();
    if (structuredFormat != null) {
      defaults.put(LoggingConstants.CONSOLE_FORMAT, structuredFormat);
    }
    var serviceEnvironment = environment.getProperty(LoggingConstants.ENVIRONMENT);
    if (StringUtils.hasText(serviceEnvironment)) {
      defaults.put(LoggingConstants.ECS_ENVIRONMENT, serviceEnvironment);
    }
    // Root cause first, so that a stack trace cut at the limit still shows why it failed.
    defaults.put(LoggingConstants.STACK_TRACE_MAX_LENGTH, settings.maxFieldLength().toBytes());
    defaults.put(LoggingConstants.STACK_TRACE_ROOT, "first");
    return defaults;
  }

  /** Returns whether every active profile is one in which plain-text logs are expected. */
  private static boolean onlyLocalProfiles(ConfigurableEnvironment environment) {
    var profiles = environment.getActiveProfiles();
    return profiles.length > 0
        && Arrays.stream(profiles).allMatch(LoggingConstants.LOCAL_PROFILES::contains);
  }
}
