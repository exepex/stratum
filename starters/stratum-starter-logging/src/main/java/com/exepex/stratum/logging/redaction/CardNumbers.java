package com.exepex.stratum.logging.redaction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.validator.routines.CreditCardValidator;

/**
 * Masks card numbers in text, keeping the last four digits. A run of digit groups, such as {@code
 * 4111 1111 1111 1111 43}, can hold a card next to other numbers, so every run of whole groups with
 * 13 to 19 digits is checked, and the longest that passes the Luhn check is masked.
 */
final class CardNumbers implements UnaryOperator<String> {

  /** Digit groups separated by single spaces or dashes, with at least 13 digits in all. */
  private static final Pattern GROUPS = Pattern.compile("\\b\\d(?:[ -]?\\d){12,}+\\b");

  /** One group of digits. */
  private static final Pattern GROUP = Pattern.compile("\\d+");

  /** The fewest digits in a card number. */
  private static final int MIN_DIGITS = 13;

  /** The most digits in a card number. */
  private static final int MAX_DIGITS = 19;

  /** Checks a card number's length and Luhn check digit, for any card brand. */
  private static final CreditCardValidator CARD =
      CreditCardValidator.genericCreditCardValidator(MIN_DIGITS, MAX_DIGITS);

  /**
   * Masks every card number in the text.
   *
   * @param text the text to mask
   * @return the text with each card number masked
   */
  @Override
  public String apply(String text) {
    if (text.chars().noneMatch(Character::isDigit)) {
      return text;
    }
    return GROUPS.matcher(text).replaceAll(match -> Matcher.quoteReplacement(mask(match)));
  }

  /** Masks the cards in one run of digit groups and keeps everything else in it. */
  private static String mask(MatchResult run) {
    var text = run.group();
    var groups = GROUP.matcher(text).results().toList();
    var masked = new StringBuilder(text.length());
    var copied = 0;
    var first = 0;
    while (first < groups.size()) {
      var last = longestCard(text, groups, first);
      if (last < 0) {
        first++;
        continue;
      }
      var start = groups.get(first).start();
      var end = groups.get(last).end();
      masked
          .append(text, copied, start)
          .append(Masks.keepingLastFour(digits(text.substring(start, end))));
      copied = end;
      first = last + 1;
    }
    return masked.append(text, copied, text.length()).toString();
  }

  /** Returns the last group of the longest card that starts at the first group, or -1. */
  private static int longestCard(String text, List<MatchResult> groups, int first) {
    var ends = new ArrayList<Integer>();
    var count = 0;
    for (var last = first; last < groups.size() && count <= MAX_DIGITS; last++) {
      count += groups.get(last).end() - groups.get(last).start();
      if (count >= MIN_DIGITS && count <= MAX_DIGITS) {
        ends.add(last);
      }
    }
    for (var i = ends.size() - 1; i >= 0; i--) {
      var last = ends.get(i);
      if (CARD.isValid(digits(text.substring(groups.get(first).start(), groups.get(last).end())))) {
        return last;
      }
    }
    return -1;
  }

  /** Returns only the digits of the text. */
  private static String digits(String text) {
    return text.replaceAll("\\D", "");
  }
}
