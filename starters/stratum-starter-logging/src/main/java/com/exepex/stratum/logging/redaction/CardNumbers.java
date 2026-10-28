package com.exepex.stratum.logging.redaction;

import com.exepex.stratum.logging.redaction.ContentRules.Replacement;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.experimental.UtilityClass;
import org.apache.commons.validator.routines.checkdigit.LuhnCheckDigit;

/**
 * Finds and masks payment card numbers in text: digit groups that hold 13 to 19 digits and pass the
 * Luhn check.
 */
@UtilityClass
class CardNumbers {

  /**
   * Digit groups separated by single spaces or dashes, with at least 13 digits in all, wherever
   * they start, even right after a word such as {@code order123}. The run is matched possessively
   * and needs nothing after it, so a failed start scans at most 13 digits and matching stays
   * linear.
   */
  static final Pattern CARD_GROUPS = Pattern.compile("\\d(?:[ -]?\\d){12,}+");

  /** One group of digits. */
  private static final Pattern DIGIT_GROUP = Pattern.compile("\\d+");

  /** The fewest digits in a card number. */
  private static final int MIN_CARD_DIGITS = 13;

  /** The most digits in a card number. */
  private static final int MAX_CARD_DIGITS = 19;

  /**
   * Masks the card numbers in a run of digit groups, keeping the last four digits. A run such as
   * {@code 4111 1111 1111 1111 43} can hold a card next to other numbers, so the longest valid card
   * that starts at each group is looked for, and the search goes on after the card it finds. A
   * valid card that starts inside it and ends after it is masked with it.
   *
   * @param run a run of digit groups that {@link #CARD_GROUPS} found
   * @return the run with each card masked
   */
  static String mask(String run) {
    var digitGroups = DigitGroups.of(run);
    var cards =
        Stream.iterate(
                digitGroups.nextCard(0),
                Optional::isPresent,
                previousCard -> digitGroups.nextCard(previousCard.orElseThrow().lastGroup() + 1))
            .map(Optional::orElseThrow)
            .map(
                card ->
                    Replacement.builder()
                        .start(card.start())
                        .end(card.end())
                        .text(Redactor.keepingLastFour(card.digits()))
                        .build())
            .toList();
    return ContentRules.replace(run, cards);
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

  /**
   * The digit groups of a run and all their digits joined, so that each possible card's digits are
   * cut from one string, not joined again.
   *
   * @param groups the digit groups, in order
   * @param digits all the digits of the run
   * @param offsets where each group's digits start in {@code digits}, then the total length
   */
  @Builder
  private record DigitGroups(List<MatchResult> groups, String digits, List<Integer> offsets) {

    /** Finds the digit groups of a run. */
    private static DigitGroups of(String run) {
      var groups = DIGIT_GROUP.matcher(run).results().toList();
      var offsets =
          IntStream.concat(
                  IntStream.of(0), groups.stream().mapToInt(group -> group.end() - group.start()))
              .toArray();
      Arrays.parallelPrefix(offsets, Integer::sum);
      return DigitGroups.builder()
          .groups(groups)
          .digits(groups.stream().map(MatchResult::group).collect(Collectors.joining()))
          .offsets(Arrays.stream(offsets).boxed().toList())
          .build();
    }

    /** Returns the first valid card at or after the group, with the cards that overlap it. */
    private Optional<CardSpan> nextCard(int fromGroup) {
      return firstCard(fromGroup, groups.size() - 1).map(this::withOverlaps);
    }

    /**
     * Returns the first valid card that starts in the given groups. No card number starts with 0.
     */
    private Optional<CardSpan> firstCard(int fromGroup, int toGroup) {
      return IntStream.rangeClosed(fromGroup, toGroup)
          .filter(firstGroup -> digits.charAt(offsets.get(firstGroup)) != '0')
          .mapToObj(this::longestCard)
          .flatMap(Optional::stream)
          .findFirst();
    }

    /**
     * Extends a card over each valid card that starts inside it and ends after it. Otherwise the
     * last four digits it keeps could show the start of the next card, and the rest of that card
     * would stay visible.
     */
    private CardSpan withOverlaps(CardSpan card) {
      return Stream.iterate(
              Optional.of(card), Optional::isPresent, span -> overlapping(span.orElseThrow()))
          .map(Optional::orElseThrow)
          .reduce((shorter, longer) -> longer)
          .orElseThrow();
    }

    /** Returns the card extended over the first valid card that starts inside it and ends after. */
    private Optional<CardSpan> overlapping(CardSpan card) {
      return IntStream.rangeClosed(card.firstGroup() + 1, card.lastGroup())
          .filter(firstGroup -> digits.charAt(offsets.get(firstGroup)) != '0')
          .mapToObj(this::longestCard)
          .flatMap(Optional::stream)
          .filter(other -> other.lastGroup() > card.lastGroup())
          .findFirst()
          .map(other -> other.toBuilder().start(card.start()).build());
    }

    /** Returns the longest valid card that starts at the group, if there is one. */
    private Optional<CardSpan> longestCard(int firstGroup) {
      var start = groups.get(firstGroup).start();
      return IntStream.range(firstGroup, groups.size())
          .takeWhile(lastGroup -> digitCount(firstGroup, lastGroup) <= MAX_CARD_DIGITS)
          .filter(lastGroup -> digitCount(firstGroup, lastGroup) >= MIN_CARD_DIGITS)
          .mapToObj(
              lastGroup ->
                  CardSpan.builder()
                      .start(start)
                      .end(groups.get(lastGroup).end())
                      .firstGroup(firstGroup)
                      .lastGroup(lastGroup)
                      .digits(digits.substring(offsets.get(firstGroup), offsets.get(lastGroup + 1)))
                      .build())
          .filter(candidate -> LuhnCheckDigit.LUHN_CHECK_DIGIT.isValid(candidate.digits()))
          .reduce((shorter, longer) -> longer);
    }

    /** Returns how many digits the groups from first to last hold. */
    private int digitCount(int firstGroup, int lastGroup) {
      return offsets.get(lastGroup + 1) - offsets.get(firstGroup);
    }
  }
}
