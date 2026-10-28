package com.exepex.stratum.logging;

import java.util.List;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * The settings of the logging module, bound to {@code stratum.logging.*}. Logging starts before any
 * bean exists, so they come from properties only, never from Java configuration.
 *
 * @param format how console lines are written. Defaults to {@code ecs}.
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
