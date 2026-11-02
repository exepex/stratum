package com.exepex.stratum.logging.redaction;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.StratumModuleSwitch;
import com.exepex.stratum.logging.LoggingConstants;
import com.exepex.stratum.logging.LoggingProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.json.JsonWriter.MemberPath;
import org.springframework.boot.json.JsonWriter.Members;
import org.springframework.boot.json.JsonWriter.ValueProcessor;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;
import org.springframework.core.env.Environment;

/**
 * Masks every value of every structured (JSON) log line before it is written: the message, MDC
 * entries, key-value pairs, and the error message and stack trace. Fields that Stratum or Spring
 * Boot fill with no personal data are written as they are, and the frame lines of a stack trace are
 * not searched ({@link KnownLogFields}). Spring Boot's logging loads it from {@code
 * META-INF/spring.factories} before the application starts. It does nothing unless {@code
 * stratum.logging.enabled=true}.
 */
@StratumInternal
public final class RedactingJsonMembersCustomizer
    implements StructuredLoggingJsonMembersCustomizer<Object> {

  /** Masks values; {@code null} when the logging module is off. */
  private final @Nullable Redactor redactor;

  /** The fields whose kind the log format tells. */
  private final KnownLogFields knownLogFields;

  /**
   * Makes the customizer; Spring Boot's logging calls this.
   *
   * @param environment the application's environment
   */
  public RedactingJsonMembersCustomizer(Environment environment) {
    this.redactor =
        StratumModuleSwitch.isEnabled(environment, LoggingConstants.MODULE)
            ? Redactor.from(LoggingProperties.resolve(environment))
            : null;
    this.knownLogFields = KnownLogFields.from(environment);
  }

  /**
   * Adds the masking to the JSON writer, if the logging module is on.
   *
   * @param members the members of the log line's JSON object
   */
  @Override
  public void customize(Members<Object> members) {
    var enabledRedactor = redactor;
    if (enabledRedactor != null) {
      members.applyingValueProcessor(
          (ValueProcessor<Object>) (path, value) -> redactAt(enabledRedactor, path, value));
    }
  }

  /**
   * Masks a value by the kind of its field: a safe field as it is, a stack trace without its frame
   * lines, any other field by its name and then its content. The name is the field's full path, so
   * a nested {@code password.new} is still matched by its words.
   */
  private @Nullable Object redactAt(
      Redactor enabledRedactor, MemberPath path, @Nullable Object value) {
    var fullPath = path.toUnescapedString();
    if (knownLogFields.isSafe(fullPath)) {
      return value;
    }
    return knownLogFields.isStackTrace(fullPath)
        ? enabledRedactor.redactStackTrace(fullPath, value)
        : enabledRedactor.redact(fullPath, value);
  }
}
