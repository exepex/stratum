package com.exepex.stratum.logging.error;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.StratumModuleSwitch;
import com.exepex.stratum.core.error.FailureWithErrorCode;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.logging.LoggingConstants;
import com.exepex.stratum.logging.LoggingProperties.LogFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.json.JsonWriter.Members;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/**
 * Writes the code of a logged {@link StratumException}, or of another exception that implements
 * {@link FailureWithErrorCode}, such as {@code SX-CORE-1003} or a service's {@code PAY-0001}, in
 * its own field, so errors can be searched and counted by code: {@code error.code}, or {@code
 * _error_code} in GELF. The exception is the logged one or the first of its causes that has a code.
 * Spring Boot's logging loads it from {@code META-INF/spring.factories}. It does nothing unless
 * {@code stratum.logging.enabled=true}, and needs Logback, Spring Boot's default.
 */
@StratumInternal
public final class ErrorCodeJsonMembersCustomizer
    implements StructuredLoggingJsonMembersCustomizer<Object> {

  /** Logback's log event, the only one this customizer reads. */
  private static final String LOGBACK_EVENT = "ch.qos.logback.classic.spi.ILoggingEvent";

  /** The field the code goes in; {@code null} when the module is off or logs are plain text. */
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
            ? structuredFormat(environment).map(this::errorCodeFieldFor).orElse(null)
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
   * Returns the structured format Spring Boot writes, the console's first and then the file's,
   * including a service's own choice over Stratum's default. A customizer is not told which output
   * it customizes, so when only one of the two is GELF, the console's format decides the name.
   */
  private Optional<String> structuredFormat(Environment environment) {
    return Stream.of(LoggingConstants.CONSOLE_FORMAT, LoggingConstants.FILE_FORMAT)
        .map(environment::getProperty)
        .filter(Objects::nonNull)
        .findFirst();
  }

  /**
   * Returns the field name for a format: GELF's own fields start with {@code _}. Case is ignored,
   * as Spring Boot ignores it.
   */
  private String errorCodeFieldFor(String structuredFormat) {
    return String.CASE_INSENSITIVE_ORDER.compare(
                structuredFormat, LogFormat.GELF.getStructuredFormat())
            == 0
        ? LoggingConstants.GELF_ERROR_CODE
        : LoggingConstants.ERROR_CODE;
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
            .filter(FailureWithErrorCode.class::isInstance)
            .map(FailureWithErrorCode.class::cast)
            .map(codedFailure -> codedFailure.getErrorCode().getCode())
            .findFirst()
            .orElse(null)
        : null;
  }
}
