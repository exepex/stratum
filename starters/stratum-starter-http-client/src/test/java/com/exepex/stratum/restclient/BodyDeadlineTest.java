package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.HttpClientConnection;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("BodyDeadline ends an answer's body by the deadline of the call's attempt")
class BodyDeadlineTest {

  /** The client's timeout, below the time left of an attempt with {@link #TEN_SECONDS} left. */
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  /** The time left of an attempt that still has time. */
  private static final Duration TEN_SECONDS = Duration.ofSeconds(10);

  /** A client's timeout above {@link #TEN_SECONDS}, so that the time left sets each read's wait. */
  private static final Duration LONG_TIMEOUT = Duration.ofSeconds(30);

  /** The body of every answer. */
  private static final byte[] BODY = "body".getBytes(StandardCharsets.US_ASCII);

  /** The connection the answer comes on, Apache's boundary to the socket. */
  private final HttpClientConnection connection = mock(HttpClientConnection.class);

  /** The executor of a pool that streams answers, as each client's own pool does. */
  private final BodyDeadline bodyDeadline = new BodyDeadline(TIMEOUT, true);

  @Nested
  @DisplayName("with no time left")
  class NoTimeLeft {

    /** The body as Apache reads it from the socket, which must never be touched. */
    private final InputStream socketBody = spy(new ByteArrayInputStream(BODY));

    /** The answer, bounded inside an attempt whose time is up. */
    private final ClassicHttpResponse response =
        boundedWithin(bodyDeadline, Duration.ZERO, false, answerOf(socketBody));

    @Test
    @DisplayName("a read fails with SocketTimeoutException and reads nothing")
    void readFails() {
      assertThatExceptionOfType(SocketTimeoutException.class)
          .isThrownBy(() -> response.getEntity().getContent().read());
      verifyNoInteractions(socketBody, connection);
    }

    @Test
    @DisplayName("closing the body fails and leaves Apache's stream unread and open")
    void closeFails() {
      assertThatExceptionOfType(SocketTimeoutException.class)
          .isThrownBy(() -> response.getEntity().getContent().close());
      verifyNoInteractions(socketBody, connection);
    }

    @Test
    @DisplayName("writing the body out fails and reads nothing")
    void writeToFails() {
      assertThatExceptionOfType(SocketTimeoutException.class)
          .isThrownBy(() -> response.getEntity().writeTo(new ByteArrayOutputStream()));
      verifyNoInteractions(socketBody, connection);
    }
  }

  @Nested
  @DisplayName("with time left")
  class WithTimeLeft {

    @Test
    @DisplayName("a read waits at most the time left when it is below timeout")
    void readWaitsAtMostTimeLeft() throws IOException {
      var longTimeout = new BodyDeadline(LONG_TIMEOUT, true);
      var response =
          boundedWithin(longTimeout, TEN_SECONDS, false, answerOf(new ByteArrayInputStream(BODY)));

      assertThat(response.getEntity().getContent().read()).isEqualTo(BODY[0]);
      assertThat(socketTimeoutSet().toDuration())
          .isLessThanOrEqualTo(TEN_SECONDS)
          .isGreaterThan(TEN_SECONDS.minusSeconds(1));
    }

    @Test
    @DisplayName("a read waits at most timeout, set once while it stays the same")
    void socketTimeoutSetOnlyWhenItChanges() throws IOException {
      var response =
          boundedWithin(bodyDeadline, TEN_SECONDS, false, answerOf(new ByteArrayInputStream(BODY)));
      try (var content = response.getEntity().getContent()) {
        content.read();
        content.read();
      }

      assertThat(socketTimeoutSet().toDuration()).isEqualTo(TIMEOUT);
    }

    @Test
    @DisplayName("closing the body reads its rest, then closes Apache's stream")
    void closeDrainsThenCloses() throws IOException {
      var socketBody = spy(new ByteArrayInputStream(BODY));

      boundedWithin(bodyDeadline, TEN_SECONDS, false, answerOf(socketBody))
          .getEntity()
          .getContent()
          .close();

      assertThat(socketBody.available()).isZero();
      verify(socketBody).close();
    }

    @Test
    @DisplayName("closing the body again, as Apache and Spring each do, reads and closes nothing")
    void secondCloseDoesNothing() throws IOException {
      var socketBody = spy(new ByteArrayInputStream(BODY));
      var response = boundedWithin(bodyDeadline, TEN_SECONDS, false, answerOf(socketBody));

      try (var content = response.getEntity().getContent()) {
        content.close();
      }

      verify(socketBody).close();
    }

    @Test
    @DisplayName("writing the body out writes it whole")
    void writeToWritesWholeBody() throws IOException {
      var response =
          boundedWithin(bodyDeadline, TEN_SECONDS, false, answerOf(new ByteArrayInputStream(BODY)));
      var out = new ByteArrayOutputStream();

      response.getEntity().writeTo(out);

      assertThat(out.toByteArray()).isEqualTo(BODY);
    }
  }

  @Nested
  @DisplayName("leaves the answer as it is")
  class LeftAsItIs {

    @Test
    @DisplayName("outside a call's attempt")
    void outsideAnAttempt() throws IOException {
      assertThat(
              bodyDeadline
                  .bounded(answerOf(new ByteArrayInputStream(BODY)), connection)
                  .getEntity())
          .isInstanceOf(InputStreamEntity.class);
    }

    @Test
    @DisplayName("when the answer has no body")
    void withoutBody() {
      assertThat(
              boundedWithin(
                      bodyDeadline,
                      TEN_SECONDS,
                      false,
                      new BasicClassicHttpResponse(HttpStatus.SC_NO_CONTENT))
                  .getEntity())
          .isNull();
    }

    @Test
    @DisplayName("for a streamed download through a pool that streams answers")
    void streamedDownload() {
      assertThat(
              boundedWithin(
                      bodyDeadline, TEN_SECONDS, true, answerOf(new ByteArrayInputStream(BODY)))
                  .getEntity())
          .isInstanceOf(InputStreamEntity.class);
    }

    @Test
    @DisplayName("but not for a streamed download through a token pool, which bounds every answer")
    void tokenPoolBoundsStreamedDownload() {
      var tokenPool = new BodyDeadline(TIMEOUT, false);

      assertThat(
              boundedWithin(tokenPool, TEN_SECONDS, true, answerOf(new ByteArrayInputStream(BODY)))
                  .getEntity())
          .isNotInstanceOf(InputStreamEntity.class);
    }
  }

  /** Returns a 200 answer whose body is the given stream. */
  private ClassicHttpResponse answerOf(InputStream body) {
    var response = new BasicClassicHttpResponse(HttpStatus.SC_OK);
    response.setEntity(new InputStreamEntity(body, ContentType.TEXT_PLAIN));
    return response;
  }

  /** Bounds the answer inside an attempt with the given time left. */
  private ClassicHttpResponse boundedWithin(
      BodyDeadline executor,
      Duration timeLeft,
      boolean keepsAnswerTimeout,
      ClassicHttpResponse response) {
    return AttemptTimeLeft.runWithin(
        System.nanoTime() + timeLeft.toNanos(),
        keepsAnswerTimeout,
        new AtomicReference<>(),
        () -> bounded(executor, response));
  }

  /** Bounds the answer, as the executor does once its head is read. */
  private ClassicHttpResponse bounded(BodyDeadline executor, ClassicHttpResponse response) {
    try {
      return executor.bounded(response, connection);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  /** Returns the one socket timeout set on the connection. */
  private Timeout socketTimeoutSet() {
    var socketTimeout = ArgumentCaptor.forClass(Timeout.class);
    verify(connection).setSocketTimeout(socketTimeout.capture());
    return socketTimeout.getValue();
  }
}
