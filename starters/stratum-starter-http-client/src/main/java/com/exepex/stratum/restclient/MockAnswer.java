package com.exepex.stratum.restclient;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;

/**
 * One answer of a mocked client: from a script, from a file, or the 501 of a call with neither.
 *
 * @param status the answer's status
 * @param contentType the type of the body, or {@code null} when there is none
 * @param body the body, empty for none
 * @param delay how long the answer takes
 */
record MockAnswer(
    HttpStatusCode status, @Nullable MediaType contentType, String body, Duration delay) {

  /**
   * Returns an answer with a JSON body, or with no body when the text is empty.
   *
   * @param status the status
   * @param body the JSON text
   * @return the answer, with no delay
   */
  static MockAnswer json(HttpStatusCode status, String body) {
    return new MockAnswer(
        status, body.isEmpty() ? null : MediaType.APPLICATION_JSON, body, Duration.ZERO);
  }

  /**
   * Returns an answer with a text body.
   *
   * @param status the status
   * @param body the text
   * @return the answer, with no delay
   */
  static MockAnswer text(HttpStatusCode status, String body) {
    return new MockAnswer(status, MediaType.TEXT_PLAIN, body, Duration.ZERO);
  }

  /**
   * Returns the 200 with no body of a method whose return type reads none.
   *
   * @return the answer
   */
  static MockAnswer empty() {
    return new MockAnswer(HttpStatus.OK, null, "", Duration.ZERO);
  }

  /**
   * Returns this answer, taking the given time.
   *
   * @param time how long the answer takes
   * @return the answer
   */
  MockAnswer withDelay(Duration time) {
    return new MockAnswer(status, contentType, body, time);
  }
}
