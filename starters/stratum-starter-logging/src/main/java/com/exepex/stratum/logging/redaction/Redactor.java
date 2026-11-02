package com.exepex.stratum.logging.redaction;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.logging.LoggingErrorCode;
import com.exepex.stratum.logging.LoggingProperties;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
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
 * looks like. A value longer than {@code stratum.logging.max-field-length} is cut first, and a
 * list, map or array keeps its first {@code stratum.logging.max-elements} elements. Each value's
 * text is read once. If anything fails while masking a value, it is replaced, never written as it
 * was.
 *
 * <p>Names, addresses and other data with no telltale shape are found only by their field or key
 * name. This is a safety net, not a licence to log personal data.
 */
@StratumInternal
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

  /** Masks text with the service's own patterns only. */
  private final UnaryOperator<String> serviceRules;

  /** Masks stack traces with Stratum's rules, leaving their frame lines as they are. */
  private final StackTraceMasker stackTraceMasker;

  /** Keeps the first elements of lists, maps and arrays. */
  private final ElementLimit elementLimit;

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
    var serviceRules = ContentMaskingRules.serviceRules(compile(redaction.extraPatterns()));
    var builtInRules = ContentMaskingRules.builtInRules(fieldNames);
    return Redactor.builder()
        .fieldNames(fieldNames)
        .contentMaskingRules(text -> serviceRules.apply(builtInRules.apply(text)))
        .serviceRules(serviceRules)
        .stackTraceMasker(new StackTraceMasker(builtInRules))
        .elementLimit(new ElementLimit(loggingProperties.maxElements()))
        .maxFieldLength((int) loggingProperties.maxFieldLength().toBytes())
        .build();
  }

  /**
   * Returns the value as it may be written. Text is cut to the maximum length and masked. A list,
   * map or array keeps its first elements, which are then masked one by one. Any other value, such
   * as a number or an object, is masked by its name or its {@code toString()}.
   *
   * @param fieldName the value's field name; {@code null} when it has none
   * @param logValue the value
   * @return the value to write
   */
  public @Nullable Object redact(@Nullable String fieldName, @Nullable Object logValue) {
    return failingClosed(
        () ->
            switch (logValue) {
              case null -> null;
              case String text -> redactText(fieldName, truncateToMaxFieldLength(text));
              case WritableJson json -> redactJson(fieldName, json);
              case Object container when elementLimit.holdsElements(container) ->
                  maskByName(fieldName, container);
              default -> writtenOnce(logValue, text -> redactText(fieldName, text));
            });
  }

  /**
   * Returns a value as masked text, for a format that writes text: a list, map or array as its
   * first elements, nested ones too, cut to the maximum length and masked like any other text.
   *
   * @param fieldName the value's field name
   * @param logValue the value
   * @return the text to write
   */
  public String redactAsText(@Nullable String fieldName, @Nullable Object logValue) {
    return Objects.toString(
        failingClosed(
            () -> redactText(fieldName, truncateToMaxFieldLength(elementLimit.textOf(logValue)))));
  }

  /**
   * Returns a stack trace as it may be written: masked in full when its field name is sensitive,
   * such as a service's extra key, else cut to the maximum length and masked, except its frame
   * lines, which hold only class, method and file names. A value that is not text is masked as any
   * other value.
   *
   * @param fieldName the stack trace's field name; {@code null} when it has none
   * @param stackTrace the stack trace
   * @return the stack trace to write
   */
  public @Nullable Object redactStackTrace(
      @Nullable String fieldName, @Nullable Object stackTrace) {
    return stackTrace instanceof String text
        ? failingClosed(() -> redactStackTraceText(fieldName, text))
        : redact(fieldName, stackTrace);
  }

  /**
   * Masks stack trace text by its field name, or else every line but its frames with Stratum's
   * rules, and then the whole trace with the service's own patterns.
   */
  private String redactStackTraceText(@Nullable String fieldName, String text) {
    var truncated = truncateToMaxFieldLength(text);
    return fieldNames
        .mask(fieldName, () -> truncated)
        .orElseGet(() -> serviceRules.apply(stackTraceMasker.mask(truncated)));
  }

  /**
   * Returns a field that Stratum knows holds no personal data, such as {@code userAgent}, as it may
   * be written: cut to the maximum length, and masked only by the service's own extra keys and
   * extra patterns, which always win over Stratum's defaults. A value that is not text is masked
   * only by an extra key; Spring Boot's own JSON, lists, maps and arrays are kept as they are.
   *
   * @param fieldName the field's name
   * @param logValue the value
   * @return the value to write
   */
  public @Nullable Object redactKnownSafe(@Nullable String fieldName, @Nullable Object logValue) {
    return failingClosed(
        () ->
            switch (logValue) {
              case null -> null;
              case Object _ when fieldNames.isExtraKey(fieldName) -> MaskedText.MASK;
              case String text -> serviceRules.apply(truncateToMaxFieldLength(text));
              case WritableJson json -> json;
              case Object container when elementLimit.holdsElements(container) -> container;
              default -> writtenOnce(logValue, UnaryOperator.identity());
            });
  }

  /**
   * Returns the masked value, or {@link #REDACTION_FAILED} if anything failed while masking it,
   * such as a {@code toString()} that throws.
   */
  private @Nullable Object failingClosed(Supplier<@Nullable Object> maskedValue) {
    try {
      return maskedValue.get();
    } catch (RuntimeException | StackOverflowError _) {
      // Fail closed: a value that could not be masked must never be written as it was, and must
      // not lose the rest of the line. A service's extra pattern can overflow the stack on long
      // text, and must not stop the caller's thread.
      return REDACTION_FAILED;
    }
  }

  /**
   * Masks a value such as a number or an object by its text, which may hold a card number or a
   * {@code toString()} with secrets. The text is read once, here, so a {@code toString()} that
   * changes between calls cannot write other text. A number or boolean whose text needs no masking
   * stays a JSON number or boolean, written from that same text.
   */
  private Object writtenOnce(Object logValue, UnaryOperator<String> redaction) {
    var text = elementLimit.textOf(logValue);
    var redacted = redaction.apply(truncateToMaxFieldLength(text));
    return (logValue instanceof Number || logValue instanceof Boolean) && redacted.equals(text)
        ? asJson(text)
        : redacted;
  }

  /** Returns text that is written into the JSON line as it is. */
  private WritableJson asJson(String json) {
    return out -> out.append(json);
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
    // The JSON is read inside the fail-closed boundary, when Spring Boot writes the line.
    var masked = failingClosed(() -> maskedJson(fieldName, json));
    if (masked instanceof WritableJson unchanged) {
      unchanged.to(out);
    } else {
      JsonWriter.standard().write(masked).to(out);
    }
  }

  /** Returns the JSON's text as it is, when masking leaves it unchanged, else the masked text. */
  private Object maskedJson(@Nullable String fieldName, WritableJson json) {
    var text = json.toJsonString();
    var redacted = redact(fieldName, text);
    return text.equals(redacted) ? asJson(text) : Objects.requireNonNull(redacted);
  }

  /**
   * Masks a list, map or array by its field name, or else keeps its first elements: Spring Boot
   * passes the elements of the copy on one by one. A value masked by name is masked as the text of
   * its first elements, cut to the maximum length.
   */
  private Object maskByName(@Nullable String fieldName, Object container) {
    return fieldNames
        .mask(fieldName, () -> truncateToMaxFieldLength(elementLimit.textOf(container)))
        .map(Object.class::cast)
        .orElseGet(() -> elementLimit.limited(container));
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
