package com.exepex.stratum.logging.redaction;

import com.exepex.stratum.core.error.CoreErrorCode;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.logging.LoggingErrorCode;
import com.exepex.stratum.logging.LoggingProperties;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntPredicate;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.IntStream;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.json.WritableJson;
import org.springframework.util.unit.DataSize;

/**
 * Masks secrets and personal data in one log value: first by its field name, then by what the text
 * looks like. A value longer than {@code stratum.logging.max-field-length} is cut first. If masking
 * fails, the value is replaced, never written as it was.
 *
 * <p>Names, addresses and other data with no telltale shape are found only by their field or key
 * name. This is a safety net, not a licence to log personal data.
 */
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(access = AccessLevel.PRIVATE)
public final class Redactor {

  /** Replaces a masked value, or the part of it that is hidden. */
  static final String MASK = "****";

  /** Written instead of a value that could not be masked. */
  static final String FAILED = "[REDACTION_FAILED]";

  /** Added to the end of a value that was cut. */
  static final String TRUNCATED = "...[truncated]";

  /** How many characters an identifier such as a card number keeps visible at its end. */
  private static final int VISIBLE_END = 4;

  /** A character of a word, which a cut must not split. */
  private static final IntPredicate IS_WORD = character -> !Character.isWhitespace(character);

  /** A character of a number written in groups, such as {@code 4111 1111}. */
  private static final IntPredicate IS_NUMBER =
      character -> Character.isDigit(character) || character == ' ' || character == '-';

  /** The shortest allowed {@code max-field-length}. */
  private static final DataSize MIN_FIELD_LENGTH = DataSize.ofKilobytes(1);

  /** The longest allowed {@code max-field-length}. */
  private static final DataSize MAX_FIELD_LENGTH = DataSize.ofMegabytes(1);

  /** Masks values by field name. */
  private final SensitiveFieldNames fieldNames;

  /** Masks values by what the text looks like. */
  private final UnaryOperator<String> contentRules;

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
    return Redactor.builder()
        .fieldNames(fieldNames)
        .contentRules(contentRules)
        .maxFieldLength((int) maxFieldLength.toBytes())
        .build();
  }

  /**
   * Returns the value as it may be written. Text is cut to the maximum length and masked. Any other
   * value, such as a number or an object, is masked by its name or its {@code toString()}.
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
        case WritableJson _, Map<?, ?> _, Iterable<?> _, Object[] _ -> maskByName(fieldName, value);
        default -> redactOther(fieldName, value);
      };
    } catch (IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException failure) {
      // Fail closed: a value that could not be masked must never be written as it was.
      return FAILED;
    }
  }

  /**
   * Masks a value such as a number or an object by its text, which may hold a card number or a
   * {@code toString()} with secrets. The value is kept as it is when its text needs no masking.
   */
  private Object redactOther(@Nullable String fieldName, Object value) {
    var text = value.toString();
    var redacted = redactText(fieldName, cut(text));
    return redacted.equals(text) ? value : redacted;
  }

  /**
   * Masks a nested JSON object or a collection by its field name only: Spring Boot passes the
   * values inside it on one by one.
   */
  private Object maskByName(@Nullable String fieldName, Object value) {
    return fieldNames.mask(fieldName, value::toString).map(Object.class::cast).orElse(value);
  }

  /** Masks text by its field name, or else by its content. */
  private String redactText(@Nullable String fieldName, String text) {
    return fieldNames.mask(fieldName, () -> text).orElseGet(() -> contentRules.apply(text));
  }

  /**
   * Cuts text longer than the maximum length. A secret that crosses the limit would lose the shape
   * the rules look for, so the cut also drops the word it falls in and any digits just before it,
   * such as the first groups of a card number.
   */
  private String cut(String text) {
    if (text.length() <= maxFieldLength) {
      return text;
    }
    var kept = text.substring(0, maxFieldLength);
    var cutsWord = !Character.isWhitespace(text.charAt(maxFieldLength));
    var wordEnd = cutsWord ? kept.length() - trailing(kept, IS_WORD) : kept.length();
    var end = wordEnd - trailing(kept.substring(0, wordEnd), IS_NUMBER);
    return kept.substring(0, end) + TRUNCATED;
  }

  /** Returns how many characters at the end of the text match the test. */
  private static int trailing(String text, IntPredicate test) {
    return (int)
        IntStream.iterate(
                text.length() - 1,
                position -> position >= 0 && test.test(text.charAt(position)),
                position -> position - 1)
            .count();
  }

  /**
   * Masks a value but keeps its last four characters, such as {@code ****1111} for a card; a value
   * of four characters or fewer is masked in full.
   *
   * @param value the value to mask
   * @return the masked value
   */
  static String keepingLastFour(String value) {
    return value.length() <= VISIBLE_END
        ? MASK
        : MASK + value.substring(value.length() - VISIBLE_END);
  }

  /** Compiles the service's patterns, stopping startup at the first invalid one. */
  private static List<Pattern> compile(List<String> patterns) {
    return IntStream.range(0, patterns.size())
        .mapToObj(
            position ->
                compileOrEmpty(patterns.get(position))
                    .orElseThrow(
                        // Neither the pattern nor the parser's message, which can quote parts of
                        // it, is shown: the pattern describes what the service wants hidden.
                        () ->
                            new StratumException(
                                LoggingErrorCode.INVALID_REDACTION_PATTERN,
                                "pattern "
                                    + (position + 1)
                                    + " is not a valid regular expression")))
        .toList();
  }

  /** Returns the compiled pattern, or empty if it is not a valid regular expression. */
  private static Optional<Pattern> compileOrEmpty(String pattern) {
    try {
      return Optional.of(Pattern.compile(pattern));
    } catch (PatternSyntaxException invalid) {
      return Optional.empty();
    }
  }
}
