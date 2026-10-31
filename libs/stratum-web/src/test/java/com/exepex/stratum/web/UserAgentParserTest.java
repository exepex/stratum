package com.exepex.stratum.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.web.testsupport.WebTestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

@DisplayName("UserAgentParser reads device, operating system and browser")
class UserAgentParserTest {

  /** The parser; loading its rules takes a few seconds, so the tests share it. */
  private static final UserAgentParser PARSER = new UserAgentParser(WebTestData.defaults().build());

  @Test
  @DisplayName("reads the User-Agent header, improved by the browser's client hints")
  void readsUserAgentAndClientHints() {
    var headers = new HttpHeaders();
    headers.set(
        HttpHeaders.USER_AGENT,
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko)"
            + " Chrome/120.0.0.0 Mobile Safari/537.36");
    headers.set("Sec-CH-UA-Model", "\"Pixel 7\"");
    headers.set("Sec-CH-UA-Platform", "\"Android\"");
    headers.set("Sec-CH-UA-Platform-Version", "\"14.0.0\"");

    assertThat(PARSER.fieldsOf(headers))
        .containsEntry(LoggingContextConstants.DEVICE_CLASS, "Mobile")
        .containsEntry(LoggingContextConstants.DEVICE_NAME, "Google Pixel 7")
        .containsEntry(LoggingContextConstants.OS_NAME, "Android")
        .containsEntry(LoggingContextConstants.OS_VERSION, "14.0.0")
        .containsEntry(LoggingContextConstants.BROWSER_NAME, "Chrome")
        .containsEntry(LoggingContextConstants.BROWSER_VERSION, "120");
  }

  @Test
  @DisplayName("leaves out what it cannot tell, and reads nothing without a User-Agent")
  void leavesOutUnknownValues() {
    var headers = new HttpHeaders();
    headers.set(HttpHeaders.USER_AGENT, "orders-cli/1.0");

    assertThat(PARSER.fieldsOf(headers)).doesNotContainKey(LoggingContextConstants.DEVICE_NAME);
    assertThat(PARSER.fieldsOf(new HttpHeaders())).isEmpty();
  }

  @Test
  @DisplayName("asks browsers for the model and platform version")
  void clientHintsToRequest() {
    assertThat(PARSER.clientHintsToRequest())
        .contains("Sec-CH-UA-Model")
        .contains("Sec-CH-UA-Platform-Version");
  }
}
