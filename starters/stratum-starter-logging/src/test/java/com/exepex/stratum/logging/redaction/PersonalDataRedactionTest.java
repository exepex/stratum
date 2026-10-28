package com.exepex.stratum.logging.redaction;

import static com.exepex.stratum.logging.testsupport.LoggingTestData.EMAIL;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.IBAN;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.PHONE;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.logging.testsupport.LoggingTestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Personal data is masked")
class PersonalDataRedactionTest {

  private final Redactor redactor = Redactor.from(LoggingTestData.defaults().build());

  /** Masks a value that has no field name, as in a log message. */
  private String text(String value) {
    return (String) redactor.redact(null, value);
  }

  @Nested
  @DisplayName("by field name")
  class ByFieldName {

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          "first_name",
          "lastName",
          "middle_name",
          "fullName",
          "given_name",
          "surname",
          "username",
          "display_name",
          "dob",
          "dateOfBirth",
          "birth_date",
          "address",
          "street_address"
        })
    @DisplayName("a name, birth date or address field is masked in full")
    void personalFieldIsMaskedInFull(String name) {
      assertThat(redactor.redact(name, "Jane")).isEqualTo(Redactor.MASK);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {"email", "phone", "mobile", "telephone", "fax", "cell", "msisdn", "iban"})
    @DisplayName("an email, phone or IBAN field keeps only its last four characters")
    void identifierFieldKeepsLastFour(String name) {
      assertThat(redactor.redact(name, "anything.com")).isEqualTo("****.com");
    }
  }

  @Nested
  @DisplayName("in text")
  class InText {

    @Test
    @DisplayName("an email address keeps only its last four characters")
    void emailIsMasked() {
      assertThat(text("sent to " + EMAIL)).isEqualTo("sent to ****.com");
    }

    @Test
    @DisplayName("an international phone number keeps only its last four digits")
    void phoneIsMasked() {
      assertThat(text("call " + PHONE + " now")).isEqualTo("call ****0958 now");
    }

    @Test
    @DisplayName("a number without a country code is kept")
    void nationalNumberIsKept() {
      assertThat(text("retry 2079460958")).isEqualTo("retry 2079460958");
    }

    @Test
    @DisplayName("a valid IBAN keeps only its last four characters, and the next words stay")
    void ibanIsMasked() {
      assertThat(text("refund GB82 WEST 1234 5698 7654 32 done")).isEqualTo("refund ****5432 done");
      assertThat(text("iban " + IBAN)).isEqualTo("iban ****5432");
    }

    @Test
    @DisplayName("two grouped IBANs next to each other are both masked")
    void adjacentIbansAreMasked() {
      var grouped = "GB82 WEST 1234 5698 7654 32";

      assertThat(text("refund " + grouped + " " + grouped)).isEqualTo("refund ****5432 ****5432");
    }

    @Test
    @DisplayName("an IBAN right after an IBAN-shaped word is masked")
    void ibanAfterIbanShapedWordIsMasked() {
      assertThat(text("ref DE12 DE89370400440532013000")).isEqualTo("ref DE12 ****3000");
      assertThat(text("AB12CD DE89 3704 0044 0532 0130 00")).isEqualTo("AB12CD ****3000");
      assertThat(text("ref US12 GB82 WEST 1234 5698 7654 32 done"))
          .isEqualTo("ref US12 ****5432 done");
    }

    @Test
    @DisplayName("an IBAN-shaped code that fails its checksum is kept")
    void invalidIbanIsKept() {
      assertThat(text("code GB00WEST12345698765432")).isEqualTo("code GB00WEST12345698765432");
    }

    @Test
    @DisplayName("a full name in a Lombok toString is masked, up to the next key")
    void lombokToStringNameIsMasked() {
      assertThat(text("Customer(id=7, fullName=Jane Q Doe, tier=GOLD)"))
          .isEqualTo("Customer(id=7, fullName=****, tier=GOLD)");
    }

    @Test
    @DisplayName("an ordinary message and an ID are kept")
    void ordinaryTextIsKept() {
      var message = "order 8f14e45f-ceea-467f-a0f9-8e6b0a5e2c4d placed at 10:15, total=42";
      assertThat(text(message)).isEqualTo(message);
    }
  }
}
