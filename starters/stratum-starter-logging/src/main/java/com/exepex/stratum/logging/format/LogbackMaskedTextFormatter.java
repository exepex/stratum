package com.exepex.stratum.logging.format;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.logging.LoggingProperties;
import com.exepex.stratum.logging.redaction.Redactor;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.logging.StackTracePrinter;
import org.springframework.core.env.Environment;

/**
 * The masked {@code text} format for Logback, Spring Boot's default logging backend. Spring Boot
 * creates it from its class name in {@code logging.structured.format.console} and {@code .file}.
 */
@StratumInternal
public final class LogbackMaskedTextFormatter extends MaskedTextFormatter<ILoggingEvent> {

  /**
   * Makes the formatter from the module's settings; Spring Boot's logging calls this.
   *
   * @param environment the application's environment
   * @param stackTracePrinter Spring Boot's stack trace printer; {@code null} when none is set
   * @throws com.exepex.stratum.core.error.StratumException if the settings are invalid
   */
  public LogbackMaskedTextFormatter(
      Environment environment, @Nullable StackTracePrinter stackTracePrinter) {
    super(Redactor.from(LoggingProperties.resolve(environment)), stackTracePrinter);
  }

  /**
   * Returns when the event happened.
   *
   * @param event the log event
   * @return the time
   */
  @Override
  Instant time(ILoggingEvent event) {
    return event.getInstant();
  }

  /**
   * Returns the event's level, such as {@code INFO}.
   *
   * @param event the log event
   * @return the level
   */
  @Override
  String level(ILoggingEvent event) {
    return event.getLevel().toString();
  }

  /**
   * Returns the name of the thread that logged the event.
   *
   * @param event the log event
   * @return the thread name
   */
  @Override
  @Nullable String thread(ILoggingEvent event) {
    return event.getThreadName();
  }

  /**
   * Returns the name of the logger.
   *
   * @param event the log event
   * @return the logger name
   */
  @Override
  @Nullable String logger(ILoggingEvent event) {
    return event.getLoggerName();
  }

  /**
   * Returns the message with its arguments filled in.
   *
   * @param event the log event
   * @return the message
   */
  @Override
  @Nullable String message(ILoggingEvent event) {
    return event.getFormattedMessage();
  }

  /**
   * Returns the MDC entries, then the key-value pairs, in that order.
   *
   * @param event the log event
   * @return the fields by name
   */
  @Override
  Map<String, @Nullable Object> fields(ILoggingEvent event) {
    var fields = new LinkedHashMap<String, @Nullable Object>(event.getMDCPropertyMap());
    Optional.ofNullable(event.getKeyValuePairs())
        .ifPresent(pairs -> pairs.forEach(pair -> putPair(fields, pair)));
    return fields;
  }

  /**
   * Returns the logged exception's stack trace, or {@code null} when there is none.
   *
   * @param event the log event
   * @return the stack trace
   */
  @Override
  @Nullable String stackTrace(ILoggingEvent event) {
    return switch (event.getThrowableProxy()) {
      case null -> null;
      case ThrowableProxy proxy -> printStackTrace(proxy.getThrowable());
      // An event read back from another process has no exception left, only its text.
      case IThrowableProxy proxy -> ThrowableProxyUtil.asString(proxy);
    };
  }

  /** Adds a key-value pair under its key's text. */
  private void putPair(Map<String, @Nullable Object> fields, KeyValuePair pair) {
    fields.put(String.valueOf(pair.key), pair.value);
  }
}
