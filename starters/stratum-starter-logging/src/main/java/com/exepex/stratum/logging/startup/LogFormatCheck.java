package com.exepex.stratum.logging.startup;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.logging.LoggingConstants;
import com.exepex.stratum.logging.LoggingErrorCode;
import com.exepex.stratum.logging.StructuredLogFormats;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * Stops startup when a log output would not use one of Stratum's formats, because Stratum masks
 * only the lines its own formats write.
 */
@StratumInternal
@UtilityClass
public class LogFormatCheck {

  /** The start of a {@code logging.config} value that Spring Boot ignores. */
  private static final String IGNORED_CONFIG_PREFIX = "-D";

  /**
   * Stops startup with {@code SX-LOGGING-1003} when the service has no Logback or Log4j2, sets
   * {@code logging.config}, has a Logback configuration file on the class path, or gives the
   * console or the log file a format other than {@code ecs}, {@code logstash}, {@code gelf} or
   * Stratum's text format.
   *
   * @param environment the application's environment, with Stratum's defaults added
   * @param classLoader the application's class loader
   * @param maskedTextFormatter the class name of Stratum's text format for the service's logging
   *     backend, or {@code null} when it has neither Logback nor Log4j2
   * @throws StratumException {@code SX-LOGGING-1003}, naming the key or file at fault
   */
  public static void requireStratumFormats(
      Environment environment, ClassLoader classLoader, @Nullable String maskedTextFormatter) {
    requireBackend(maskedTextFormatter);
    requireNoLoggingConfig(environment);
    requireNoLogbackFile(classLoader);
    requireStratumFormat(environment, LoggingConstants.CONSOLE_FORMAT, maskedTextFormatter);
    requireStratumFormat(environment, LoggingConstants.FILE_FORMAT, maskedTextFormatter);
  }

  /** Stops startup when the service has neither Logback nor Log4j2. */
  private static void requireBackend(@Nullable String maskedTextFormatter) {
    if (maskedTextFormatter == null) {
      throw notStratum("no Logback or Log4j2 on the classpath");
    }
  }

  /** Stops startup when {@code logging.config} is set, as Spring Boot reads it. */
  private static void requireNoLoggingConfig(Environment environment) {
    var loggingConfig = environment.getProperty(LoggingConstants.LOGGING_CONFIG);
    if (StringUtils.hasLength(loggingConfig) && !loggingConfig.startsWith(IGNORED_CONFIG_PREFIX)) {
      throw notStratum(LoggingConstants.LOGGING_CONFIG + " is set");
    }
  }

  /** Stops startup when Spring Boot would load a Logback configuration file of the service's. */
  private static void requireNoLogbackFile(ClassLoader classLoader) {
    if (!ClassUtils.isPresent(LoggingConstants.LOGBACK_EVENT, classLoader)) {
      return;
    }
    var ownFile =
        LoggingConstants.LOGBACK_CONFIGURATION_FILES.stream()
            .filter(name -> new ClassPathResource(name, classLoader).exists())
            .findFirst();
    if (ownFile.isPresent()) {
      throw notStratum(ownFile.get() + " is on the classpath");
    }
  }

  /** Stops startup when the key is unset or holds a format other than Stratum's. */
  private static void requireStratumFormat(
      Environment environment, String key, @Nullable String maskedTextFormatter) {
    var format = environment.getProperty(key);
    if (format == null
        || !(StructuredLogFormats.isSpringBootFormat(format)
            || format.equals(maskedTextFormatter))) {
      throw notStratum(key + " is not ecs, logstash, gelf or Stratum's text format");
    }
  }

  /** Returns the startup error with the detail that names the key or file at fault. */
  private static StratumException notStratum(String detail) {
    return new StratumException(LoggingErrorCode.LOG_FORMAT_NOT_STRATUM, detail);
  }
}
