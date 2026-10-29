package com.exepex.stratum.logging.redaction;

import static com.exepex.stratum.logging.testsupport.LoggingTestData.redactUnnamedText;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Values that overlap or touch are each masked in full")
class OverlappingValueRedactionTest {

  @Test
  @DisplayName("digits inside an email address that pass the Luhn check do not leave it visible")
  void cardInsideEmailIsMaskedWithIt() {
    assertThat(redactUnnamedText("to jane_2026_10_29_07_39_22_123@example.com"))
        .isEqualTo("to ****.com");
    assertThat(redactUnnamedText("to jane.doe.4111111111111111@x.com")).isEqualTo("to ****.com");
  }

  @Test
  @DisplayName("an email address that starts with a card number keeps only its own end visible")
  void emailStartingWithCardKeepsItsOwnEnd() {
    assertThat(redactUnnamedText("4111111111111111.jane@example.com")).isEqualTo("****.com");
  }

  @Test
  @DisplayName("a social security number that a card number runs into is still masked")
  void ssnAfterCardIsMasked() {
    assertThat(redactUnnamedText("10 123-45-678900004111.123-45-6789"))
        .isEqualTo("****4111.****6789");
  }

  @Test
  @DisplayName("an IBAN next to a card number is masked, not cut into by the card")
  void ibanNextToCardIsMasked() {
    assertThat(redactUnnamedText("GB82 WEST 1234 5698 7654 32  4111-1111-1111-1111"))
        .isEqualTo("****5432  ****1111");
  }

  @Test
  @DisplayName("a phone number next to a card number is masked")
  void phoneNextToCardIsMasked() {
    assertThat(redactUnnamedText("+1 415 555 2671  4111 1111 1111 1111"))
        .isEqualTo("****2671  ****1111");
  }

  @Test
  @DisplayName("an email address right after a card number is masked")
  void emailAfterCardIsMasked() {
    assertThat(redactUnnamedText("user@4111-1111-1111-1111.jane_2026_10_29@example.com"))
        .isEqualTo("user@********.com");
  }
}
