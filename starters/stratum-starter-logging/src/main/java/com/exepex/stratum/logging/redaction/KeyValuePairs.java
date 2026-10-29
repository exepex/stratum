package com.exepex.stratum.logging.redaction;

import com.exepex.stratum.logging.redaction.ContentRules.Replacement;
import java.util.Optional;
import java.util.function.IntPredicate;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Masks the value of each sensitive key in text, as in {@code password=x}, {@code "token":"x"},
 * {@code 'token': 'x'}, escaped JSON such as {@code \"token\":\"x\"}, {@code ?api_key=x&}, {@code
 * password%3Dx} or Lombok's {@code User(email=x, ...)}. A quoted value ends at its closing quote.
 * An unquoted value runs over spaces until the next key, or until one of {@code , ; & ) } ] "} or a
 * line break; the value of a header that holds a list, such as {@code Cookie: a=1; b=2}, runs to
 * the end of the line.
 *
 * <p>Only the key is found with a regular expression, and a value's end is looked for only after a
 * sensitive key, by scanning, so a long or hostile text cannot make matching slow.
 */
@RequiredArgsConstructor
final class KeyValuePairs implements UnaryOperator<String> {

  /**
   * A key, in optional plain, single or escaped quotes, followed by {@code =}, {@code :} or %3D.
   */
  private static final Pattern KEY =
      Pattern.compile(
          "\\\\?[\"']?([A-Za-z][A-Za-z0-9_.-]{0,63}+)\\\\?[\"']?\\s*+(?:[=:]|%3[DdAa])\\s*+");

  /** Spaces and then another key, which end an unquoted value. */
  private static final Pattern NEXT_KEY = Pattern.compile("\\s++[A-Za-z0-9_.-]++\\s*+[=:]");

  /** Opens and closes an escaped quoted value inside a JSON string. */
  private static final String ESCAPED_QUOTE = "\\\"";

  /**
   * In escaped JSON a backslash is written as four, so the count of backslashes before a quote is
   * read modulo four: one closes the value, three is a quote inside it.
   */
  private static final int ESCAPED_JSON_CYCLE = 4;

  /** The characters that end an unquoted value. */
  private static final String VALUE_ENDS = ",;&)}]\"\r\n";

  /** The names whose values are masked. */
  private final SensitiveFieldNames fieldNames;

  /**
   * Masks the value of every sensitive key in the text.
   *
   * @param text the text to mask
   * @return the text with each sensitive value masked
   */
  @Override
  public String apply(String text) {
    if (text.indexOf('=') < 0 && text.indexOf(':') < 0 && !text.contains("%3")) {
      return text;
    }
    var keys = KEY.matcher(text);
    var replacements =
        Stream.iterate(
                nextPair(text, keys, 0),
                Optional::isPresent,
                previousPair -> nextPair(text, keys, previousPair.orElseThrow().searchFrom()))
            .map(Optional::orElseThrow)
            .map(Pair::replacement)
            .flatMap(Optional::stream)
            .toList();
    return ContentRules.replace(text, replacements);
  }

  /**
   * Finds the next key from the position, and the masked form of its value if the key is sensitive.
   */
  private Optional<Pair> nextPair(String text, Matcher keys, int from) {
    if (from >= text.length() || !keys.find(from)) {
      return Optional.empty();
    }
    var key = keys.group(1);
    var value = fieldNames.isSensitive(key) ? valueAt(text, key, keys.end()) : null;
    if (value == null || value.isEmpty()) {
      // A value that is not masked may itself hold pairs, as in a JSON message string.
      return Optional.of(new Pair(Math.max(keys.end(), keys.start() + 1), null));
    }
    var mask = fieldNames.mask(key, () -> value.content(text)).orElse(Redactor.MASK);
    return Optional.of(
        new Pair(
            value.end(),
            Replacement.builder()
                .start(value.start())
                .end(value.end())
                .text(value.wrap(mask))
                .build()));
  }

  /** Finds the value that starts at the index. */
  private Value valueAt(String text, String key, int start) {
    if (text.startsWith(ESCAPED_QUOTE, start)) {
      // Inside escaped JSON, a quote in the value is written \\\" and the closing one \".
      var closing =
          closingQuote(
              text,
              start + ESCAPED_QUOTE.length(),
              '"',
              backslashes -> backslashes % ESCAPED_JSON_CYCLE == 1);
      return closing < 0
          ? Value.builder()
              .quote(ESCAPED_QUOTE)
              .start(start)
              .contentEnd(text.length())
              .end(text.length())
              .build()
          : Value.builder()
              .quote(ESCAPED_QUOTE)
              .start(start)
              .contentEnd(closing - 1)
              .end(closing + 1)
              .build();
    }
    var opening = start < text.length() ? text.charAt(start) : ' ';
    if (opening == '"' || opening == '\'') {
      var closing = closingQuote(text, start + 1, opening, backslashes -> backslashes % 2 == 0);
      return closing < 0
          ? Value.builder()
              .quote(String.valueOf(opening))
              .start(start)
              .contentEnd(text.length())
              .end(text.length())
              .build()
          : Value.builder()
              .quote(String.valueOf(opening))
              .start(start)
              .contentEnd(closing)
              .end(closing + 1)
              .build();
    }
    if (opening == '[' || opening == '{') {
      var end = containerEnd(text, start);
      return Value.builder().quote("").start(start).contentEnd(end).end(end).build();
    }
    var end = fieldNames.isHeaderList(key) ? lineEnd(text, start) : plainEnd(text, start);
    return Value.builder().quote("").start(start).contentEnd(end).end(end).build();
  }

  /**
   * Returns the end of a JSON array or object that starts at the position: just after its closing
   * bracket, or the end of the text if it has none. Brackets inside strings are skipped.
   */
  private int containerEnd(String text, int start) {
    // The stream never ends, so it always has a first match.
    var finishedScan =
        Stream.iterate(
                ContainerScan.builder().position(start + 1).depth(1).build(),
                scan -> scan.next(text))
            .filter(scan -> scan.depth() == 0 || scan.position() >= text.length())
            .findFirst()
            .orElseThrow();
    return finishedScan.depth() == 0 ? finishedScan.position() : text.length();
  }

  /**
   * Returns the position of the closing quote, or -1 if there is none. A quote closes the value
   * when the number of backslashes before it passes the test; any other quote is part of the value.
   */
  private int closingQuote(String text, int from, char quote, IntPredicate closes) {
    return IntStream.range(from, text.length())
        .filter(
            position ->
                text.charAt(position) == quote
                    && closes.test(backslashesBefore(text, from, position)))
        .findFirst()
        .orElse(-1);
  }

  /** Returns how many backslashes come just before the position, counting back to {@code from}. */
  private int backslashesBefore(String text, int from, int position) {
    return (int)
        IntStream.iterate(
                position - 1,
                previous -> previous >= from && text.charAt(previous) == '\\',
                previous -> previous - 1)
            .count();
  }

  /** Returns the end of the line, without trailing spaces. */
  private int lineEnd(String text, int from) {
    var lineBreak =
        IntStream.range(from, text.length())
            .filter(position -> text.charAt(position) == '\r' || text.charAt(position) == '\n')
            .findFirst()
            .orElse(text.length());
    return from + text.substring(from, lineBreak).stripTrailing().length();
  }

  /** Returns the end of an unquoted value, without trailing spaces. */
  private int plainEnd(String text, int from) {
    var nextKey = NEXT_KEY.matcher(text);
    var stop =
        IntStream.range(from, text.length())
            .filter(
                position ->
                    VALUE_ENDS.indexOf(text.charAt(position)) >= 0
                        || startsNextKey(nextKey, text, from, position))
            .findFirst()
            .orElse(text.length());
    return from + text.substring(from, stop).stripTrailing().length();
  }

  /** Returns whether the spaces that start at the position are followed by another key. */
  private boolean startsNextKey(Matcher nextKey, String text, int from, int position) {
    return position > from
        && Character.isWhitespace(text.charAt(position))
        && !Character.isWhitespace(text.charAt(position - 1))
        && nextKey.region(position, text.length()).lookingAt();
  }

  /**
   * A key found in the text.
   *
   * @param searchFrom where the search for the next key starts
   * @param maskedValue the masked form of its value; {@code null} when it is not masked
   */
  private record Pair(int searchFrom, @Nullable Replacement maskedValue) {

    /** Returns the masked form of the value, if it is masked. */
    private Optional<Replacement> replacement() {
      return Optional.ofNullable(maskedValue);
    }
  }

  /**
   * Where a value is in the text.
   *
   * @param quote the quote around it, or an empty string when it has none
   * @param start where it starts, with its opening quote
   * @param contentEnd where its content ends, before any closing quote
   * @param end where it ends, after any closing quote
   */
  @Builder
  private record Value(String quote, int start, int contentEnd, int end) {

    /** Returns whether there is nothing to mask. */
    private boolean isEmpty() {
      return contentEnd <= start + quote.length();
    }

    /** Returns the value without its quotes. */
    private String content(String text) {
      return text.substring(start + quote.length(), contentEnd);
    }

    /** Returns the mask in the value's quotes, with a closing quote only if the value had one. */
    private String wrap(String mask) {
      return quote + mask + (end > contentEnd ? quote : "");
    }
  }

  /**
   * One step of the scan for the end of a JSON array or object.
   *
   * @param position the next character to read
   * @param depth how many brackets are open
   * @param inString whether the scan is inside a quoted string
   */
  @Builder(toBuilder = true)
  private record ContainerScan(int position, int depth, boolean inString) {

    /** Reads the character at the position and returns the state after it. */
    private ContainerScan next(String text) {
      var character = text.charAt(position);
      if (inString) {
        return switch (character) {
          case '\\' -> toBuilder().position(position + 2).build();
          case '"' -> toBuilder().position(position + 1).inString(false).build();
          default -> toBuilder().position(position + 1).build();
        };
      }
      return switch (character) {
        case '"' -> toBuilder().position(position + 1).inString(true).build();
        case '[', '{' -> toBuilder().position(position + 1).depth(depth + 1).build();
        case ']', '}' -> toBuilder().position(position + 1).depth(depth - 1).build();
        default -> toBuilder().position(position + 1).build();
      };
    }
  }
}
