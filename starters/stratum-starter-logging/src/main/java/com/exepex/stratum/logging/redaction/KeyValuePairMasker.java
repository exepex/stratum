package com.exepex.stratum.logging.redaction;

import com.exepex.stratum.logging.redaction.MaskedText.Replacement;
import java.util.ArrayList;
import java.util.Optional;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import lombok.Builder;
import lombok.RequiredArgsConstructor;

/**
 * Masks the value of each sensitive key in text, as in {@code password=x}, {@code "token":"x"},
 * {@code 'token': 'x'}, escaped JSON such as {@code \"token\":\"x\"}, {@code ?api_key=x&}, {@code
 * password%3Dx} or Lombok's {@code User(email=x, ...)}. A quoted value ends at its closing quote, a
 * JSON array or object at its closing bracket, and the value of a header that holds a list, such as
 * {@code Cookie: a=1; b=2}, at the end of the line. Any other value ends at the next key, at one of
 * {@code , ; & ) } ] "} or at a line break.
 */
@RequiredArgsConstructor
final class KeyValuePairMasker {

  // Only the key is matched with a regular expression. A value's end is found by scanning, and
  // only after a sensitive key, so a long or hostile text cannot make matching slow.
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
  String mask(String text) {
    var keys = KEY.matcher(text);
    var maskedValues = new ArrayList<Replacement>();
    var searchFrom = 0;
    // A loop, because the next search starts after a masked value, but inside a value that is
    // not masked, which may itself hold pairs, as in a JSON message string.
    while (keys.find(searchFrom)) {
      var key = keys.group(1);
      var maskedValue =
          fieldNames.isSensitive(key)
              ? maskedValueAt(text, key, keys.end())
              : Optional.<Replacement>empty();
      maskedValue.ifPresent(maskedValues::add);
      searchFrom = maskedValue.map(Replacement::end).orElse(keys.end());
    }
    return MaskedText.replace(text, maskedValues);
  }

  /** Returns the masked form of the sensitive key's value, or empty if the value is empty. */
  private Optional<Replacement> maskedValueAt(String text, String key, int start) {
    var value = valueAt(text, key, start);
    if (value.isEmpty()) {
      return Optional.empty();
    }
    var mask = fieldNames.mask(key, () -> value.content(text)).orElse(MaskedText.MASK);
    return Optional.of(
        Replacement.builder().start(value.start()).end(value.end()).text(value.wrap(mask)).build());
  }

  /** Finds the value that starts at the index. */
  private ValueSpan valueAt(String text, String key, int start) {
    if (text.startsWith(ESCAPED_QUOTE, start)) {
      // Inside escaped JSON, a quote in the value is written \\\" and the closing one \".
      var closing =
          closingQuote(
              text,
              start + ESCAPED_QUOTE.length(),
              '"',
              backslashes -> backslashes % ESCAPED_JSON_CYCLE == 1);
      return quotedValue(text, ESCAPED_QUOTE, start, closing);
    }
    var opening = start < text.length() ? text.charAt(start) : ' ';
    if (opening == '"' || opening == '\'') {
      var closing = closingQuote(text, start + 1, opening, backslashes -> backslashes % 2 == 0);
      return quotedValue(text, String.valueOf(opening), start, closing);
    }
    if (opening == '[' || opening == '{') {
      return unquotedValue(start, containerEnd(text, start));
    }
    return unquotedValue(
        start, fieldNames.isHeaderList(key) ? lineEnd(text, start) : plainEnd(text, start));
  }

  /** Returns a quoted value; one with no closing quote runs to the end of the text. */
  private ValueSpan quotedValue(String text, String quote, int start, int closingQuote) {
    var end = closingQuote < 0 ? text.length() : closingQuote + 1;
    var contentEnd = closingQuote < 0 ? text.length() : end - quote.length();
    return ValueSpan.builder().quote(quote).start(start).contentEnd(contentEnd).end(end).build();
  }

  /** Returns a value with no quotes. */
  private ValueSpan unquotedValue(int start, int end) {
    return ValueSpan.builder().quote("").start(start).contentEnd(end).end(end).build();
  }

  /**
   * Returns the end of a JSON array or object that starts at the position: just after its closing
   * bracket, or the end of the text if it has none. Brackets inside strings are skipped.
   */
  private int containerEnd(String text, int start) {
    var depth = 1;
    var inString = false;
    var position = start + 1;
    // A loop, because each character can change the depth or whether the scan is in a string.
    while (depth > 0 && position < text.length()) {
      var character = text.charAt(position);
      if (inString) {
        // A backslash skips the character after it, so an escaped quote never ends the string.
        position += character == '\\' ? 2 : 1;
        inString = character != '"';
      } else {
        depth += depthChange(character);
        inString = character == '"';
        position++;
      }
    }
    return depth == 0 ? position : text.length();
  }

  /** Returns how a character outside a string changes the number of open brackets. */
  private int depthChange(char character) {
    return switch (character) {
      case '[', '{' -> 1;
      case ']', '}' -> -1;
      default -> 0;
    };
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
                    && closes.test(
                        MaskedText.countTrailing(
                            text, from, position, character -> character == '\\')))
        .findFirst()
        .orElse(-1);
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
   * Where a value is in the text.
   *
   * @param quote the quote around it, or an empty string when it has none
   * @param start where it starts, with its opening quote
   * @param contentEnd where its content ends, before any closing quote
   * @param end where it ends, after any closing quote
   */
  @Builder
  private record ValueSpan(String quote, int start, int contentEnd, int end) {

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
}
