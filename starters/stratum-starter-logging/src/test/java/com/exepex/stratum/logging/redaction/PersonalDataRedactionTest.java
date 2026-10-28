package com.exepex.stratum.logging.redaction;

import static com.exepex.stratum.logging.testsupport.LoggingTestData.EMAIL;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.IBAN;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.PHONE;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.logging.testsupport.LoggingTestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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

    @Test
    @DisplayName("a name field is masked in full")
    void nameFieldIsMaskedInFull() {
      assertThat(redactor.redact("first_name", "Jane")).isEqualTo("****");
      assertThat(redactor.redact("dateOfBirth", "1990-01-01")).isEqualTo("****");
    }

    @Test
    @DisplayName("an email field keeps only its last four characters")
    void emailFieldKeepsLastFour() {
      assertThat(redactor.redact("email", "anything.com")).isEqualTo("****.com");
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
