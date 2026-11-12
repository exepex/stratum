package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

@DisplayName("A mock answer's text never shows its body, which may hold personal data")
class MockAnswerTest {

  @Test
  @DisplayName("the text gives the status and type, and only the body's length")
  void textHidesBody() {
    assertThat(MockAnswer.json(HttpStatus.OK, "{\"email\":\"person@example.invalid\"}"))
        .hasToString(
            "MockAnswer[status=200 OK, contentType=application/json, "
                + "34 body characters hidden, delay=PT0S]");
  }
}
