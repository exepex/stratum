package com.exepex.stratum.logging.error;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import com.exepex.stratum.core.condition.StratumModuleSwitch;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.logging.LoggingConstants;
import com.exepex.stratum.logging.LoggingProperties;
import java.util.Objects;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.json.JsonWriter.Members;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/**
 * Writes the code of a logged {@link StratumException}, such as {@code SX-CORE-1003} or a service's
 * {@code PAY-0001}, in its own field, so errors can be searched and counted by code. The exception
 * is the logged one or the first of its causes that has a code. Spring Boot's logging loads it from
 * {@code META-INF/spring.factories}. It does nothing unless {@code stratum.logging.enabled=true},
 * and needs Logback, Spring Boot's default.
 */
public final class ErrorCodeJsonMembersCustomizer
    implements StructuredLoggingJsonMembersCustomizer<Object> {

  /** Logback's log event, the only one this customizer reads. */
  private static final String LOGBACK_EVENT = "ch.qos.logback.classic.spi.ILoggingEvent";

  /**
   * The field the code goes in, which depends on the format; {@code null} when the module is off.
   */
  private final @Nullable String errorCodeField;

  /**
   * Makes the customizer; Spring Boot's logging calls this.
   *
   * @param environment the application's environment
   */
  public ErrorCodeJsonMembersCustomizer(Environment environment) {
    this.errorCodeField =
        StratumModuleSwitch.isEnabled(environment, LoggingConstants.MODULE)
                && ClassUtils.isPresent(LOGBACK_EVENT, null)
            ? LoggingProperties.bind(environment).format().getErrorCodeField()
            : null;
  }

  /**
   * Adds the error code field to the JSON writer, if the logging module is on.
   *
   * @param members the members of the log line's JSON object
   */
  @Override
  public void customize(Members<Object> members) {
    var enabledErrorCodeField = errorCodeField;
    if (enabledErrorCodeField != null) {
      members.add(enabledErrorCodeField, this::errorCode).whenNotNull();
    }
  }

  /**
   * Returns the code of the logged exception or of its first cause with one, or {@code null}.
   * Logback's proxies end a chain of causes that loops back on itself.
   */
  private @Nullable String errorCode(Object event) {
    return event instanceof ILoggingEvent loggingEvent
        ? Stream.iterate(
                loggingEvent.getThrowableProxy(), Objects::nonNull, IThrowableProxy::getCause)
            .filter(ThrowableProxy.class::isInstance)
            .map(ThrowableProxy.class::cast)
            .map(ThrowableProxy::getThrowable)
            .filter(StratumException.class::isInstance)
            .map(StratumException.class::cast)
            .map(stratumException -> stratumException.getReport().getExternal().code())
            .findFirst()
            .orElse(null)
        : null;
  }
}
