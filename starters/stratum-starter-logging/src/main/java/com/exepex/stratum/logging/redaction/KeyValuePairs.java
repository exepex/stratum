package com.exepex.stratum.logging.redaction;

import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;

/**
 * Masks the value of each sensitive key in text, as in {@code password=x}, {@code "token":"x"},
 * {@code ?api_key=x&} or Lombok's {@code User(email=x, ...)}. A quoted value ends at its closing
 * quote. An unquoted value runs over spaces until the next key, or until one of {@code , ; & ) } ]
 * "} or a line break.
 *
 * <p>Only the key is found with a regular expression; the value's end is found by scanning, so a
 * long or hostile value cannot make matching slow.
 */
@RequiredArgsConstructor
final class KeyValuePairs implements UnaryOperator<String> {

  /** A key, in optional quotes, followed by {@code =} or {@code :}. */
  private static final Pattern KEY =
      Pattern.compile("\"?([A-Za-z][A-Za-z0-9_.-]{0,63})\"?\\s*[=:]\\s*");

  /** The start of a following key, which ends an unquoted value. */
  private static final Pattern NEXT_KEY = Pattern.compile("[A-Za-z0-9_.-]+\\s*[=:]");

  /** Starts and ends a quoted value. */
  private static final char QUOTE = '"';

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
    if (text.indexOf('=') < 0 && text.indexOf(':') < 0) {
      return text;
    }
    var masked = new StringBuilder(text.length());
    var keys = KEY.matcher(text);
    var copied = 0;
    var from = 0;
    while (from < text.length() && keys.find(from)) {
      var valueStart = keys.end();
      var quoted = valueStart < text.length() && text.charAt(valueStart) == QUOTE;
      var valueEnd = quoted ? quotedEnd(text, valueStart + 1) : plainEnd(text, valueStart);
      var mask = fieldNames.mask(keys.group(1), () -> value(text, valueStart, valueEnd, quoted));
      if (mask.isPresent() && valueEnd > valueStart) {
        masked
            .append(text, copied, valueStart)
            .append(quoted ? "\"" + mask.get() + "\"" : mask.get());
        copied = valueEnd;
        from = valueEnd;
      } else {
        // A value that is not masked may itself hold pairs, as in a JSON message string.
        from = Math.max(valueStart, keys.start() + 1);
      }
    }
    return masked.append(text, copied, text.length()).toString();
  }

  /** Returns the value without its quotes. */
  private static String value(String text, int start, int end, boolean quoted) {
    return quoted
        ? text.substring(start + 1, Math.max(start + 1, end - 1))
        : text.substring(start, end);
  }

  /** Returns the index after the closing quote, or the end of the text when there is none. */
  private static int quotedEnd(String text, int from) {
    var i = from;
    while (i < text.length()) {
      var c = text.charAt(i);
      if (c == QUOTE) {
        return i + 1;
      }
      // A backslash escapes the character after it, which may be a quote.
      i += c == '\\' ? 2 : 1;
    }
    return text.length();
  }

  /** Returns the end of an unquoted value, without trailing spaces. */
  private static int plainEnd(String text, int from) {
    var nextKey = NEXT_KEY.matcher(text);
    var end = from;
    for (var i = from; i < text.length(); i++) {
      var c = text.charAt(i);
      if (VALUE_ENDS.indexOf(c) >= 0) {
        return end;
      }
      if (!Character.isWhitespace(c)) {
        end = i + 1;
      } else if (end == i && startsKey(nextKey, text, i)) {
        return end;
      }
    }
    return end;
  }

  /** Returns whether a key follows the spaces that start at the index. */
  private static boolean startsKey(Matcher nextKey, String text, int spaces) {
    var i = spaces;
    while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
      i++;
    }
    return nextKey.region(i, text.length()).lookingAt();
  }
}
