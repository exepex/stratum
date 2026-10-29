package com.exepex.stratum.logging.redaction;

import static com.exepex.stratum.logging.testsupport.LoggingTestData.PASSWORD;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.redactUnnamedText;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;

@DisplayName("A user name and password in a URL are masked, and never the end of them shown")
class UrlCredentialRedactionTest {

  /**
   * A URL with a password, followed by another secret that holds an {@code @}, each with the line
   * it must become. After a quote, the URL's mask stops at its host and the next secret is found by
   * its key. After a comma or semicolon, which a password may hold, the rest of the line is masked.
   */
  private static final List<Arguments> URLS_BEFORE_ANOTHER_SECRET =
      List.of(
          Arguments.of(
              "{\"url\":\"https://u:p@host\",\"password\":\"x@y\"}",
              "{\"url\":\"https://****@host\",\"password\":\"****\"}"),
          Arguments.of(
              "url='https://u:p@host';secret='s3@cr3t'", "url='https://****@host';secret='****'"),
          Arguments.of(
              "{\"url\":\"https://u:p@host\",\"token\":\"ab@cd\"}",
              "{\"url\":\"https://****@host\",\"token\":\"****\"}"),
          Arguments.of("url=https://u:p@host,password=x@y", "url=https://****"),
          Arguments.of("jdbc:mysql://u:p@db:3306;password=Pa@ss", "jdbc:mysql://****"));

  /** The characters that end a URL written in other text, each of which stops its password. */
  private static final List<String> CHARACTERS_THAT_END_A_URL =
      List.of(" ", "/", "?", "#", "\"", "'", "<", ">");

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

  @ParameterizedTest
  @FieldSource("URLS_BEFORE_ANOTHER_SECRET")
  @DisplayName("an @ in a secret after the URL is not read as the end of the URL's password")
  void atSignAfterUrlIsKept(String line, String redactedLine) {
    assertThat(redactUnnamedText(line)).isEqualTo(redactedLine);
  }

  @ParameterizedTest
  @FieldSource("CHARACTERS_THAT_END_A_URL")
  @DisplayName("an @ after a character that ends the URL is not read as the end of its password")
  void atSignAfterEndOfUrlIsKept(String endOfUrl) {
    assertThat(redactUnnamedText("https://u:p@host" + endOfUrl + "next@x"))
        .isEqualTo("https://****@host" + endOfUrl + "next@x");
  }

  @Test
  @DisplayName("a URL password that holds a raw @ and = is masked with the rest of the line")
  void urlPasswordWithAtSignAndEqualsIsMasked() {
    assertThat(redactUnnamedText("postgres://app:before@after=tail@db:5432/orders"))
        .isEqualTo("postgres://****");
  }

  @Test
  @DisplayName("an @ in a URL's query is not read as the end of a password")
  void atSignInUrlQueryIsKept() {
    assertThat(redactUnnamedText("https://alice:example@host?password=before@after"))
        .isEqualTo("https://****@host?password=****");
  }

  @Test
  @DisplayName("an @ in a URL's fragment is not read as the end of a password")
  void atSignInUrlFragmentIsKept() {
    assertThat(redactUnnamedText("https://u:p@ss@host#f@x")).isEqualTo("https://****@host#f@x");
  }

  @Test
  @DisplayName("an @ in a URL's path is not read as the end of a password")
  void atSignInUrlPathIsKept() {
    assertThat(redactUnnamedText("see https://social.example:8443/@bob and https://a:x@h/@c"))
        .isEqualTo("see https://social.example:8443/@bob and https://****@h/@c");
  }
}
