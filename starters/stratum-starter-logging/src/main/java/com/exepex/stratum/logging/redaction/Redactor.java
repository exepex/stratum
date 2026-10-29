package com.exepex.stratum.logging.redaction;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.logging.LoggingErrorCode;
import com.exepex.stratum.logging.LoggingProperties;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.json.WritableJson;

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

  /** Written instead of a value that could not be masked. */
  static final String REDACTION_FAILED = "[REDACTION_FAILED]";

  /** Added to the end of a value that was cut. */
  static final String TRUNCATION_MARKER = "...[truncated]";

  /** A character of a word, which a cut must not split. */
  private static final IntPredicate NOT_WHITESPACE =
      character -> !Character.isWhitespace(character);

  /** A character of a number written in groups, such as {@code 4111 1111} or {@code 4111.1111}. */
  private static final IntPredicate DIGIT_OR_GROUP_SEPARATOR =
      character -> Character.isDigit(character) || " -./_".indexOf(character) >= 0;

  /** Masks values by field name. */
  private final SensitiveFieldNames fieldNames;

  /** Masks values by what the text looks like. */
  private final UnaryOperator<String> contentMaskingRules;

  /** The longest a value may be, in characters. */
  private final int maxFieldLength;

  /**
   * Makes a redactor from the module's settings.
   *
   * @param loggingProperties the logging settings, already validated
   * @return the redactor
   * @throws StratumException {@code SX-LOGGING-1002} if an extra pattern is not a valid regular
   *     expression
   */
  public static Redactor from(LoggingProperties loggingProperties) {
    var redaction = loggingProperties.redaction();
    var fieldNames = new SensitiveFieldNames(redaction.extraKeys());
    var contentMaskingRules =
        ContentMaskingRules.allRulesInOrder(fieldNames, compile(redaction.extraPatterns()));
    return Redactor.builder()
        .fieldNames(fieldNames)
        .contentMaskingRules(contentMaskingRules)
        .maxFieldLength((int) loggingProperties.maxFieldLength().toBytes())
        .build();
  }

  /**
   * Returns the value as it may be written. Text is cut to the maximum length and masked. Any other
   * value, such as a number or an object, is masked by its name or its {@code toString()}.
   *
   * @param fieldName the value's field name; {@code null} when it has none
   * @param logValue the value
   * @return the value to write
   */
  public @Nullable Object redact(@Nullable String fieldName, @Nullable Object logValue) {
    try {
      return switch (logValue) {
        case null -> null;
        case String text -> redactText(fieldName, truncateToMaxFieldLength(text));
        case WritableJson json -> redactJson(fieldName, json);
        case Map<?, ?> _, Iterable<?> _, Object[] _ -> maskByName(fieldName, logValue);
        default -> redactOther(fieldName, logValue);
      };
    } catch (IllegalArgumentException
        | IllegalStateException
        | IndexOutOfBoundsException
        | StackOverflowError _) {
      // Fail closed: a value that could not be masked must never be written as it was. A service's
      // extra pattern can overflow the stack on long text, and must not stop the caller's thread.
      return REDACTION_FAILED;
    }
  }

  /**
   * Masks a value such as a number or an object by its text, which may hold a card number or a
   * {@code toString()} with secrets. The value is kept as it is when its text needs no masking.
   */
  private Object redactOther(@Nullable String fieldName, Object logValue) {
    // A toString() that returns null is written as the text null: Spring Boot cannot write it.
    var ownText = Optional.ofNullable(logValue.toString());
    var text = ownText.orElse("null");
    var redacted = redactText(fieldName, truncateToMaxFieldLength(text));
    return ownText.filter(redacted::equals).isPresent() ? logValue : redacted;
  }

  /**
   * Masks JSON when Spring Boot writes it. Spring Boot's own nested objects write straight into the
   * log line, passing their values here one by one, so nothing reaches the buffer. Any other JSON
   * is written as it is, so its text is masked; masked JSON is written as a string, because masking
   * can leave text that is not valid JSON.
   */
  private WritableJson redactJson(@Nullable String fieldName, WritableJson json) {
    return out -> writeRedactedJson(fieldName, json, out);
  }

  /** Writes the JSON to the output: as it is when nothing is masked, else as a masked string. */
  private void writeRedactedJson(@Nullable String fieldName, WritableJson json, Appendable out)
      throws IOException {
    var buffer = new StringBuilder();
    json.to(buffer);
    var text = buffer.toString();
    if (text.isEmpty()) {
      return;
    }
    var redacted = redact(fieldName, text);
    if (text.equals(redacted)) {
      out.append(text);
    } else {
      JsonWriter.standard().write(redacted).to(out);
    }
  }

  /**
   * Masks a map, a collection or an array by its field name only: Spring Boot passes the values
   * inside it on one by one. A {@code toString()} that returns null is masked as the text null.
   */
  private Object maskByName(@Nullable String fieldName, Object logValue) {
    return fieldNames
        .mask(fieldName, () -> Objects.requireNonNullElse(logValue.toString(), "null"))
        .map(Object.class::cast)
        .orElse(logValue);
  }

  /** Masks text by its field name, or else by its content. */
  private String redactText(@Nullable String fieldName, String text) {
    return fieldNames.mask(fieldName, () -> text).orElseGet(() -> contentMaskingRules.apply(text));
  }

  /**
   * Cuts text longer than the maximum length. A secret that crosses the limit would lose the shape
   * the rules look for, so the cut also drops the word it falls in and any digits just before it,
   * such as the first groups of a card number.
   */
  private String truncateToMaxFieldLength(String text) {
    if (text.length() <= maxFieldLength) {
      return text;
    }
    var cutsWord = !Character.isWhitespace(text.charAt(maxFieldLength));
    var wordEnd =
        cutsWord
            ? maxFieldLength - MaskedText.countTrailing(text, 0, maxFieldLength, NOT_WHITESPACE)
            : maxFieldLength;
    var end = wordEnd - MaskedText.countTrailing(text, 0, wordEnd, DIGIT_OR_GROUP_SEPARATOR);
    return text.substring(0, end) + TRUNCATION_MARKER;
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
    } catch (PatternSyntaxException _) {
      return Optional.empty();
    }
  }
}
