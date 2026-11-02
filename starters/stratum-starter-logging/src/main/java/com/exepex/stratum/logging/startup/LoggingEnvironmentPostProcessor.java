package com.exepex.stratum.logging.startup;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.core.condition.StratumModuleSwitch;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.core.properties.ServiceInstanceId;
import com.exepex.stratum.core.properties.StratumDefaults;
import com.exepex.stratum.logging.LoggingConstants;
import com.exepex.stratum.logging.LoggingErrorCode;
import com.exepex.stratum.logging.LoggingProperties;
import com.exepex.stratum.logging.LoggingProperties.LogFormat;
import com.exepex.stratum.logging.StructuredLogFormats;
import com.exepex.stratum.logging.format.Log4j2MaskedTextFormatter;
import com.exepex.stratum.logging.format.LogbackMaskedTextFormatter;
import com.exepex.stratum.logging.redaction.Redactor;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * Sets up logging before the application starts, when {@code stratum.logging.enabled=true}: checks
 * that the service has a name and valid settings, and gives Spring Boot's structured logging and
 * its executors Stratum's defaults, the instance ID included. Every value the service sets for
 * those properties wins, Java default properties included.
 */
@StratumInternal
public class LoggingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  /** Logback's log event, present when the service logs with Logback. */
  private static final String LOGBACK_EVENT = "ch.qos.logback.classic.spi.ILoggingEvent";

  /** Log4j2's log event, present when the service logs with Log4j2. */
  private static final String LOG4J2_EVENT = "org.apache.logging.log4j.core.LogEvent";

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
   *     SX-LOGGING-1002} if an extra redaction pattern is invalid, {@code SX-CORE-1003} if a
   *     setting breaks its rules
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
        environment,
        stratumLoggingDefaults(loggingProperties, environment, application.getClassLoader()));
    // After the format defaults, so that the fields follow the formats the outputs now write.
    StratumDefaults.addBelowServiceValues(environment, instanceIdFields(environment));
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
      LoggingProperties loggingProperties,
      ConfigurableEnvironment environment,
      ClassLoader classLoader) {
    var defaultProperties = new HashMap<String, Object>();
    var structuredFormat =
        Optional.ofNullable(loggingProperties.format().getStructuredFormat())
            .or(() -> maskedTextFormatter(classLoader));
    if (structuredFormat.isPresent()) {
      // The file too: Spring Boot's own plain-text file would hold every value unmasked.
      defaultProperties.put(LoggingConstants.CONSOLE_FORMAT, structuredFormat.get());
      defaultProperties.put(LoggingConstants.FILE_FORMAT, structuredFormat.get());
    }
    var serviceEnvironment = environment.getProperty(StratumModuleConstants.SERVICE_ENVIRONMENT);
    if (StringUtils.hasText(serviceEnvironment)) {
      defaultProperties.put(LoggingConstants.ECS_ENVIRONMENT, serviceEnvironment);
    }
    defaultProperties.put(
        LoggingConstants.STACK_TRACE_MAX_LENGTH, loggingProperties.maxFieldLength().toBytes());
    defaultProperties.put(LoggingConstants.STACK_TRACE_ROOT, LoggingConstants.ROOT_CAUSE_FIRST);
    defaultProperties.putAll(StratumDefaults.CONTEXT_PROPAGATION);
    return defaultProperties;
  }

  /**
   * Returns Spring Boot's settings that write the instance ID: ECS's {@code service.node.name} when
   * the console or the log file writes ECS, and the added field of the first that writes Logstash
   * or GELF. An added field reaches every JSON output, so with ECS and Logstash both, the ECS line
   * carries it too. The text format writes none.
   */
  private Map<String, Object> instanceIdFields(ConfigurableEnvironment environment) {
    var instanceId = ServiceInstanceId.of(environment).value();
    var formats = StructuredLogFormats.of(environment).stream().flatMap(Optional::stream).toList();
    var fields = new HashMap<String, Object>();
    if (formats.contains(LogFormat.ECS)) {
      fields.put(LoggingConstants.ECS_NODE_NAME, instanceId);
    }
    formats.stream()
        .map(this::addedInstanceIdField)
        .flatMap(Optional::stream)
        .findFirst()
        .ifPresent(
            field ->
                fields.put(
                    StratumModuleConstants.mapEntry(LoggingConstants.JSON_ADDED_FIELDS, field),
                    instanceId));
    return fields;
  }

  /**
   * Returns the name of the field a format adds for the instance ID; none for ECS, which has its
   * own field, or for text.
   */
  private Optional<String> addedInstanceIdField(LogFormat format) {
    return switch (format) {
      case LOGSTASH -> Optional.of(LoggingConstants.INSTANCE_ID);
      case GELF -> Optional.of(LoggingConstants.GELF_INSTANCE_ID);
      case ECS, TEXT -> Optional.empty();
    };
  }

  /**
   * Returns the class name of the masked text format for the service's logging backend, Logback
   * first as Spring Boot picks it, or empty when the service has neither.
   */
  private Optional<String> maskedTextFormatter(ClassLoader classLoader) {
    if (ClassUtils.isPresent(LOGBACK_EVENT, classLoader)) {
      return Optional.of(LogbackMaskedTextFormatter.class.getName());
    }
    return ClassUtils.isPresent(LOG4J2_EVENT, classLoader)
        ? Optional.of(Log4j2MaskedTextFormatter.class.getName())
        : Optional.empty();
  }
}
