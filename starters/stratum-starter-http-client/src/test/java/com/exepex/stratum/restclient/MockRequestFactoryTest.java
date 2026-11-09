package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;

@DisplayName("A mocked answer is limited as a real answer is")
class MockRequestFactoryTest {

  /** The method the delayed answer is scripted for. */
  private static final String METHOD = "download";

  /** The time total-timeout leaves the attempt, below the delay. */
  private static final Duration TIME_LEFT = Duration.ofMillis(100);

  /** The answers, with a 300ms delay scripted for the method. */
  private final MockAnswers answers = RestClientTestData.answersWithoutMapper();

  /** The factory under test, with a client timeout far above the delay. */
  private final MockRequestFactory factory =
      new MockRequestFactory(RestClientTestData.PAYMENTS, RestClientTestData.WAIT, answers);

  @Nested
  @DisplayName("in its delay")
  class Delay {

    @Test
    @DisplayName("a streamed download keeps its full timeout, past the time total-timeout leaves")
    void streamedDownloadKeepsTimeout() {
      scriptDelayedAnswer();

      assertThat(AttemptTimeLeft.runWithin(deadline(), true, MockRequestFactoryTest.this::call))
          .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("any other call fails once the time total-timeout leaves is spent")
    void otherCallEndsByTotalTimeout() {
      scriptDelayedAnswer();
      var deadline = deadline();

      assertThatExceptionOfType(UncheckedIOException.class)
          .isThrownBy(
              () -> AttemptTimeLeft.runWithin(deadline, false, MockRequestFactoryTest.this::call))
          .withCauseInstanceOf(SocketTimeoutException.class);
    }
  }

  @Nested
  @DisplayName("in its error body")
  class ErrorBody {

    @Test
    @DisplayName("an error body is cut at 64 KB, as ErrorBodyLimit cuts a real one")
    void errorBodyCut() throws IOException {
      answers.script(
          RestClientTestData.PAYMENTS,
          METHOD,
          MockAnswer.text(
              HttpStatus.INTERNAL_SERVER_ERROR,
              "x".repeat(HttpClientConstants.ERROR_BODY_LIMIT * 2)));

      assertThat(bodyLength()).isEqualTo(HttpClientConstants.ERROR_BODY_LIMIT);
    }

    @Test
    @DisplayName("a successful body is never cut")
    void successfulBodyWhole() throws IOException {
      var size = HttpClientConstants.ERROR_BODY_LIMIT * 2;
      answers.script(
          RestClientTestData.PAYMENTS, METHOD, MockAnswer.text(HttpStatus.OK, "x".repeat(size)));

      assertThat(bodyLength()).isEqualTo(size);
    }
  }

  /** Scripts a 200 that takes longer than the time left. */
  private void scriptDelayedAnswer() {
    answers.script(
        RestClientTestData.PAYMENTS,
        METHOD,
        MockAnswer.empty().withDelay(RestClientTestData.SHORT));
  }

  /** Returns the deadline that leaves {@link #TIME_LEFT}. */
  private long deadline() {
    return System.nanoTime() + TIME_LEFT.toNanos();
  }

  /** Sends one call of the method and returns its status. */
  private HttpStatus call() {
    try (var response = execute()) {
      return HttpStatus.valueOf(response.getStatusCode().value());
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  /** Sends one call of the method and returns the length of the body it reads. */
  private int bodyLength() throws IOException {
    try (var response = execute()) {
      return response.getBody().readAllBytes().length;
    }
  }

  /** Sends one call of the method and returns its answer. */
  private ClientHttpResponse execute() throws IOException {
    var request = factory.createRequest(URI.create("http://payments/files"), HttpMethod.GET);
    request.getAttributes().put(MockAnswers.METHOD_ATTRIBUTE, new MockCall(METHOD, false));
    return request.execute();
  }
}
