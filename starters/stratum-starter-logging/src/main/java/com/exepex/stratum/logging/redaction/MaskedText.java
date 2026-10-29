package com.exepex.stratum.logging.redaction;

import java.util.List;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;
import lombok.Builder;
import lombok.experimental.UtilityClass;

/** The mask, and the text editing that every redaction rule shares. */
@UtilityClass
class MaskedText {

  /** Replaces a masked value, or the part of it that is hidden. */
  static final String MASK = "****";

  /** How many characters an identifier such as a card number keeps visible at its end. */
  private static final int VISIBLE_END_LENGTH = 4;

  /**
   * Masks a value but keeps its last four characters, such as {@code ****1111} for a card; a value
   * of four characters or fewer is masked in full.
   *
   * @param value the value to mask
   * @return the masked value
   */
  static String keepingLastFour(String value) {
    return value.length() <= VISIBLE_END_LENGTH
        ? MASK
        : MASK + value.substring(value.length() - VISIBLE_END_LENGTH);
  }

  /**
   * Returns the text with each replacement written in place of the part it covers.
   *
   * @param text the text
   * @param replacements the parts to replace, in order and not overlapping
   * @return the text with the parts replaced
   */
  static String replace(String text, List<Replacement> replacements) {
    var replacedText = new StringBuilder(text.length());
    var copiedUpTo = 0;
    // A loop, because each copied part starts where the previous replacement ended.
    for (var replacement : replacements) {
      replacedText.append(text, copiedUpTo, replacement.start()).append(replacement.text());
      copiedUpTo = replacement.end();
    }
    return replacedText.append(text, copiedUpTo, text.length()).toString();
  }

  /**
   * Returns how many characters just before {@code end} pass the test, counting back no further
   * than {@code from}.
   *
   * @param text the text
   * @param from the first position that may be counted
   * @param end the position just after the last character to look at
   * @param test the test each counted character passes
   * @return the number of characters
   */
  static int countTrailing(String text, int from, int end, IntPredicate test) {
    return (int)
        IntStream.iterate(
                end - 1,
                position -> position >= from && test.test(text.charAt(position)),
                position -> position - 1)
            .count();
  }

  /**
   * A part of a text and what is written in its place.
   *
   * @param start where the part starts
   * @param end where the part ends
   * @param text what is written instead
   */
  @Builder
  record Replacement(int start, int end, String text) {}
}
