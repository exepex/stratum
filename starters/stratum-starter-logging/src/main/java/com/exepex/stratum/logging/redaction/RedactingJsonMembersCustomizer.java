package com.exepex.stratum.logging.redaction;

import com.exepex.stratum.logging.LoggingSettings;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.json.JsonWriter.Members;
import org.springframework.boot.json.JsonWriter.ValueProcessor;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;
import org.springframework.core.env.Environment;

/**
 * Masks every value of every structured (JSON) log line before it is written: the message, MDC
 * entries, key-value pairs, and the error message and stack trace. Spring Boot's logging loads it
 * from {@code META-INF/spring.factories} before the application starts. It does nothing unless
 * {@code stratum.logging.enabled=true}.
 */
public final class RedactingJsonMembersCustomizer
    implements StructuredLoggingJsonMembersCustomizer<Object> {

  /** Masks values; {@code null} when the logging module is off. */
  private final @Nullable Redactor redactor;

  /**
   * Makes the customizer; Spring Boot's logging calls this.
   *
   * @param environment the application's environment
   */
  public RedactingJsonMembersCustomizer(Environment environment) {
    this.redactor =
        LoggingSettings.isEnabled(environment)
            ? Redactor.from(LoggingSettings.bind(environment))
            : null;
  }

  /**
   * Adds the masking to the JSON writer, if the logging module is on.
   *
   * @param members the members of the log line's JSON object
   */
  @Override
  public void customize(Members<Object> members) {
    var active = redactor;
    if (active != null) {
      members.applyingValueProcessor(
          (ValueProcessor<Object>) (path, value) -> active.redact(path.name(), value));
    }
  }
}
