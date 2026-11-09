package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("A mocked answer's delay is limited as a real answer's wait is")
class MockRequestFactoryTest {

  /** The method the delayed answer is scripted for. */
  private static final String METHOD = "download";

  /** The time total-timeout leaves the attempt, below the delay. */
  private static final Duration TIME_LEFT = Duration.ofMillis(100);

  /** The answers, with a 300ms delay scripted for the method. */
  private final MockAnswers answers =
      new MockAnswers(new StaticListableBeanFactory().getBeanProvider(JsonMapper.class));

  /** The factory under test, with a client timeout far above the delay. */
  private final MockRequestFactory factory =
      new MockRequestFactory(RestClientTestData.PAYMENTS, RestClientTestData.WAIT, answers);

  @Test
  @DisplayName("a streamed download keeps its full timeout, past the time total-timeout leaves")
  void streamedDownloadKeepsTimeout() {
    scriptDelayedAnswer();

    assertThat(AttemptTimeLeft.runWithin(deadline(), true, this::call)).isEqualTo(HttpStatus.OK);
  }

  @Test
  @DisplayName("any other call fails once the time total-timeout leaves is spent")
  void otherCallEndsByTotalTimeout() {
    scriptDelayedAnswer();
    var deadline = deadline();

    assertThatExceptionOfType(UncheckedIOException.class)
        .isThrownBy(() -> AttemptTimeLeft.runWithin(deadline, false, this::call))
        .withCauseInstanceOf(SocketTimeoutException.class);
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
    try {
      var request = factory.createRequest(URI.create("http://payments/files"), HttpMethod.GET);
      request.getAttributes().put(MockAnswers.METHOD_ATTRIBUTE, new MockCall(METHOD, false));
      try (var response = request.execute()) {
        return HttpStatus.valueOf(response.getStatusCode().value());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
