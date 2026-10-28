package com.exepex.stratum.logging.redaction;

import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;

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
    var masked = new StringBuilder(text.length());
    var keys = KEY.matcher(text);
    var copied = 0;
    var from = 0;
    while (from < text.length() && keys.find(from)) {
      var key = keys.group(1);
      var value = fieldNames.isSensitive(key) ? Value.at(text, key, keys.end()) : null;
      if (value == null || value.isEmpty()) {
        // A value that is not masked may itself hold pairs, as in a JSON message string.
        from = Math.max(keys.end(), keys.start() + 1);
        continue;
      }
      var mask = fieldNames.mask(key, () -> value.content(text)).orElse(Masks.MASK);
      masked.append(text, copied, value.start()).append(value.wrap(mask));
      copied = value.end();
      from = value.end();
    }
    return masked.append(text, copied, text.length()).toString();
  }

  /**
   * Where a value is in the text.
   *
   * @param quote the quote around it, or an empty string when it has none
   * @param start where it starts, with its opening quote
   * @param contentEnd where its content ends, before any closing quote
   * @param end where it ends, after any closing quote
   */
  private record Value(String quote, int start, int contentEnd, int end) {

    /** Finds the value that starts at the index. */
    private static Value at(String text, String key, int start) {
      if (text.startsWith(ESCAPED_QUOTE, start)) {
        var close = text.indexOf(ESCAPED_QUOTE, start + ESCAPED_QUOTE.length());
        return close < 0
            ? new Value(ESCAPED_QUOTE, start, text.length(), text.length())
            : new Value(ESCAPED_QUOTE, start, close, close + ESCAPED_QUOTE.length());
      }
      var first = start < text.length() ? text.charAt(start) : ' ';
      if (first == '"' || first == '\'') {
        var close = closingQuote(text, start + 1, first);
        return close < 0
            ? new Value(String.valueOf(first), start, text.length(), text.length())
            : new Value(String.valueOf(first), start, close, close + 1);
      }
      var end =
          SensitiveFieldNames.isHeaderList(key) ? lineEnd(text, start) : plainEnd(text, start);
      return new Value("", start, end, end);
    }

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

  /** Returns the position of the closing quote, skipping escaped ones, or -1 if there is none. */
  private static int closingQuote(String text, int from, char quote) {
    return IntStream.range(from, text.length())
        .filter(position -> text.charAt(position) == quote && !isEscaped(text, from, position))
        .findFirst()
        .orElse(-1);
  }

  /** Returns whether an odd number of backslashes, which escape it, comes before the position. */
  private static boolean isEscaped(String text, int from, int position) {
    var backslashes =
        IntStream.iterate(
                position - 1,
                previous -> previous >= from && text.charAt(previous) == '\\',
                previous -> previous - 1)
            .count();
    return backslashes % 2 == 1;
  }

  /** Returns the end of the line, without trailing spaces. */
  private static int lineEnd(String text, int from) {
    var lineBreak =
        IntStream.range(from, text.length())
            .filter(position -> text.charAt(position) == '\r' || text.charAt(position) == '\n')
            .findFirst()
            .orElse(text.length());
    return from + text.substring(from, lineBreak).stripTrailing().length();
  }

  /** Returns the end of an unquoted value, without trailing spaces. */
  private static int plainEnd(String text, int from) {
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
  private static boolean startsNextKey(Matcher nextKey, String text, int from, int position) {
    return position > from
        && Character.isWhitespace(text.charAt(position))
        && !Character.isWhitespace(text.charAt(position - 1))
        && nextKey.region(position, text.length()).lookingAt();
  }
}
