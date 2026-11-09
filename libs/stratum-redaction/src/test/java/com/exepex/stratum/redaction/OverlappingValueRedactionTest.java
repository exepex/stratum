package com.exepex.stratum.redaction;

import static com.exepex.stratum.redaction.testsupport.RedactionTestData.maskUnnamedText;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Values that overlap or touch are each masked in full")
class OverlappingValueRedactionTest {

  @Test
  @DisplayName("digits inside an email address that pass the Luhn check do not leave it visible")
  void cardInsideEmailIsMaskedWithIt() {
    assertThat(maskUnnamedText("to jane_2026_10_29_07_39_22_123@example.com"))
        .isEqualTo("to ****.com");
    assertThat(maskUnnamedText("to jane.doe.4111111111111111@x.com")).isEqualTo("to ****.com");
  }

  @Test
  @DisplayName("an email address that starts with a card number keeps only its own end visible")
  void emailStartingWithCardKeepsItsOwnEnd() {
    assertThat(maskUnnamedText("4111111111111111.jane@example.com")).isEqualTo("****.com");
  }

  @Test
  @DisplayName("an IBAN next to a card number is masked, not cut into by the card")
  void ibanNextToCardIsMasked() {
    assertThat(maskUnnamedText("GB82 WEST 1234 5698 7654 32  4111-1111-1111-1111"))
        .isEqualTo("****5432  ****1111");
  }

  @Test
  @DisplayName("a card number next to a phone number is masked, and the phone number is kept")
  void cardNextToPhoneIsMasked() {
    assertThat(maskUnnamedText("+1 415 555 2671  4111 1111 1111 1111"))
        .isEqualTo("+1 415 555 2671  ****1111");
  }

  @Test
  @DisplayName("an email address right after a card number is masked")
  void emailAfterCardIsMasked() {
    assertThat(maskUnnamedText("user@4111-1111-1111-1111.jane_2026_10_29@example.com"))
        .isEqualTo("user@********.com");
  }
}
