package com.exepex.stratum.logging;

import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.env.Environment;
import org.springframework.util.unit.DataSize;

/**
 * The settings of the logging module, bound to {@code stratum.logging.*}. Logging starts before any
 * bean exists, so they are read straight from the environment, never from Java configuration.
 *
 * @param format how console and file lines are written. Defaults to {@code ecs}.
 * @param maxFieldLength the longest a single value or stack trace may be; anything longer is cut.
 *     Defaults to 16KB; from 1KB to 1MB.
 * @param redaction what is masked on top of Stratum's built-in rules
 */
@Builder(toBuilder = true)
@ConfigurationProperties(prefix = LoggingConstants.PREFIX)
public record LoggingProperties(
    @DefaultValue("ecs") LogFormat format,
    @DefaultValue("16KB") DataSize maxFieldLength,
    @DefaultValue Redaction redaction) {

  /**
   * Returns whether {@code stratum.logging.enabled} is {@code true}. Unset means off.
   *
   * @param environment the application's environment
   * @return whether the module is on
   */
  public static boolean isEnabled(Environment environment) {
    return Boolean.TRUE.equals(
        Binder.get(environment).bind(LoggingConstants.ENABLED, Boolean.class).orElse(false));
  }

  /**
   * Binds {@code stratum.logging.*}, with the defaults for anything unset.
   *
   * @param environment the application's environment
   * @return the settings
   */
  public static LoggingProperties bind(Environment environment) {
    return Binder.get(environment).bindOrCreate(LoggingConstants.PREFIX, LoggingProperties.class);
  }

  /** How log lines are written. Every format except {@link #TEXT} is one JSON object. */
  @Getter
  @RequiredArgsConstructor
  public enum LogFormat {

    /** Elastic Common Schema, the default: the same field names in every service. */
    ECS("ecs"),

    /** The Logstash JSON format. */
    LOGSTASH("logstash"),

    /** The Graylog Extended Log Format. */
    GELF("gelf"),

    /** Plain text for people to read on a laptop. It is not redacted. */
    TEXT(null);

    /** Spring Boot's name for the format; {@code null} for plain text. */
    private final @Nullable String structuredFormat;
  }

  /**
   * The service's own redaction rules, added to Stratum's built-in ones, which cannot be removed.
   * What these rules find is always masked.
   *
   * @param extraKeys more field and key names whose values are masked, such as {@code iban-hash}
   * @param extraPatterns more regular expressions whose matches are masked
   */
  @Builder(toBuilder = true)
  public record Redaction(
      @DefaultValue({}) List<String> extraKeys, @DefaultValue({}) List<String> extraPatterns) {

    /** Keeps unchangeable copies of the lists. */
    public Redaction {
      extraKeys = List.copyOf(extraKeys);
      extraPatterns = List.copyOf(extraPatterns);
    }
  }
}
