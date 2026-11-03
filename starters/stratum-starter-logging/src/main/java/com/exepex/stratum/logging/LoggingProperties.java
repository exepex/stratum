package com.exepex.stratum.logging;

import com.exepex.stratum.core.properties.StratumCustomizers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.env.Environment;
import org.springframework.util.unit.DataSize;

/**
 * The settings of the logging module, bound to {@code stratum.logging.*} and then changed by the
 * service's {@link LoggingPropertiesCustomizer} classes, which win. The descriptions of the
 * components are what IDEs show, so they are plain sentences.
 *
 * @param format How console and file lines are written: ecs, logstash, gelf or text. Defaults to
 *     ecs. Text is one masked line per event, for people to read.
 * @param maxFieldLength The longest a single value or stack trace may be; anything longer is cut.
 *     From 1KB to 1MB. Defaults to 16KB.
 * @param maxElements The most elements kept of each logged list, map or array, nested ones too; the
 *     rest are left out and counted in one marker. From 1 to 20. Defaults to 10.
 * @param redaction What is masked on top of Stratum's built-in rules.
 * @param volume How many lines each logger may write, so that a flood of lines cannot slow the
 *     service down.
 */
@Builder(toBuilder = true)
@ConfigurationProperties(prefix = LoggingConstants.PREFIX)
public record LoggingProperties(
    @DefaultValue("ecs") LogFormat format,
    @DefaultValue("16KB") DataSize maxFieldLength,
    @DefaultValue("10") @Min(1) @Max(20) int maxElements,
    @DefaultValue Redaction redaction,
    @DefaultValue @Valid Volume volume) {

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
   * @throws com.exepex.stratum.core.error.StratumException {@code SX-CORE-1003} if a setting breaks
   *     its rules
   */
  public static LoggingProperties resolve(Environment environment) {
    return StratumCustomizers.resolveSettings(
        environment,
        LoggingConstants.PREFIX,
        LoggingProperties.class,
        LoggingPropertiesCustomizer.class);
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

    /** One masked line of text per event, for people to read. */
    TEXT(null);

    /** Spring Boot's name for the JSON format; {@code null} for text, which Stratum writes. */
    private final @Nullable String structuredFormat;
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

  /**
   * How many lines each logger may write per second, at each level, and which share of DEBUG and
   * INFO lines is kept. Lines over the limit are dropped before they are built, and one summary
   * line per interval counts them.
   *
   * @param enabled Whether the limits apply. Defaults to true.
   * @param maxPerSecond The most TRACE, DEBUG or INFO lines one logger writes per second, for each
   *     level. From 1 to 1,000,000. Defaults to 1000.
   * @param warnErrorMaxPerSecond The most WARN or ERROR lines one logger writes per second, for
   *     each level. From 1 to 1,000,000. Defaults to 5000.
   * @param debugKeepOneIn Keeps one in this many TRACE and DEBUG lines, chosen by trace ID so that
   *     a request's lines are kept together. From 1 to 1,000,000. Defaults to 1, which keeps all.
   * @param infoKeepOneIn Keeps one in this many INFO lines, chosen the same way. From 1 to
   *     1,000,000. Defaults to 1, which keeps all.
   * @param neverLimitLoggers The loggers whose lines are never limited or sampled, each with the
   *     loggers below it, such as org.springframework.security. Defaults to Spring Security's.
   * @param maxKeys The most loggers and levels counted on their own; any more share one count per
   *     level. From 64 to 16384. Defaults to 1024.
   * @param reportInterval How often the summary of dropped lines is written. From 1s to 1h.
   *     Defaults to 10s.
   */
  @Builder(toBuilder = true)
  public record Volume(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("1000") @Min(1) @Max(MAX_RATE) int maxPerSecond,
      @DefaultValue("5000") @Min(1) @Max(MAX_RATE) int warnErrorMaxPerSecond,
      @DefaultValue("1") @Min(1) @Max(MAX_RATE) int debugKeepOneIn,
      @DefaultValue("1") @Min(1) @Max(MAX_RATE) int infoKeepOneIn,
      @DefaultValue("org.springframework.security") List<String> neverLimitLoggers,
      @DefaultValue("1024") @Min(64) @Max(16384) int maxKeys,
      @DefaultValue("10s") Duration reportInterval) {

    /** The highest limit and sampling rate. */
    private static final int MAX_RATE = 1_000_000;

    /** The shortest allowed {@code report-interval}. */
    private static final Duration MIN_REPORT_INTERVAL = Duration.ofSeconds(1);

    /** The longest allowed {@code report-interval}. */
    private static final Duration MAX_REPORT_INTERVAL = Duration.ofHours(1);

    /** Keeps an unchangeable copy of the list. */
    public Volume {
      neverLimitLoggers = List.copyOf(neverLimitLoggers);
    }

    /**
     * Returns whether {@code report-interval} is from 1s to 1h. Validation reports a value out of
     * that range as {@code stratum.logging.volume.report-interval-in-range breaks @AssertTrue}.
     *
     * @return whether the interval is in range
     */
    @AssertTrue
    public boolean isReportIntervalInRange() {
      return reportInterval.compareTo(MIN_REPORT_INTERVAL) >= 0
          && reportInterval.compareTo(MAX_REPORT_INTERVAL) <= 0;
    }
  }
}
