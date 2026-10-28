package com.exepex.stratum.logging.redaction;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.apache.commons.validator.routines.CreditCardValidator;

/**
 * Masks card numbers in text, keeping the last four digits. A run of digit groups, such as {@code
 * 4111 1111 1111 1111 43}, can hold a card next to other numbers, so every run of whole groups with
 * 13 to 19 digits is checked with Apache Commons Validator, and the longest valid one is masked.
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
    return text.chars().anyMatch(Character::isDigit)
        ? GROUPS.matcher(text).replaceAll(run -> Matcher.quoteReplacement(maskCards(run.group())))
        : text;
  }

  /** Masks the first card in a run of digit groups, then the cards in the rest of the run. */
  private static String maskCards(String run) {
    var groups = GROUP.matcher(run).results().toList();
    return IntStream.range(0, groups.size())
        .mapToObj(first -> longestCard(run, groups, first))
        .flatMap(Optional::stream)
        .findFirst()
        .map(
            card ->
                run.substring(0, card.start())
                    + Masks.keepingLastFour(digits(run, card))
                    + maskCards(run.substring(card.end())))
        .orElse(run);
  }

  /** Returns the longest valid card that starts at the first group, if there is one. */
  private static Optional<Span> longestCard(String run, List<MatchResult> groups, int first) {
    var start = groups.get(first).start();
    return IntStream.range(first, groups.size())
        .mapToObj(last -> new Span(start, groups.get(last).end()))
        .takeWhile(candidate -> digits(run, candidate).length() <= MAX_DIGITS)
        .filter(candidate -> CARD.isValid(digits(run, candidate)))
        .reduce((shorter, longer) -> longer);
  }

  /** Returns the digits of a candidate, without its spaces or dashes. */
  private static String digits(String run, Span candidate) {
    return run.substring(candidate.start(), candidate.end()).replaceAll("\\D", "");
  }

  /**
   * Where a possible card is in a run of digit groups.
   *
   * @param start where its first group starts
   * @param end where its last group ends
   */
  private record Span(int start, int end) {}
}
