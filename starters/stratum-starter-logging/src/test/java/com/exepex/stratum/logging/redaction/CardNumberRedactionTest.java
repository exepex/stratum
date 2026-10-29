package com.exepex.stratum.logging.redaction;

import static com.exepex.stratum.logging.testsupport.LoggingTestData.CARD;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.redactUnnamedText;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;

@DisplayName("Card numbers in text keep only their last four digits")
class CardNumberRedactionTest {

  /** Lines with the test card next to another number, each of which must still have it masked. */
  private static final List<String> CARDS_NEXT_TO_OTHER_NUMBERS =
      List.of(
          "paid with 4111111111111111 43",
          "paid with 4111 1111 1111 1111 43",
          "43 4111111111111111 paid",
          "order123 4111111111111111 paid",
          "order123 4111 1111 1111 1111 paid",
          "4111-1111-1111-1111 7 and 5500 0000 0000 0004");

  /** The test card with its groups split by each other separator. */
  private static final List<String> CARDS_WITH_OTHER_SEPARATORS =
      List.of(
          "4111.1111.1111.1111",
          "4111/1111/1111/1111",
          "4111_1111_1111_1111",
          "4111  1111  1111  1111");

  /** American Express and 19-digit cards in their own layouts, split by dots. */
  private static final List<Arguments> OTHER_CARD_LAYOUTS_WITH_DOTS =
      List.of(
          Arguments.of("3782.822463.10005", "****0005"),
          Arguments.of("4402.6140.1419.3141.705", "****1705"),
          Arguments.of("4111.1111.1111.1111.003", "****1003"));

  /**
   * Digits that pass the Luhn check across a dot, underscore or mixed separators, but are not in a
   * card's layout: timestamps, file names, decimal numbers and groups split by more than one kind
   * of separator.
   */
  private static final List<String> LUHN_PASSING_NUMBERS_OUTSIDE_CARD_LAYOUTS =
      List.of(
          "at 2026.10.01.15.16.35.239",
          "at 2026.12.28.10.34.57.585",
          "export_20260604_010831_222.csv",
          "path /2026/10/09/23/22/50/707",
          "price 798.2872025584391",
          "card 4111 1111-1111.1111 paid");

  @Test
  @DisplayName("a card number that passes the Luhn check keeps only its last four digits")
  void validCardIsMasked() {
    assertThat(redactUnnamedText("paid with 4111 1111 1111 1111 today"))
        .isEqualTo("paid with ****1111 today");
    assertThat(redactUnnamedText("card " + CARD)).isEqualTo("card ****1111");
    assertThat(redactUnnamedText("card 4402 6140 1419 3141 705")).isEqualTo("card ****1705");
    assertThat(redactUnnamedText("card 4111 1111 1111 1111 003")).isEqualTo("card ****1003");
    assertThat(redactUnnamedText("0 4111 1111 1111 1111")).isEqualTo("0 ****1111");
  }

  @ParameterizedTest
  @FieldSource("CARDS_NEXT_TO_OTHER_NUMBERS")
  @DisplayName("a card next to another number is still found and masked")
  void cardNextToNumberIsMasked(String line) {
    assertThat(redactUnnamedText(line))
        .doesNotContain("4111111111111111")
        .doesNotContain("4111 1111 1111 1111")
        .doesNotContain("4111-1111-1111-1111")
        .contains("****1111");
  }

  @Test
  @DisplayName("every card in a run of digit groups is masked, not only the first")
  void everyCardInRunIsMasked() {
    assertThat(redactUnnamedText("cards 4111111111111111 5500000000000004 done"))
        .isEqualTo("cards ****1111 ****0004 done");
    assertThat(redactUnnamedText("cards 4111 1111 1111 1111 4111 1111 1111 1111 done"))
        .doesNotContain("4111 1111 1111 1111");
  }

  @Test
  @DisplayName("a card that overlaps a longer valid number before it is masked too")
  void overlappingCardIsMasked() {
    assertThat(redactUnnamedText("cards 3782 822463 10005 4242 4242 4242 4242 done"))
        .isEqualTo("cards ****4242 done");
  }

  @Test
  @DisplayName("a long number that fails the Luhn check is kept")
  void numberFailingLuhnIsKept() {
    assertThat(redactUnnamedText("order 4111111111111112")).isEqualTo("order 4111111111111112");
  }

  @ParameterizedTest
  @FieldSource("CARDS_WITH_OTHER_SEPARATORS")
  @DisplayName("a card split by dots, slashes, underscores or two spaces is masked")
  void cardWithOtherSeparatorsIsMasked(String card) {
    assertThat(redactUnnamedText("card " + card + " paid")).isEqualTo("card ****1111 paid");
  }

  @ParameterizedTest
  @FieldSource("OTHER_CARD_LAYOUTS_WITH_DOTS")
  @DisplayName("an American Express or 19-digit card split by dots in its layout is masked")
  void otherCardLayoutWithDotsIsMasked(String card, String maskedCard) {
    assertThat(redactUnnamedText(card)).isEqualTo(maskedCard);
  }

  @ParameterizedTest
  @FieldSource("LUHN_PASSING_NUMBERS_OUTSIDE_CARD_LAYOUTS")
  @DisplayName("digits split by dots or underscores outside a card's layout are kept")
  void numberOutsideCardLayoutIsKept(String line) {
    assertThat(redactUnnamedText(line)).isEqualTo(line);
  }

  @Test
  @DisplayName("dotted digits that fail the Luhn check, such as a timestamp, are kept")
  void dottedDigitsFailingLuhnAreKept() {
    assertThat(redactUnnamedText("at 2026.10.29.07.39.22.124"))
        .isEqualTo("at 2026.10.29.07.39.22.124");
  }
}
