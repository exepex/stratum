package com.exepex.stratum.redaction;

import static com.exepex.stratum.redaction.testsupport.RedactionTestData.maskByName;
import static com.exepex.stratum.redaction.testsupport.RedactionTestData.maskUnnamedText;
import static org.assertj.core.api.Assertions.assertThat;

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
      assertThat(maskByName(name, VALUE)).isEqualTo(MaskedText.MASK);
    }

    @ParameterizedTest
    @FieldSource("KEEPING_LAST_FOUR_NAMES")
    @DisplayName("keeps its last four characters")
    void wordKeepsLastFour(String name) {
      assertThat(maskByName(name, VALUE)).isEqualTo("****7890");
    }

    @ParameterizedTest
    @FieldSource("ORDINARY_NAMES")
    @DisplayName("is not found in a word that only contains its letters")
    void ordinaryNameIsKept(String name) {
      assertThat(maskByName(name, VALUE)).isEqualTo(VALUE);
    }

    @Test
    @DisplayName("a word masked in full wins over one that keeps its last four")
    void maskedInFullWins() {
      assertThat(maskByName("cardSecret", VALUE)).isEqualTo(MaskedText.MASK);
      assertThat(maskByName("emailToken", VALUE)).isEqualTo(MaskedText.MASK);
    }

    @Test
    @DisplayName("a long secret word is still found inside a word")
    void longSecretWordIsFoundInsideWord() {
      assertThat(maskByName("dbpassword", VALUE)).isEqualTo(MaskedText.MASK);
      assertThat(maskByName("JSESSIONID", VALUE)).isEqualTo(MaskedText.MASK);
    }

    @Test
    @DisplayName("a key in text is matched by its words too")
    void keyInTextIsMatchedByWords() {
      assertThat(maskUnnamedText("login dbPwd=abc userPin=1234 shipping=fast"))
          .isEqualTo("login dbPwd=**** userPin=**** shipping=fast");
    }
  }

  @Nested
  @DisplayName("a cloud provider's secret name")
  class CloudSecretName {

    /** Names under which Azure, AWS and Azure API Management send a signature or a key. */
    private static final List<String> CLOUD_SECRET_NAMES =
        List.of(
            "sig",
            "SIG",
            "signature",
            "X-Amz-Signature",
            "X-AMZ-SIGNATURE",
            "x-hub-signature-256",
            "subscription-key",
            "Ocp-Apim-Subscription-Key",
            "OCP-APIM-SUBSCRIPTION-KEY",
            "subscriptionKey",
            "AccountKey",
            "account_key");

    /** Names that hold the letters of a cloud secret name, or only part of it. */
    private static final List<String> LOOKALIKE_NAMES =
        List.of(
            "signal",
            "design",
            "assign",
            "signed",
            "isSigned",
            "subscription",
            "subscriptionId",
            "account",
            "accountId",
            "key",
            "sigCount",
            "signatureType");

    @ParameterizedTest
    @FieldSource("CLOUD_SECRET_NAMES")
    @DisplayName("is masked in full")
    void cloudNameIsMaskedInFull(String name) {
      assertThat(maskByName(name, VALUE)).isEqualTo(MaskedText.MASK);
    }

    @ParameterizedTest
    @FieldSource("LOOKALIKE_NAMES")
    @DisplayName("is not found in a name that only looks like it")
    void lookalikeNameIsKept(String name) {
      assertThat(maskByName(name, VALUE)).isEqualTo(VALUE);
    }

    @Test
    @DisplayName("is masked with a describing word that is not a listed one, such as Algorithm")
    void cloudNameWithOtherLastWordIsMasked() {
      assertThat(maskByName("signatureAlgorithm", VALUE)).isEqualTo(MaskedText.MASK);
      assertThat(maskByName("SignatureVersion", VALUE)).isEqualTo(MaskedText.MASK);
      assertThat(maskByName("accountKey", VALUE)).isEqualTo(MaskedText.MASK);
    }

    @Test
    @DisplayName("is masked in the query of a signed URL, and its other parameters are kept")
    void signedUrlQueryIsMasked() {
      assertThat(
              maskUnnamedText(
                  "https://acme.blob.core.windows.net/c/report.csv?sv=2024-11-04&sp=r"
                      + "&sig=c2lnbmF0dXJl%3D&se=2026-12-01"))
          .isEqualTo(
              "https://acme.blob.core.windows.net/c/report.csv?sv=2024-11-04&sp=r"
                  + "&sig=****&se=2026-12-01");
      assertThat(
              maskUnnamedText(
                  "https://s3.amazonaws.com/b/k?X-Amz-Expires=60&X-Amz-Signature=fe5f80f7&x=1"))
          .isEqualTo("https://s3.amazonaws.com/b/k?X-Amz-Expires=60&X-Amz-Signature=****&x=1");
      assertThat(maskUnnamedText("/rates?subscription-key=fake0key&signature=fake1sig"))
          .isEqualTo("/rates?subscription-key=****&signature=****");
    }

    @Test
    @DisplayName("is masked in a header line, a connection string and JSON text")
    void cloudNameInTextIsMasked() {
      assertThat(maskUnnamedText("Ocp-Apim-Subscription-Key: fake0key"))
          .isEqualTo("Ocp-Apim-Subscription-Key: ****");
      assertThat(
              maskUnnamedText(
                  "DefaultEndpointsProtocol=https;AccountName=acme;AccountKey=ZmFrZQ==;"
                      + "EndpointSuffix=core.windows.net"))
          .isEqualTo(
              "DefaultEndpointsProtocol=https;AccountName=acme;AccountKey=****;"
                  + "EndpointSuffix=core.windows.net");
      assertThat(maskUnnamedText("{\"signature\":\"fake1sig\",\"id\":7}"))
          .isEqualTo("{\"signature\":\"****\",\"id\":7}");
    }

    @Test
    @DisplayName("leaves the values of lookalike keys in text as they are")
    void lookalikeKeysInTextAreKept() {
      var text = "signal=on&design=flat&signed=true&subscription=gold&account=acme&key=k1";

      assertThat(maskUnnamedText(text)).isEqualTo(text);
    }
  }

  @Nested
  @DisplayName("a name in unusual case or written as one word")
  class UnusualCase {

    @Test
    @DisplayName("a list header in any case keeps every value masked to the end of the line")
    void listHeaderInAnyCaseIsMaskedToLineEnd() {
      assertThat(maskUnnamedText("cOoKiE: a=alpha; b=bravo")).isEqualTo("cOoKiE: ****");
      assertThat(maskUnnamedText("sEt-cOoKiE: a=alpha; b=bravo")).isEqualTo("sEt-cOoKiE: ****");
      assertThat(maskUnnamedText("setcookie: a=alpha; b=bravo")).isEqualTo("setcookie: ****");
      assertThat(maskUnnamedText("proxyauthorization: Basic a; b"))
          .isEqualTo("proxyauthorization: ****");
    }

    @Test
    @DisplayName("a sensitive name in mixed case is still masked")
    void nameInMixedCaseIsMasked() {
      assertThat(maskByName("dAtEoFbIrTh", VALUE)).isEqualTo(MaskedText.MASK);
      assertThat(maskByName("cVv", VALUE)).isEqualTo(MaskedText.MASK);
      assertThat(maskUnnamedText("dAtEoFbIrTh=1990-01-01")).isEqualTo("dAtEoFbIrTh=****");
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
      assertThat(maskByName(name, VALUE)).isEqualTo(VALUE);
    }

    @Test
    @DisplayName("is still masked when the describing word is not the last")
    void aboutWordInsideIsMasked() {
      assertThat(maskByName("tokenCountSecret", VALUE)).isEqualTo(MaskedText.MASK);
    }
  }

  @Nested
  @DisplayName("the service's own extra keys")
  class ExtraKeys {

    /** Names with the service's own {@code lockerCode}. */
    private final SensitiveFieldNames withLockerCode =
        new SensitiveFieldNames(List.of("lockerCode"));

    @Test
    @DisplayName("match the whole name only, without case, _ or -")
    void extraKeyMatchesWholeName() {
      assertThat(maskByName(withLockerCode, "locker_code", VALUE)).isEqualTo(MaskedText.MASK);
      assertThat(maskByName(withLockerCode, "LOCKER-CODE", VALUE)).isEqualTo(MaskedText.MASK);
      assertThat(maskByName(withLockerCode, "myLockerCode", VALUE)).isEqualTo(VALUE);
    }

    @Test
    @DisplayName("are masked even when their last word describes the value")
    void extraKeyIgnoresAboutWords() {
      var withVisitCount = new SensitiveFieldNames(List.of("visitCount"));

      assertThat(maskByName(withVisitCount, "visitCount", VALUE)).isEqualTo(MaskedText.MASK);
    }
  }
}
