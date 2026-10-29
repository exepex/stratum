package com.exepex.stratum.logging;

import com.exepex.stratum.core.properties.StratumCustomizers;
import com.exepex.stratum.core.properties.StratumPropertiesResolver;
import jakarta.validation.Validation;
import jakarta.validation.constraints.AssertTrue;
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
 * The settings of the logging module, bound to {@code stratum.logging.*} and then changed by the
 * service's {@link LoggingPropertiesCustomizer} classes, which win. The descriptions of the
 * components are what IDEs show, so they are plain sentences.
 *
 * @param format How console and file lines are written: ecs, logstash, gelf or text. Defaults to
 *     ecs. Text is plain and not redacted.
 * @param maxFieldLength The longest a single value or stack trace may be; anything longer is cut.
 *     From 1KB to 1MB. Defaults to 16KB.
 * @param stackTraceRoot Which exception of a stack trace is written first: first, the root cause,
 *     so that a stack trace cut at max-field-length still shows why it failed, or last, the
 *     exception that was caught. Sets Spring Boot's logging.structured.json.stacktrace.root, which
 *     wins if the service sets it. Defaults to first.
 * @param redaction What is masked on top of Stratum's built-in rules.
 */
@Builder(toBuilder = true)
@ConfigurationProperties(prefix = LoggingConstants.PREFIX)
public record LoggingProperties(
    @DefaultValue("ecs") LogFormat format,
    @DefaultValue("16KB") DataSize maxFieldLength,
    @DefaultValue("first") StackTraceRoot stackTraceRoot,
    @DefaultValue Redaction redaction) {

  /** The shortest allowed {@code max-field-length}. */
  private static final DataSize MIN_FIELD_LENGTH = DataSize.ofKilobytes(1);

  /** The longest allowed {@code max-field-length}. */
  private static final DataSize MAX_FIELD_LENGTH = DataSize.ofMegabytes(1);

  /**
   * Returns the settings the module uses: {@code stratum.logging.*} with the defaults for anything
   * unset, changed by every {@link LoggingPropertiesCustomizer} in {@code
   * META-INF/spring.factories}, then validated. Logging starts before any bean exists, so this
   * makes its own validator.
   *
   * @param environment the application's environment
   * @return the settings
   * @throws jakarta.validation.ConstraintViolationException {@code SX-CORE-1003} if a setting
   *     breaks its rules
   */
  public static LoggingProperties resolve(Environment environment) {
    var boundProperties =
        Binder.get(environment).bindOrCreate(LoggingConstants.PREFIX, LoggingProperties.class);
    var customizers = StratumCustomizers.load(LoggingPropertiesCustomizer.class);
    try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
      return new StratumPropertiesResolver(validatorFactory.getValidator())
          .resolve(boundProperties, customizers.stream());
    }
  }

  /**
   * Returns whether {@code max-field-length} is from 1KB to 1MB. Validation reports a value out of
   * that range as {@code stratum.logging.max-field-length-in-range breaks @AssertTrue}.
   *
   * @return whether the length is in range
   */
  @AssertTrue
  public boolean isMaxFieldLengthInRange() {
    return maxFieldLength.compareTo(MIN_FIELD_LENGTH) >= 0
        && maxFieldLength.compareTo(MAX_FIELD_LENGTH) <= 0;
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

  /** Which exception of a stack trace is written first. */
  public enum StackTraceRoot {

    /** The root cause, the default. */
    FIRST,

    /** The exception that was caught, as Java prints it. */
    LAST
  }

  /**
   * The service's own redaction rules, added to Stratum's built-in ones, which cannot be removed.
   * What these rules find is always masked.
   *
   * @param extraKeys More field and key names whose values are masked, such as iban-hash. Defaults
   *     to none.
   * @param extraPatterns More regular expressions whose matches are masked. Defaults to none.
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
