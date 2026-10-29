package com.exepex.stratum.logging.redaction;

import static com.exepex.stratum.logging.testsupport.LoggingTestData.DEFAULT_REDACTOR;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.JWT;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.PASSWORD;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.SSN;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.redactUnnamedText;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.logging.testsupport.LoggingTestData;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.json.WritableJson;

@DisplayName("Secrets, cards and national IDs are always masked")
class SecretRedactionTest {

  /** A field name that is always masked. */
  private static final String SECRET_FIELD = "password";

  @Nested
  @DisplayName("by field name")
  class ByFieldName {

    /** Card and national ID field names, each masked with its last four characters kept. */
    private static final List<String> CARD_AND_NATIONAL_ID_FIELD_NAMES =
        List.of(
            "card",
            "cardNumber",
            "credit_card",
            "pan",
            "ssn",
            "national_id",
            "tax_id",
            "passport",
            "passport_number");

    /** Secret field names, whole or as part of a longer name, each masked in full. */
    private static final List<String> SECRET_FIELD_NAMES =
        List.of(
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
            "aws_secret_access_key",
            "passcode",
            "passCode",
            "user_passcode");

    @ParameterizedTest
    @FieldSource("SECRET_FIELD_NAMES")
    @DisplayName("a secret field, or one whose name holds a secret word, is masked in full")
    void secretFieldIsMaskedInFull(String name) {
      assertThat(DEFAULT_REDACTOR.redact(name, "plain value")).isEqualTo(MaskedText.MASK);
    }

    @ParameterizedTest
    @FieldSource("CARD_AND_NATIONAL_ID_FIELD_NAMES")
    @DisplayName("a card or national ID field keeps only its last four characters")
    void cardFieldKeepsLastFour(String name) {
      assertThat(DEFAULT_REDACTOR.redact(name, "any-card-1234")).isEqualTo("****1234");
    }

    @Test
    @DisplayName("a value that is not text, such as a numeric PIN, is masked by its name")
    void nonTextValueIsMaskedByName() {
      assertThat(DEFAULT_REDACTOR.redact("pin", LoggingTestData.PIN)).isEqualTo(MaskedText.MASK);
    }

    @Test
    @DisplayName("a value that is written as its text, such as a URI, is masked by its content")
    void objectTextIsMasked() {
      var destination = URI.create("https://app:" + PASSWORD + "@host/orders");

      assertThat(DEFAULT_REDACTOR.redact("destination", destination))
          .isEqualTo("https://****@host/orders");
    }

    @Test
    @DisplayName("an array, list or map under a secret name is masked in full")
    void containerUnderSecretNameIsMasked() {
      assertThat(DEFAULT_REDACTOR.redact(SECRET_FIELD, new Object[] {PASSWORD}))
          .isEqualTo(MaskedText.MASK);
      assertThat(DEFAULT_REDACTOR.redact(SECRET_FIELD, List.of(PASSWORD)))
          .isEqualTo(MaskedText.MASK);
      assertThat(DEFAULT_REDACTOR.redact(SECRET_FIELD, Map.of("value", PASSWORD)))
          .isEqualTo(MaskedText.MASK);
    }

    @Test
    @DisplayName("JSON that Spring Boot writes as it is has its secrets masked")
    void writableJsonSecretIsMasked() {
      var details = WritableJson.of(out -> out.append("{\"password\":\"" + PASSWORD + "\"}"));
      var plain = WritableJson.of(out -> out.append("{\"id\":7}"));

      assertThat(redactedJson("details", details)).isEqualTo("\"{\\\"password\\\":\\\"****\\\"}\"");
      assertThat(redactedJson("details", plain)).isEqualTo("{\"id\":7}");
      assertThat(redactedJson(SECRET_FIELD, plain)).isEqualTo("\"****\"");
    }

    /** Returns the JSON written for the value after redaction. */
    private String redactedJson(String fieldName, WritableJson json) {
      return ((WritableJson) DEFAULT_REDACTOR.redact(fieldName, json)).toJsonString();
    }

    @Test
    @DisplayName("an ordinary field and value are left as they are")
    void ordinaryFieldIsKept() {
      assertThat(DEFAULT_REDACTOR.redact("orderId", LoggingTestData.ORDER_ID))
          .isEqualTo(LoggingTestData.ORDER_ID);
      assertThat(DEFAULT_REDACTOR.redact("name", "order-service")).isEqualTo("order-service");
    }
  }

  @Nested
  @DisplayName("in text")
  class InText {

    /** Secret values whose opening bracket is never closed before the text ends. */
    private static final List<String> SECRET_BRACKETS_AT_END_OF_TEXT =
        List.of("password=[", "{\"secret\":{", "password={\"a\":\"\\");

    /** A password written in escaped JSON, single quotes, URL encoding and compound keys. */
    private static final List<String> SECRETS_IN_OTHER_FORMS =
        List.of(
            "dbPassword=" + PASSWORD,
            "pass=" + PASSWORD,
            "{\\\"password\\\":\\\"" + PASSWORD + "\\\"}",
            "{'password': '" + PASSWORD + "'}",
            "GET /login?password%3D" + PASSWORD,
            "X-CSRF-Token: " + PASSWORD,
            "Customer[name=Jane, dbPassword=" + PASSWORD + "]");

    /** The HTTP authorization schemes whose credentials are masked. */
    private static final List<String> AUTHORIZATION_SCHEMES =
        List.of("Bearer", "Basic", "Digest", "Negotiate");

    @Test
    @DisplayName("a JSON Web Token is masked")
    void jwtIsMasked() {
      assertThat(redactUnnamedText("token " + JWT + " used")).isEqualTo("token **** used");
    }

    @ParameterizedTest
    @FieldSource("AUTHORIZATION_SCHEMES")
    @DisplayName("credentials after an authorization scheme are masked")
    void authorizationSchemeIsMasked(String scheme) {
      assertThat(redactUnnamedText("sent " + scheme + " dXNlcjpwYXNz to x"))
          .isEqualTo("sent " + scheme + " **** to x");
    }

    @Test
    @DisplayName("Digest credentials, a list of parameters, are masked to the end of the line")
    void digestParametersAreMasked() {
      assertThat(
              redactUnnamedText(
                  "sent Digest username=\"fake-user\", response=\"fake-response\"\nnext"))
          .isEqualTo("sent Digest ****\nnext");
    }

    @Test
    @DisplayName("an Authorization header written as text is masked")
    void authorizationHeaderIsMasked() {
      assertThat(redactUnnamedText("Authorization: Bearer abc.def.ghi"))
          .doesNotContain("abc.def.ghi");
    }

    @Test
    @DisplayName("a Cookie header is masked to the end of the line, with every cookie in it")
    void wholeCookieHeaderIsMasked() {
      assertThat(redactUnnamedText("Cookie: sid=fake-session; opaque=fake-cookie\nnext line"))
          .isEqualTo("Cookie: ****\nnext line");
    }

    @ParameterizedTest
    @FieldSource("SECRETS_IN_OTHER_FORMS")
    @DisplayName(
        "a secret is masked in escaped JSON, single quotes, URL encoding and compound keys")
    void secretInOtherFormsIsMasked(String line) {
      assertThat(redactUnnamedText(line)).doesNotContain(PASSWORD).contains(MaskedText.MASK);
    }

    @Test
    @DisplayName("a secret in escaped JSON is masked whole, even when it holds an escaped quote")
    void escapedJsonSecretWithQuoteIsMasked() {
      var line = "{\\\"password\\\":\\\"prefix\\\\\\\"fake-suffix\\\",\\\"id\\\":7}";

      assertThat(redactUnnamedText(line)).isEqualTo("{\\\"password\\\":\\\"****\\\",\\\"id\\\":7}");
    }

    @Test
    @DisplayName(
        "a JSON array or object under a secret key is masked whole, and the next pair kept")
    void jsonContainerUnderSecretKeyIsMasked() {
      assertThat(redactUnnamedText("{\"token\":[\"" + PASSWORD + "\", 2],\"id\":7}"))
          .isEqualTo("{\"token\":****,\"id\":7}");
      assertThat(
              redactUnnamedText("{\"secret\":{\"value\":\"a]b\",\"nested\":{\"x\":1}},\"id\":7}"))
          .isEqualTo("{\"secret\":****,\"id\":7}");
      assertThat(redactUnnamedText("{\"secret\":{\"note\":\"a \\\"}\\\" b\"},\"id\":7}"))
          .isEqualTo("{\"secret\":****,\"id\":7}");
    }

    @ParameterizedTest
    @FieldSource("SECRET_BRACKETS_AT_END_OF_TEXT")
    @DisplayName("a bracket that opens a secret value at the end of the text is masked")
    void bracketAtEndOfTextIsMasked(String line) {
      assertThat(redactUnnamedText(line)).endsWith(MaskedText.MASK);
    }

    @Test
    @DisplayName("a JSON Web Token cut after its second part is still masked")
    void cutJwtIsMasked() {
      var cut = JWT.substring(0, JWT.lastIndexOf('.'));

      assertThat(redactUnnamedText("token " + cut)).isEqualTo("token " + MaskedText.MASK);
    }

    @Test
    @DisplayName("a password in a key-value pair is masked, and the next pair is kept")
    void keyValueSecretIsMasked() {
      assertThat(redactUnnamedText("password=" + PASSWORD + " user=bob"))
          .isEqualTo("password=**** user=bob");
    }

    @Test
    @DisplayName("a sensitive key inside a masked value does not mask it a second time")
    void secretKeyInsideMaskedValueIsSkipped() {
      assertThat(redactUnnamedText("password=\"token=abc\"")).isEqualTo("password=\"****\"");
    }

    @Test
    @DisplayName("a quoted JSON secret is masked, even with spaces and escaped quotes")
    void quotedJsonSecretIsMasked() {
      assertThat(redactUnnamedText("{\"password\":\"a b \\\"c\",\"id\":7}"))
          .isEqualTo("{\"password\":\"****\",\"id\":7}");
    }

    @Test
    @DisplayName("a quoted secret with no closing quote is masked to the end of the text")
    void unclosedQuotedSecretIsMasked() {
      assertThat(redactUnnamedText("password=\"S3CR3T")).isEqualTo("password=\"****");
      assertThat(redactUnnamedText("{\"token\":\"S3CR3T")).isEqualTo("{\"token\":\"****");
    }

    @Test
    @DisplayName("a token in a URL's query string is masked, and the other parameters are kept")
    void queryStringTokenIsMasked() {
      assertThat(redactUnnamedText("GET /orders?access_token=abc123&page=2"))
          .isEqualTo("GET /orders?access_token=****&page=2");
    }

    @Test
    @DisplayName("a user name and password in a URL are masked")
    void urlCredentialsAreMasked() {
      assertThat(redactUnnamedText("connecting to postgres://app:" + PASSWORD + "@db:5432/orders"))
          .isEqualTo("connecting to postgres://****@db:5432/orders");
    }

    @Test
    @DisplayName("a password in a URL with no user name is masked")
    void urlPasswordWithoutUserIsMasked() {
      assertThat(redactUnnamedText("connecting to redis://:" + PASSWORD + "@cache:6379/0"))
          .isEqualTo("connecting to redis://****@cache:6379/0");
    }

    @Test
    @DisplayName("a URL password that holds a raw @ is masked up to the last @ before the host")
    void urlPasswordWithRawAtSignIsMasked() {
      assertThat(redactUnnamedText("connecting to https://bob:p@ss@word@api.example.com/v1"))
          .isEqualTo("connecting to https://****@api.example.com/v1");
    }

    @Test
    @DisplayName("an @ in a URL's path is not read as the end of a password")
    void atSignInUrlPathIsKept() {
      assertThat(redactUnnamedText("see https://social.example:8443/@bob and https://a:x@h/@c"))
          .isEqualTo("see https://social.example:8443/@bob and https://****@h/@c");
    }

    @Test
    @DisplayName("a secret in a Lombok toString is masked")
    void lombokToStringSecretIsMasked() {
      assertThat(redactUnnamedText("Login(user=bob, password=" + PASSWORD + ")"))
          .isEqualTo("Login(user=bob, password=****)");
    }

    @Test
    @DisplayName("a social security number keeps only its last four digits")
    void ssnIsMasked() {
      assertThat(redactUnnamedText("ssn on file " + SSN))
          .doesNotContain("123-45")
          .contains("****6789");
    }
  }

  @Nested
  @DisplayName("with the service's own rules")
  class ServiceRules {

    private final Redactor withRules =
        Redactor.from(
            LoggingTestData.withRedaction(List.of("badge_no"), List.of("ACME-\\d{6}")).build());

    @Test
    @DisplayName("an extra key is masked as a field and in a key-value pair, up to the next key")
    void extraKeyIsMasked() {
      assertThat(withRules.redact("badge-no", "B-77")).isEqualTo(MaskedText.MASK);
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
      var withEmail =
          Redactor.from(LoggingTestData.withRedaction(List.of("email"), List.of()).build());

      assertThat(withEmail.redact("email", "jane.doe@example.com")).isEqualTo(MaskedText.MASK);
    }

    @Test
    @DisplayName("the built-in rules still apply")
    void builtInRulesStillApply() {
      assertThat(withRules.redact(SECRET_FIELD, "x")).isEqualTo(MaskedText.MASK);
    }
  }
}
