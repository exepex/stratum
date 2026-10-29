package com.exepex.stratum.logging.redaction;

import static com.exepex.stratum.logging.testsupport.LoggingTestData.CARD;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.redactUnnamedText;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Card numbers in text keep only their last four digits")
class CardNumberRedactionTest {

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
  @ValueSource(
      strings = {
        "paid with 4111111111111111 43",
        "paid with 4111 1111 1111 1111 43",
        "43 4111111111111111 paid",
        "order123 4111111111111111 paid",
        "order123 4111 1111 1111 1111 paid",
        "4111-1111-1111-1111 7 and 5500 0000 0000 0004"
      })
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
  @ValueSource(
      strings = {
        "4111.1111.1111.1111",
        "4111/1111/1111/1111",
        "4111_1111_1111_1111",
        "4111  1111  1111  1111",
        "4111 1111-1111.1111"
      })
  @DisplayName("a card split by dots, slashes, underscores or two spaces is masked")
  void cardWithOtherSeparatorsIsMasked(String card) {
    assertThat(redactUnnamedText("card " + card + " paid")).isEqualTo("card ****1111 paid");
  }

  @Test
  @DisplayName("dotted digits that fail the Luhn check, such as a timestamp, are kept")
  void dottedDigitsFailingLuhnAreKept() {
    assertThat(redactUnnamedText("at 2026.10.29.07.39.22.124"))
        .isEqualTo("at 2026.10.29.07.39.22.124");
  }
}
