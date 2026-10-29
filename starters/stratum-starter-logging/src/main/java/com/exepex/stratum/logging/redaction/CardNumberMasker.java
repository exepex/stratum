package com.exepex.stratum.logging.redaction;

import com.exepex.stratum.logging.redaction.MaskedText.Replacement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.experimental.UtilityClass;
import org.apache.commons.validator.routines.checkdigit.LuhnCheckDigit;

/**
 * Masks payment card numbers in text, keeping their last four digits: digit groups that hold 13 to
 * 19 digits and pass the Luhn check.
 */
@UtilityClass
class CardNumberMasker {

  // Possessive, and needing nothing after the run, so a failed start scans at most 13 digits and
  // matching stays linear. It matches right after a word too, as in order123.
  /** Digit groups separated by single spaces or dashes, with at least 13 digits in all. */
  private static final Pattern CARD_GROUPS = Pattern.compile("\\d(?:[ -]?\\d){12,}+");

  /** One group of digits. */
  private static final Pattern DIGIT_GROUP = Pattern.compile("\\d+");

  /** The fewest digits in a card number. */
  private static final int MIN_CARD_DIGITS = 13;

  /** The most digits in a card number. */
  private static final int MAX_CARD_DIGITS = 19;

  /**
   * Masks every card number in the text.
   *
   * @param text the text to mask
   * @return the text with each card number masked
   */
  static String mask(String text) {
    return CARD_GROUPS
        .matcher(text)
        .replaceAll(digitGroupRun -> Matcher.quoteReplacement(maskRun(digitGroupRun.group())));
  }

  /**
   * Masks the cards in one run of digit groups. A run such as {@code 4111 1111 1111 1111 43} can
   * hold a card next to other numbers, so the longest valid card that starts at each group is
   * looked for, and the search goes on after the card it finds.
   */
  private static String maskRun(String digitGroupRun) {
    var groups = DIGIT_GROUP.matcher(digitGroupRun).results().toList();
    var maskedCards = new ArrayList<Replacement>();
    var nextCard = longestCardsStartingIn(digitGroupRun, groups, 0, groups.size()).findFirst();
    // A loop, because each search starts after the card that the previous one found.
    while (nextCard.isPresent()) {
      var card = withOverlaps(digitGroupRun, groups, nextCard.get());
      maskedCards.add(
          Replacement.builder()
              .start(card.start())
              .end(card.end())
              .text(MaskedText.keepingLastFour(card.digits()))
              .build());
      nextCard =
          longestCardsStartingIn(digitGroupRun, groups, card.lastGroup() + 1, groups.size())
              .findFirst();
    }
    return MaskedText.replace(digitGroupRun, maskedCards);
  }

  /**
   * Extends a card over each valid card that starts inside it and ends after it. Otherwise the last
   * four digits it keeps could show the start of the next card, and the rest of that card would
   * stay visible.
   */
  private static CardSpan withOverlaps(
      String digitGroupRun, List<MatchResult> groups, CardSpan card) {
    var extendedCard = card;
    var overlappingCard = overlappingCard(digitGroupRun, groups, extendedCard);
    // A loop, because each extension can reach one more overlapping card.
    while (overlappingCard.isPresent()) {
      extendedCard = overlappingCard.get();
      overlappingCard = overlappingCard(digitGroupRun, groups, extendedCard);
    }
    return extendedCard;
  }

  /** Returns the card extended over the first valid card that starts inside it and ends after. */
  private static Optional<CardSpan> overlappingCard(
      String digitGroupRun, List<MatchResult> groups, CardSpan card) {
    return longestCardsStartingIn(
            digitGroupRun, groups, card.firstGroup() + 1, card.lastGroup() + 1)
        .filter(otherCard -> otherCard.lastGroup() > card.lastGroup())
        .findFirst()
        .map(otherCard -> otherCard.toBuilder().start(card.start()).build());
  }

  /**
   * Returns, in order, the longest valid card that starts at each group from {@code fromGroup} up
   * to {@code toGroupExclusive}. No card number starts with 0.
   */
  private static Stream<CardSpan> longestCardsStartingIn(
      String digitGroupRun, List<MatchResult> groups, int fromGroup, int toGroupExclusive) {
    return IntStream.range(fromGroup, toGroupExclusive)
        .filter(firstGroup -> digitGroupRun.charAt(groups.get(firstGroup).start()) != '0')
        .mapToObj(firstGroup -> longestCard(digitGroupRun, groups, firstGroup))
        .flatMap(Optional::stream);
  }

  /** Returns the longest valid card that starts at the group, if there is one. */
  private static Optional<CardSpan> longestCard(
      String digitGroupRun, List<MatchResult> groups, int firstGroup) {
    var validCards =
        IntStream.range(firstGroup, groups.size())
            .takeWhile(lastGroup -> digitCount(groups, firstGroup, lastGroup) <= MAX_CARD_DIGITS)
            .filter(lastGroup -> digitCount(groups, firstGroup, lastGroup) >= MIN_CARD_DIGITS)
            .mapToObj(
                lastGroup ->
                    CardSpan.builder()
                        .start(groups.get(firstGroup).start())
                        .end(groups.get(lastGroup).end())
                        .firstGroup(firstGroup)
                        .lastGroup(lastGroup)
                        .digits(digitsOf(digitGroupRun, groups, firstGroup, lastGroup))
                        .build())
            .filter(candidate -> LuhnCheckDigit.LUHN_CHECK_DIGIT.isValid(candidate.digits()))
            .toList();
    return validCards.isEmpty() ? Optional.empty() : Optional.of(validCards.getLast());
  }

  /** Returns how many digits the groups from first to last hold. */
  private static int digitCount(List<MatchResult> groups, int firstGroup, int lastGroup) {
    // Groups are one separator apart, so the separators between them number lastGroup - firstGroup.
    var span = groups.get(lastGroup).end() - groups.get(firstGroup).start();
    return span - (lastGroup - firstGroup);
  }

  /** Returns the digits of the groups from first to last, without spaces or dashes. */
  private static String digitsOf(
      String digitGroupRun, List<MatchResult> groups, int firstGroup, int lastGroup) {
    return digitGroupRun
        .substring(groups.get(firstGroup).start(), groups.get(lastGroup).end())
        .replace(" ", "")
        .replace("-", "");
  }

  /**
   * Where a possible card is in a run of digit groups.
   *
   * @param start where its first group starts
   * @param end where its last group ends
   * @param firstGroup the position in the run of the first group of its last valid card
   * @param lastGroup the position of its last group in the run
   * @param digits the digits of its last valid card, without spaces or dashes
   */
  @Builder(toBuilder = true)
  private record CardSpan(int start, int end, int firstGroup, int lastGroup, String digits) {}
}
