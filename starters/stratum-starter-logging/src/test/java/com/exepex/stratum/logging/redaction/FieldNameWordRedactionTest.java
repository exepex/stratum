package com.exepex.stratum.logging.redaction;

import static com.exepex.stratum.logging.testsupport.LoggingTestData.DEFAULT_REDACTOR;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.redactUnnamedText;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.logging.testsupport.LoggingTestData;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

@DisplayName("Field and key names are matched by their words")
class FieldNameWordRedactionTest {

  /** A value that keeps its last four characters when masked that way. */
  private static final String VALUE = "value-7890";

  @Nested
  @DisplayName("splitting a name into words")
  class Splitting {

    @Test
    @DisplayName("a name splits at capitals, separators and digits, in lower case")
    void splitsIntoWords() {
      assertThat(FieldNameWords.wordsOf("dbPwd")).containsExactly("db", "pwd");
      assertThat(FieldNameWords.wordsOf("card_cvv")).containsExactly("card", "cvv");
      assertThat(FieldNameWords.wordsOf("X-API-Key")).containsExactly("x", "api", "key");
      assertThat(FieldNameWords.wordsOf("XMLHttpRequest"))
          .containsExactly("xml", "http", "request");
      assertThat(FieldNameWords.wordsOf("pin2.new")).containsExactly("pin", "2", "new");
      assertThat(FieldNameWords.wordsOf("JSESSIONID")).containsExactly("jsessionid");
    }

    @Test
    @DisplayName("a name with no letters or digits has no words")
    void emptyNameHasNoWords() {
      assertThat(FieldNameWords.wordsOf("")).isEmpty();
      assertThat(FieldNameWords.wordsOf("_-.")).isEmpty();
    }
  }

  @Nested
  @DisplayName("a sensitive word inside a longer name")
  class SensitiveWord {

    /** Names that hold a word masked in full. */
    private static final List<String> MASKED_IN_FULL_NAMES =
        List.of(
            "dbPwd",
            "userPin",
            "card_cvv",
            "otp_code",
            "accessKey",
            "encryption_key",
            "signingKey",
            "jwt",
            "userJwt",
            "XAPIKey",
            "dateOfBirth",
            "customer.first_name",
            "password.new");

    /** Names that hold a word whose value keeps its last four characters. */
    private static final List<String> KEEPING_LAST_FOUR_NAMES =
        List.of("accountNumber", "bank_account_number", "customerEmail", "home_phone", "cardNo");

    /** Names whose words only look like sensitive ones. */
    private static final List<String> ORDINARY_NAMES =
        List.of("shipping", "passenger", "bypass", "author", "spinner", "key", "orderId");

    @ParameterizedTest
    @FieldSource("MASKED_IN_FULL_NAMES")
    @DisplayName("is masked in full")
    void wordIsMaskedInFull(String name) {
      assertThat(DEFAULT_REDACTOR.redact(name, VALUE)).isEqualTo(MaskedText.MASK);
    }

    @ParameterizedTest
    @FieldSource("KEEPING_LAST_FOUR_NAMES")
    @DisplayName("keeps its last four characters")
    void wordKeepsLastFour(String name) {
      assertThat(DEFAULT_REDACTOR.redact(name, VALUE)).isEqualTo("****7890");
    }

    @ParameterizedTest
    @FieldSource("ORDINARY_NAMES")
    @DisplayName("is not found in a word that only contains its letters")
    void ordinaryNameIsKept(String name) {
      assertThat(DEFAULT_REDACTOR.redact(name, VALUE)).isEqualTo(VALUE);
    }

    @Test
    @DisplayName("a word masked in full wins over one that keeps its last four")
    void maskedInFullWins() {
      assertThat(DEFAULT_REDACTOR.redact("cardSecret", VALUE)).isEqualTo(MaskedText.MASK);
      assertThat(DEFAULT_REDACTOR.redact("emailToken", VALUE)).isEqualTo(MaskedText.MASK);
    }

    @Test
    @DisplayName("a long secret word is still found inside a word")
    void longSecretWordIsFoundInsideWord() {
      assertThat(DEFAULT_REDACTOR.redact("dbpassword", VALUE)).isEqualTo(MaskedText.MASK);
      assertThat(DEFAULT_REDACTOR.redact("JSESSIONID", VALUE)).isEqualTo(MaskedText.MASK);
    }

    @Test
    @DisplayName("a key in text is matched by its words too")
    void keyInTextIsMatchedByWords() {
      assertThat(redactUnnamedText("login dbPwd=abc userPin=1234 shipping=fast"))
          .isEqualTo("login dbPwd=**** userPin=**** shipping=fast");
    }
  }

  @Nested
  @DisplayName("a name whose last word describes the value")
  class AboutWord {

    /** Names that end in a word about the secret, not the secret itself. */
    private static final List<String> ABOUT_NAMES =
        List.of(
            "tokenCount",
            "token_type",
            "passwordLength",
            "pin_size",
            "passwordExpiry",
            "token_expires",
            "mfaOtpEnabled",
            "pinRequired",
            "emailConsent",
            "secretRotated");

    @ParameterizedTest
    @FieldSource("ABOUT_NAMES")
    @DisplayName("is not masked")
    void aboutNameIsKept(String name) {
      assertThat(DEFAULT_REDACTOR.redact(name, VALUE)).isEqualTo(VALUE);
    }

    @Test
    @DisplayName("is still masked when the describing word is not the last")
    void aboutWordInsideIsMasked() {
      assertThat(DEFAULT_REDACTOR.redact("tokenCountSecret", VALUE)).isEqualTo(MaskedText.MASK);
    }
  }

  @Nested
  @DisplayName("the service's own extra keys")
  class ExtraKeys {

    private final Redactor redactor =
        Redactor.from(LoggingTestData.withRedaction(List.of("lockerCode"), List.of()).build());

    @Test
    @DisplayName("match the whole name only, without case, _ or -")
    void extraKeyMatchesWholeName() {
      assertThat(redactor.redact("locker_code", VALUE)).isEqualTo(MaskedText.MASK);
      assertThat(redactor.redact("LOCKER-CODE", VALUE)).isEqualTo(MaskedText.MASK);
      assertThat(redactor.redact("myLockerCode", VALUE)).isEqualTo(VALUE);
    }

    @Test
    @DisplayName("are masked even when their last word describes the value")
    void extraKeyIgnoresAboutWords() {
      var countRedactor =
          Redactor.from(LoggingTestData.withRedaction(List.of("visitCount"), List.of()).build());

      assertThat(countRedactor.redact("visitCount", VALUE)).isEqualTo(MaskedText.MASK);
    }
  }
}
