package com.exepex.stratum.logging.redaction;

import static com.exepex.stratum.logging.testsupport.LoggingTestData.CARD;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.JWT;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.PASSWORD;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.SSN;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.logging.testsupport.LoggingTestData;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Secrets, cards and national IDs are always masked")
class SecretRedactionTest {

  private final Redactor redactor = Redactor.from(LoggingTestData.defaults().build());

  /** Masks a value that has no field name, as in a log message. */
  private String text(String value) {
    return (String) redactor.redact(null, value);
  }

  @Nested
  @DisplayName("by field name")
  class ByFieldName {

    @ParameterizedTest
    @ValueSource(
        strings = {
          "pwd",
          "auth",
          "pin",
          "otp",
          "cvv",
          "cvc",
          "password",
          "passwd",
          "passphrase",
          "secret",
          "client_secret",
          "token",
          "access_token",
          "refresh_token",
          "id_token",
          "X-Auth-Token",
          "X-CSRF-Token",
          "apiKey",
          "X-Api-Key",
          "credential",
          "credentials",
          "private_key",
          "session_id",
          "JSESSIONID",
          "cookie",
          "set-cookie",
          "Authorization",
          "Proxy-Authorization",
          "spring.datasource.password",
          "dbPassword",
          "userPassword",
          "newPassword",
          "secretKey",
          "apiToken",
          "authToken",
          "aws_secret_access_key"
        })
    @DisplayName("a secret field, or one whose name holds a secret word, is masked in full")
    void secretFieldIsMaskedInFull(String name) {
      assertThat(redactor.redact(name, "plain value")).isEqualTo(Masks.MASK);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "card",
          "cardNumber",
          "credit_card",
          "pan",
          "ssn",
          "national_id",
          "tax_id",
          "passport",
          "passport_number"
        })
    @DisplayName("a card or national ID field keeps only its last four characters")
    void cardFieldKeepsLastFour(String name) {
      assertThat(redactor.redact(name, "any-card-1234")).isEqualTo("****1234");
    }

    @Test
    @DisplayName("a value that is not text, such as a numeric PIN, is masked by its name")
    void nonTextValueIsMaskedByName() {
      assertThat(redactor.redact("pin", Integer.valueOf("1234"))).isEqualTo(Masks.MASK);
    }

    @Test
    @DisplayName("an ordinary field and value are left as they are")
    void ordinaryFieldIsKept() {
      var orderId = Long.valueOf("42");
      assertThat(redactor.redact("orderId", orderId)).isEqualTo(orderId);
      assertThat(redactor.redact("name", "order-service")).isEqualTo("order-service");
    }
  }

  @Nested
  @DisplayName("in text")
  class InText {

    @Test
    @DisplayName("a JSON Web Token is masked")
    void jwtIsMasked() {
      assertThat(text("token " + JWT + " used")).isEqualTo("token **** used");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer", "Basic", "Digest", "Negotiate"})
    @DisplayName("credentials after an authorization scheme are masked")
    void authorizationSchemeIsMasked(String scheme) {
      assertThat(text("sent " + scheme + " dXNlcjpwYXNz to x"))
          .isEqualTo("sent " + scheme + " **** to x");
    }

    @Test
    @DisplayName("an Authorization header written as text is masked")
    void authorizationHeaderIsMasked() {
      assertThat(text("Authorization: Bearer abc.def.ghi")).doesNotContain("abc.def.ghi");
    }

    @Test
    @DisplayName("a Cookie header is masked to the end of the line, with every cookie in it")
    void wholeCookieHeaderIsMasked() {
      assertThat(text("Cookie: sid=fake-session; opaque=fake-cookie\nnext line"))
          .isEqualTo("Cookie: ****\nnext line");
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "dbPassword=" + PASSWORD,
          "{\\\"password\\\":\\\"" + PASSWORD + "\\\"}",
          "{'password': '" + PASSWORD + "'}",
          "GET /login?password%3D" + PASSWORD,
          "X-CSRF-Token: " + PASSWORD,
          "Customer[name=Jane, dbPassword=" + PASSWORD + "]"
        })
    @DisplayName(
        "a secret is masked in escaped JSON, single quotes, URL encoding and compound keys")
    void secretInOtherFormsIsMasked(String line) {
      assertThat(text(line)).doesNotContain(PASSWORD).contains(Masks.MASK);
    }

    @Test
    @DisplayName("a JSON Web Token cut after its second part is still masked")
    void cutJwtIsMasked() {
      var cut = JWT.substring(0, JWT.lastIndexOf('.'));

      assertThat(text("token " + cut)).isEqualTo("token " + Masks.MASK);
    }

    @Test
    @DisplayName("a password in a key-value pair is masked, and the next pair is kept")
    void keyValueSecretIsMasked() {
      assertThat(text("password=" + PASSWORD + " user=bob")).isEqualTo("password=**** user=bob");
    }

    @Test
    @DisplayName("a quoted JSON secret is masked, even with spaces and escaped quotes")
    void quotedJsonSecretIsMasked() {
      assertThat(text("{\"password\":\"a b \\\"c\",\"id\":7}"))
          .isEqualTo("{\"password\":\"****\",\"id\":7}");
    }

    @Test
    @DisplayName("a token in a URL's query string is masked, and the other parameters are kept")
    void queryStringTokenIsMasked() {
      assertThat(text("GET /orders?access_token=abc123&page=2"))
          .isEqualTo("GET /orders?access_token=****&page=2");
    }

    @Test
    @DisplayName("a user name and password in a URL are masked")
    void urlCredentialsAreMasked() {
      assertThat(text("connecting to postgres://app:" + PASSWORD + "@db:5432/orders"))
          .isEqualTo("connecting to postgres://****@db:5432/orders");
    }

    @Test
    @DisplayName("a secret in a Lombok toString is masked")
    void lombokToStringSecretIsMasked() {
      assertThat(text("Login(user=bob, password=" + PASSWORD + ")"))
          .isEqualTo("Login(user=bob, password=****)");
    }

    @Test
    @DisplayName("a card number that passes the Luhn check keeps only its last four digits")
    void validCardIsMasked() {
      assertThat(text("paid with 4111 1111 1111 1111 today")).isEqualTo("paid with ****1111 today");
      assertThat(text("card " + CARD)).isEqualTo("card ****1111");
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "paid with 4111111111111111 43",
          "paid with 4111 1111 1111 1111 43",
          "43 4111111111111111 paid",
          "4111-1111-1111-1111 7 and 5500 0000 0000 0004"
        })
    @DisplayName("a card next to another number is still found and masked")
    void cardNextToNumberIsMasked(String line) {
      assertThat(text(line))
          .doesNotContain("4111111111111111")
          .doesNotContain("4111 1111 1111 1111")
          .doesNotContain("4111-1111-1111-1111")
          .contains("****1111");
    }

    @Test
    @DisplayName("a long number that fails the Luhn check is kept")
    void numberFailingLuhnIsKept() {
      assertThat(text("order 4111111111111112")).isEqualTo("order 4111111111111112");
    }

    @Test
    @DisplayName("a social security number keeps only its last four digits")
    void ssnIsMasked() {
      assertThat(text("ssn on file " + SSN)).doesNotContain("123-45").contains("****6789");
    }
  }

  @Nested
  @DisplayName("with the service's own rules")
  class ServiceRules {

    private final Redactor withRules =
        Redactor.from(LoggingTestData.withRedaction(List.of("badge_no"), List.of("ACME-\\d{6}")));

    @Test
    @DisplayName("an extra key is masked as a field and in a key-value pair, up to the next key")
    void extraKeyIsMasked() {
      assertThat(withRules.redact("badge-no", "B-77")).isEqualTo(Masks.MASK);
      assertThat(withRules.redact(null, "badgeNo=B 77 gate=4")).isEqualTo("badgeNo=**** gate=4");
    }

    @Test
    @DisplayName("an extra pattern is masked in full")
    void extraPatternIsMasked() {
      assertThat(withRules.redact(null, "ref ACME-123456 done")).isEqualTo("ref **** done");
    }

    @Test
    @DisplayName("the built-in rules still apply")
    void builtInRulesStillApply() {
      assertThat(withRules.redact("password", "x")).isEqualTo(Masks.MASK);
    }
  }
}
