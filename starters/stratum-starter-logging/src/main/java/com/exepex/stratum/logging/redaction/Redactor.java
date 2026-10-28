package com.exepex.stratum.logging.redaction;

import com.exepex.stratum.core.error.CoreErrorCode;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.logging.LoggingErrorCode;
import com.exepex.stratum.logging.LoggingProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.util.unit.DataSize;

/**
 * Masks secrets and personal data in one log value: first by its field name, then by what the text
 * looks like. A value longer than {@code stratum.logging.max-field-length} is cut first. If masking
 * fails, the value is replaced, never written as it was.
 *
 * <p>Names, addresses and other data with no telltale shape are found only by their field or key
 * name. This is a safety net, not a licence to log personal data.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class Redactor {

  /** Written instead of a value that could not be masked. */
  static final String FAILED = "[REDACTION_FAILED]";

  /** Added to the end of a value that was cut. */
  static final String TRUNCATED = "...[truncated]";

  /** The shortest allowed {@code max-field-length}. */
  private static final DataSize MIN_FIELD_LENGTH = DataSize.ofKilobytes(1);

  /** The longest allowed {@code max-field-length}. */
  private static final DataSize MAX_FIELD_LENGTH = DataSize.ofMegabytes(1);

  /** Masks values by field name. */
  private final SensitiveFieldNames fieldNames;

  /** Masks values by what the text looks like. */
  private final List<UnaryOperator<String>> contentRules;

  /** The longest a value may be, in characters. */
  private final int maxFieldLength;

  /**
   * Makes a redactor from the module's settings.
   *
   * @param settings the logging settings
   * @return the redactor
   * @throws StratumException {@code SX-LOGGING-1002} if an extra pattern is not a valid regular
   *     expression, {@code SX-CORE-1003} if {@code max-field-length} is out of range
   */
  public static Redactor from(LoggingProperties settings) {
    var maxFieldLength = settings.maxFieldLength();
    if (maxFieldLength.compareTo(MIN_FIELD_LENGTH) < 0
        || maxFieldLength.compareTo(MAX_FIELD_LENGTH) > 0) {
      throw new StratumException(
          CoreErrorCode.INVALID_SETTINGS,
          "stratum.logging.max-field-length must be from 1KB to 1MB");
    }
    var redaction = settings.redaction();
    var fieldNames = SensitiveFieldNames.withExtraKeys(redaction.extraKeys());
    var contentRules = ContentRules.of(fieldNames, compile(redaction.extraPatterns()));
    return new Redactor(fieldNames, contentRules, (int) maxFieldLength.toBytes());
  }

  /**
   * Returns the value as it may be written. Text is cut to the maximum length and masked; any other
   * value is masked only when its field name is sensitive.
   *
   * @param fieldName the value's field name; {@code null} when it has none
   * @param value the value
   * @return the value to write
   */
  public @Nullable Object redact(@Nullable String fieldName, @Nullable Object value) {
    try {
      return switch (value) {
        case null -> null;
        case String text -> redactText(fieldName, cut(text));
        default ->
            fieldNames.mask(fieldName, value::toString).map(Object.class::cast).orElse(value);
      };
    } catch (IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException e) {
      // Fail closed: a value that could not be masked must never be written as it was.
      return FAILED;
    }
  }

  /** Masks text by its field name, or else by its content. */
  private String redactText(@Nullable String fieldName, String text) {
    return fieldNames.mask(fieldName, () -> text).orElseGet(() -> redactContent(text));
  }

  /** Applies every content rule, then the phone number search. */
  private String redactContent(String text) {
    var masked = text;
    for (var rule : contentRules) {
      masked = rule.apply(masked);
    }
    return PhoneNumbers.mask(masked);
  }

  /** Cuts text longer than the maximum length. */
  private String cut(String text) {
    return text.length() > maxFieldLength ? text.substring(0, maxFieldLength) + TRUNCATED : text;
  }

  /** Compiles the service's patterns, stopping startup at the first invalid one. */
  private static List<Pattern> compile(List<String> patterns) {
    var compiled = new ArrayList<Pattern>(patterns.size());
    for (var pattern : patterns) {
      // Neither the pattern nor the parser's message, which can quote parts of it, is shown: the
      // pattern describes what the service wants hidden.
      if (!isValid(pattern)) {
        throw new StratumException(
            LoggingErrorCode.INVALID_REDACTION_PATTERN,
            "pattern " + (compiled.size() + 1) + " is not a valid regular expression");
      }
      compiled.add(Pattern.compile(pattern));
    }
    return compiled;
  }

  /** Returns whether the pattern compiles. */
  private static boolean isValid(String pattern) {
    try {
      Pattern.compile(pattern);
      return true;
    } catch (PatternSyntaxException e) {
      return false;
    }
  }
}
