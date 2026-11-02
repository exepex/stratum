package com.exepex.stratum.logging.format;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.logging.LoggingProperties;
import com.exepex.stratum.logging.redaction.Redactor;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.message.Message;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.logging.StackTracePrinter;
import org.springframework.core.env.Environment;

/**
 * The masked {@code text} format for Log4j2. Spring Boot creates it from its class name in {@code
 * logging.structured.format.console} and {@code .file}.
 */
@StratumInternal
public final class Log4j2MaskedTextFormatter extends MaskedTextFormatter<LogEvent> {

  /**
   * Makes the formatter from the module's settings; Spring Boot's logging calls this.
   *
   * @param environment the application's environment
   * @param stackTracePrinter Spring Boot's stack trace printer; {@code null} when none is set
   * @throws com.exepex.stratum.core.error.StratumException if the settings are invalid
   */
  public Log4j2MaskedTextFormatter(
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
  Instant time(LogEvent event) {
    var instant = event.getInstant();
    return Instant.ofEpochMilli(instant.getEpochMillisecond())
        .plusNanos(instant.getNanoOfMillisecond());
  }

  /**
   * Returns the event's level, such as {@code INFO}.
   *
   * @param event the log event
   * @return the level
   */
  @Override
  String level(LogEvent event) {
    return event.getLevel().name();
  }

  /**
   * Returns the name of the thread that logged the event.
   *
   * @param event the log event
   * @return the thread name
   */
  @Override
  @Nullable String thread(LogEvent event) {
    return event.getThreadName();
  }

  /**
   * Returns the name of the logger.
   *
   * @param event the log event
   * @return the logger name
   */
  @Override
  @Nullable String logger(LogEvent event) {
    return event.getLoggerName();
  }

  /**
   * Returns the message with its arguments filled in.
   *
   * @param event the log event
   * @return the message
   */
  @Override
  @Nullable String message(LogEvent event) {
    return Optional.ofNullable(event.getMessage()).map(Message::getFormattedMessage).orElse(null);
  }

  /**
   * Returns the event's context data, which holds the MDC entries.
   *
   * @param event the log event
   * @return the fields by name
   */
  @Override
  Map<String, @Nullable Object> fields(LogEvent event) {
    return new LinkedHashMap<>(event.getContextData().toMap());
  }

  /**
   * Returns the logged exception's stack trace, or {@code null} when there is none.
   *
   * @param event the log event
   * @return the stack trace
   */
  @Override
  @Nullable String stackTrace(LogEvent event) {
    return Optional.ofNullable(event.getThrown()).map(this::printStackTrace).orElse(null);
  }
}
