package com.exepex.stratum.logging.redaction;

import java.util.ArrayList;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * Splits a field or key name into lower-case words, so that a name is matched however it is
 * written: {@code dbPwd}, {@code card_cvv}, {@code X-API-Key} and {@code
 * spring.datasource.password} all show their words.
 */
@UtilityClass
class FieldNameWords {

  /**
   * Returns the words of a name. A word ends at any character that is not a letter or digit, where
   * a lower-case letter meets an upper-case one, where a run of capitals meets a capitalized word,
   * as in {@code XMLHttp}, and where letters meet digits.
   *
   * @param name the field or key name
   * @return its words in lower case, in order; empty when it has none
   */
  static List<String> wordsOf(String name) {
    var words = new ArrayList<String>();
    var word = new StringBuilder();
    // A loop, because whether a character starts a new word depends on the characters around it.
    for (var position = 0; position < name.length(); position++) {
      var character = name.charAt(position);
      if (!Character.isLetterOrDigit(character)) {
        endWord(words, word);
      } else {
        if (!word.isEmpty() && startsWord(name, position)) {
          endWord(words, word);
        }
        word.append(Character.toLowerCase(character));
      }
    }
    endWord(words, word);
    return List.copyOf(words);
  }

  /** Returns whether the letter or digit at the position, inside a word, starts a new one. */
  private static boolean startsWord(String name, int position) {
    var previous = name.charAt(position - 1);
    var current = name.charAt(position);
    var lowerToUpper = Character.isLowerCase(previous) && Character.isUpperCase(current);
    var capitalsToWord =
        Character.isUpperCase(previous)
            && Character.isUpperCase(current)
            && position + 1 < name.length()
            && Character.isLowerCase(name.charAt(position + 1));
    return lowerToUpper
        || capitalsToWord
        || Character.isDigit(previous) != Character.isDigit(current);
  }

  /** Adds the word being built, if any, and starts the next one. */
  private static void endWord(List<String> words, StringBuilder word) {
    if (!word.isEmpty()) {
      words.add(word.toString());
      word.setLength(0);
    }
  }
}
