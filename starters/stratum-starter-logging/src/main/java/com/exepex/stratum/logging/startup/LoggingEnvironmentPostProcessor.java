package com.exepex.stratum.logging.startup;

import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.core.condition.StratumModuleSwitch;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.core.properties.ReactorOperatorContextPropagation;
import com.exepex.stratum.core.properties.StratumDefaults;
import com.exepex.stratum.logging.LoggingConstants;
import com.exepex.stratum.logging.LoggingErrorCode;
import com.exepex.stratum.logging.LoggingProperties;
import com.exepex.stratum.logging.LoggingProperties.LogFormat;
import com.exepex.stratum.logging.redaction.Redactor;
import jakarta.validation.ConstraintViolationException;
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
import org.springframework.util.StringUtils;

/**
 * Sets up logging before the application starts, when {@code stratum.logging.enabled=true}: checks
 * that the service has a name and valid settings, and gives Spring Boot's structured logging and
 * its executors Stratum's defaults. Every value the service sets for those properties wins, Java
 * default properties included.
 */
@RequiredArgsConstructor
public class LoggingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

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
   *     SX-LOGGING-1002} if an extra redaction pattern is invalid
   * @throws ConstraintViolationException {@code SX-CORE-1003} if a setting breaks its rules
   */
  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (!StratumModuleSwitch.isEnabled(environment, LoggingConstants.MODULE)) {
      return;
    }
    if (!StringUtils.hasText(environment.getProperty(LoggingConstants.APPLICATION_NAME))) {
      throw new StratumException(LoggingErrorCode.APPLICATION_NAME_MISSING);
    }
    var loggingProperties = LoggingProperties.resolve(environment);
    requireValidRedactionPatterns(loggingProperties);
    StratumDefaults.addBelowServiceValues(
        environment, stratumLoggingDefaults(loggingProperties, environment));
    ReactorOperatorContextPropagation.enableWhenSetToEveryOperator(environment);
    if (loggingProperties.format() == LogFormat.TEXT && !onlyLocalProfiles(environment)) {
      logFactory
          .getLog(LoggingEnvironmentPostProcessor.class)
          .warn(
              LoggingConstants.FORMAT
                  + "=text writes plain text, which is not redacted."
                  + " Use it only in the local or test profile.");
    }
  }

  /**
   * Stops startup with {@code SX-LOGGING-1002} when an extra redaction pattern is invalid. The
   * redactor is built only for this check: Spring Boot's logging builds the one it uses.
   */
  private void requireValidRedactionPatterns(LoggingProperties loggingProperties) {
    Redactor.from(loggingProperties);
  }

  /** Returns Spring Boot's logging properties that Stratum sets by default. */
  private Map<String, Object> stratumLoggingDefaults(
      LoggingProperties loggingProperties, ConfigurableEnvironment environment) {
    var defaultProperties = new HashMap<String, Object>();
    var structuredFormat = loggingProperties.format().getStructuredFormat();
    if (structuredFormat != null) {
      // The file too: a plain-text file would hold every value unredacted.
      defaultProperties.put(LoggingConstants.CONSOLE_FORMAT, structuredFormat);
      defaultProperties.put(LoggingConstants.FILE_FORMAT, structuredFormat);
    }
    var serviceEnvironment = environment.getProperty(StratumModuleConstants.SERVICE_ENVIRONMENT);
    if (StringUtils.hasText(serviceEnvironment)) {
      defaultProperties.put(LoggingConstants.ECS_ENVIRONMENT, serviceEnvironment);
    }
    defaultProperties.put(
        LoggingConstants.STACK_TRACE_MAX_LENGTH, loggingProperties.maxFieldLength().toBytes());
    defaultProperties.put(
        LoggingConstants.STACK_TRACE_ROOT,
        loggingProperties.stackTraceStartsWithRootCause()
            ? LoggingConstants.ROOT_CAUSE_FIRST
            : LoggingConstants.OUTERMOST_EXCEPTION_FIRST);
    defaultProperties.putAll(StratumDefaults.asyncThreadContextPropagation(environment));
    defaultProperties.putAll(StratumDefaults.reactorOperatorContextPropagation(environment));
    return defaultProperties;
  }

  /** Returns whether every active profile is one in which plain-text logs are expected. */
  private boolean onlyLocalProfiles(ConfigurableEnvironment environment) {
    var profiles = environment.getActiveProfiles();
    return profiles.length > 0
        && Arrays.stream(profiles).allMatch(LoggingConstants.LOCAL_PROFILES::contains);
  }
}
