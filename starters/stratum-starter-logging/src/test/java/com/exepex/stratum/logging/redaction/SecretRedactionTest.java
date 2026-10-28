package com.exepex.stratum.logging.redaction;

import static com.exepex.stratum.logging.testsupport.LoggingTestData.CARD;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.JWT;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.PASSWORD;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.SSN;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.logging.testsupport.LoggingTestData;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.json.WritableJson;

@DisplayName("Secrets, cards and national IDs are always masked")
class SecretRedactionTest {

  /** A field name that is always masked. */
  private static final String SECRET_FIELD = "password";

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
          "pass",
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
      assertThat(redactor.redact(name, "plain value")).isEqualTo(Redactor.MASK);
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
      assertThat(redactor.redact("pin", Integer.valueOf("1234"))).isEqualTo(Redactor.MASK);
    }

    @Test
    @DisplayName("a value that is written as its text, such as a URI, is masked by its content")
    void objectTextIsMasked() {
      var destination = URI.create("https://app:" + PASSWORD + "@host/orders");

      assertThat(redactor.redact("destination", destination)).isEqualTo("https://****@host/orders");
    }

    @Test
    @DisplayName("an array, list or map under a secret name is masked in full")
    void containerUnderSecretNameIsMasked() {
      assertThat(redactor.redact(SECRET_FIELD, new Object[] {PASSWORD})).isEqualTo(Redactor.MASK);
      assertThat(redactor.redact(SECRET_FIELD, List.of(PASSWORD))).isEqualTo(Redactor.MASK);
      assertThat(redactor.redact(SECRET_FIELD, Map.of("value", PASSWORD))).isEqualTo(Redactor.MASK);
    }

    @Test
    @DisplayName("JSON that Spring Boot writes as it is has its secrets masked")
    void writableJsonSecretIsMasked() {
      var details = WritableJson.of(out -> out.append("{\"password\":\"" + PASSWORD + "\"}"));
      var plain = WritableJson.of(out -> out.append("{\"id\":7}"));

      assertThat(written("details", details)).isEqualTo("\"{\\\"password\\\":\\\"****\\\"}\"");
      assertThat(written("details", plain)).isEqualTo("{\"id\":7}");
      assertThat(written(SECRET_FIELD, plain)).isEqualTo("\"****\"");
    }

    /** Returns the JSON written for the value after redaction. */
    private String written(String fieldName, WritableJson json) {
      return ((WritableJson) redactor.redact(fieldName, json)).toJsonString();
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
    @DisplayName("Digest credentials, a list of parameters, are masked to the end of the line")
    void digestParametersAreMasked() {
      assertThat(text("sent Digest username=\"fake-user\", response=\"fake-response\"\nnext"))
          .isEqualTo("sent Digest ****\nnext");
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
          "pass=" + PASSWORD,
          "{\\\"password\\\":\\\"" + PASSWORD + "\\\"}",
          "{'password': '" + PASSWORD + "'}",
          "GET /login?password%3D" + PASSWORD,
          "X-CSRF-Token: " + PASSWORD,
          "Customer[name=Jane, dbPassword=" + PASSWORD + "]"
        })
    @DisplayName(
        "a secret is masked in escaped JSON, single quotes, URL encoding and compound keys")
    void secretInOtherFormsIsMasked(String line) {
      assertThat(text(line)).doesNotContain(PASSWORD).contains(Redactor.MASK);
    }

    @Test
    @DisplayName("a secret in escaped JSON is masked whole, even when it holds an escaped quote")
    void escapedJsonSecretWithQuoteIsMasked() {
      var line = "{\\\"password\\\":\\\"prefix\\\\\\\"fake-suffix\\\",\\\"id\\\":7}";

      assertThat(text(line)).isEqualTo("{\\\"password\\\":\\\"****\\\",\\\"id\\\":7}");
    }

    @Test
    @DisplayName(
        "a JSON array or object under a secret key is masked whole, and the next pair kept")
    void jsonContainerUnderSecretKeyIsMasked() {
      assertThat(text("{\"token\":[\"" + PASSWORD + "\", 2],\"id\":7}"))
          .isEqualTo("{\"token\":****,\"id\":7}");
      assertThat(text("{\"secret\":{\"value\":\"a]b\",\"nested\":{\"x\":1}},\"id\":7}"))
          .isEqualTo("{\"secret\":****,\"id\":7}");
    }

    @Test
    @DisplayName("a JSON Web Token cut after its second part is still masked")
    void cutJwtIsMasked() {
      var cut = JWT.substring(0, JWT.lastIndexOf('.'));

      assertThat(text("token " + cut)).isEqualTo("token " + Redactor.MASK);
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
    @DisplayName("a password in a URL with no user name is masked")
    void urlPasswordWithoutUserIsMasked() {
      assertThat(text("connecting to redis://:" + PASSWORD + "@cache:6379/0"))
          .isEqualTo("connecting to redis://****@cache:6379/0");
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
          "order123 4111111111111111 paid",
          "order123 4111 1111 1111 1111 paid",
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
    @DisplayName("every card in a run of digit groups is masked, not only the first")
    void everyCardInRunIsMasked() {
      assertThat(text("cards 4111111111111111 5500000000000004 done"))
          .isEqualTo("cards ****1111 ****0004 done");
      assertThat(text("cards 4111 1111 1111 1111 4111 1111 1111 1111 done"))
          .doesNotContain("4111 1111 1111 1111");
    }

    @Test
    @DisplayName("a card that overlaps a longer valid number before it is masked too")
    void overlappingCardIsMasked() {
      assertThat(text("cards 3782 822463 10005 4242 4242 4242 4242 done"))
          .isEqualTo("cards ****4242 done");
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
      assertThat(withRules.redact("badge-no", "B-77")).isEqualTo(Redactor.MASK);
      assertThat(withRules.redact(null, "badgeNo=B 77 gate=4")).isEqualTo("badgeNo=**** gate=4");
    }

    @Test
    @DisplayName("an extra pattern is masked in full")
    void extraPatternIsMasked() {
      assertThat(withRules.redact(null, "ref ACME-123456 done")).isEqualTo("ref **** done");
    }

    @Test
    @DisplayName("an extra key that is built in with its last four kept is masked in full")
    void extraKeyIsMaskedInFull() {
      var withEmail = Redactor.from(LoggingTestData.withRedaction(List.of("email"), List.of()));

      assertThat(withEmail.redact("email", "jane.doe@example.com")).isEqualTo(Redactor.MASK);
    }

    @Test
    @DisplayName("the built-in rules still apply")
    void builtInRulesStillApply() {
      assertThat(withRules.redact(SECRET_FIELD, "x")).isEqualTo(Redactor.MASK);
    }
  }
}
