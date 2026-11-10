package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

@DisplayName("ClientCallOutcome reads how a call ended from its last attempt sent")
class ClientCallOutcomeTest {

  /** A GET of the inventory client, whose 404 is an error. */
  private static final ClientCall CALL =
      ClientCall.builder()
          .client(RestClientTestData.INVENTORY)
          .method(HttpMethod.GET)
          .uriTemplate("/stock/{sku}")
          .build();

  /** Where the last attempt went. */
  private static final URI SENT_TO = URI.create("http://inventory:8080/stock/42");

  /** The time the call took. */
  private static final Duration TOOK = Duration.ofMillis(12);

  /**
   * How an attempt can end, and the call's outcome: each answer class, each failure with no answer,
   * and a failure inside the service.
   */
  private static final List<Ending> ENDINGS =
      List.of(
          new Ending("2xx", null, CallOutcome.SUCCESS),
          new Ending("4xx", error(HttpStatus.BAD_REQUEST), CallOutcome.CLIENT_ERROR),
          new Ending("5xx", error(HttpStatus.SERVICE_UNAVAILABLE), CallOutcome.SERVER_ERROR),
          new Ending(
              "read timeout", noAnswer(new SocketTimeoutException("read")), CallOutcome.TIMEOUT),
          new Ending(
              "connect failure",
              noAnswer(new ConnectException("refused")),
              CallOutcome.UNAVAILABLE),
          new Ending(
              "connect timeout",
              noAnswer(new ConnectTimeoutException("connect")),
              CallOutcome.UNAVAILABLE),
          new Ending(
              "full pool",
              noAnswer(new ConnectionRequestTimeoutException("pool")),
              CallOutcome.UNAVAILABLE),
          new Ending(
              "body cut off by a read timeout",
              new RestClientException("cut", new SocketTimeoutException("read")),
              CallOutcome.TIMEOUT),
          new Ending("unreadable answer", new RestClientException("bad JSON"), CallOutcome.ERROR));

  /** Returns Spring's exception for an error answer. */
  private static RuntimeException error(HttpStatus status) {
    return status.is4xxClientError()
        ? HttpClientErrorException.create(status, "", null, null, null)
        : HttpServerErrorException.create(status, "", null, null, null);
  }

  /** Returns Spring's exception for an attempt that got no answer. */
  private static RuntimeException noAnswer(IOException cause) {
    return new ResourceAccessException("no answer", cause);
  }

  /** Returns an attempt that was sent and ended with the failure, or none. */
  private static ClientAttempt sent(@Nullable RuntimeException failure) {
    return ClientAttempt.builder()
        .method(HttpMethod.GET)
        .failure(failure)
        .url(SENT_TO)
        .timeLeft(Duration.ZERO)
        .nextWait(Duration.ZERO)
        .build();
  }

  @ParameterizedTest(name = "{0}")
  @FieldSource("ENDINGS")
  @DisplayName("each ending maps to its outcome")
  void outcomes(Ending ending) {
    var outcome = ClientCallOutcome.of(CALL, sent(ending.failure()), null, 1, TOOK);

    assertThat(outcome.outcome()).isEqualTo(ending.expected());
    assertThat(outcome.url()).isEqualTo(SENT_TO);
  }

  @Nested
  @DisplayName("its status")
  class Status {

    @Test
    @DisplayName("an error answer carries its status")
    void errorAnswerHasStatus() {
      var outcome = ClientCallOutcome.of(CALL, sent(error(HttpStatus.BAD_GATEWAY)), null, 1, TOOK);

      assertThat(outcome.status()).isEqualTo(HttpStatus.BAD_GATEWAY.value());
    }

    @Test
    @DisplayName("a success and a failure with no answer carry none")
    void otherEndingsHaveNone() {
      assertThat(ClientCallOutcome.of(CALL, sent(null), null, 1, TOOK).status()).isNull();
      assertThat(
              ClientCallOutcome.of(
                      CALL, sent(noAnswer(new ConnectException("refused"))), null, 1, TOOK)
                  .status())
          .isNull();
    }
  }

  @Nested
  @DisplayName("a 404")
  class NotFound {

    @Test
    @DisplayName("on a call that reads it as nothing is a success, with no status")
    void emptyAnswerIsSuccess() {
      var optionalCall =
          ClientCall.builder()
              .client(CALL.client())
              .method(CALL.method())
              .uriTemplate(CALL.uriTemplate())
              .notFoundIsEmpty(true)
              .build();

      var outcome =
          ClientCallOutcome.of(optionalCall, sent(error(HttpStatus.NOT_FOUND)), null, 1, TOOK);

      assertThat(outcome.outcome()).isEqualTo(CallOutcome.SUCCESS);
      assertThat(outcome.status()).isNull();
    }

    @Test
    @DisplayName("on any other call is a client error")
    void otherCallIsClientError() {
      assertThat(
              ClientCallOutcome.of(CALL, sent(error(HttpStatus.NOT_FOUND)), null, 1, TOOK)
                  .outcome())
          .isEqualTo(CallOutcome.CLIENT_ERROR);
    }
  }

  @Nested
  @DisplayName("with no attempt sent")
  class NothingSent {

    @Test
    @DisplayName("every attempt refused by the breaker is refused, with no URL")
    void refused() {
      var outcome = ClientCallOutcome.of(CALL, null, null, 0, TOOK);

      assertThat(outcome.outcome()).isEqualTo(CallOutcome.REFUSED);
      assertThat(outcome.url()).isNull();
    }

    @Test
    @DisplayName("a failure before the attempts ended them keeps its own outcome")
    void notSentKeepsItsOutcome() {
      assertThat(ClientCallOutcome.of(CALL, null, CallOutcome.UNAVAILABLE, 0, TOOK).outcome())
          .isEqualTo(CallOutcome.UNAVAILABLE);
    }
  }

  /**
   * How an attempt ended, and the call's outcome.
   *
   * @param name what the ending is, for the test's name
   * @param failure the attempt's failure, or {@code null} for an answer that is not an error
   * @param expected the call's outcome
   */
  private record Ending(String name, @Nullable RuntimeException failure, CallOutcome expected) {}
}
