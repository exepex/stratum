package com.exepex.stratum.restclient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.impl.io.HttpRequestExecutor;
import org.apache.hc.core5.http.io.HttpClientConnection;
import org.apache.hc.core5.http.io.HttpResponseInformationCallback;
import org.apache.hc.core5.http.io.entity.HttpEntityWrapper;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.jspecify.annotations.Nullable;

/**
 * Ends the reading of an answer, its status line and headers, its body, and the reading of its rest
 * when the answer is closed, when the call's {@code total-timeout} runs out, however slowly it
 * comes. A streamed download through a pool that streams answers keeps only its {@code timeout}
 * between bytes.
 */
@RequiredArgsConstructor
class AnswerDeadline extends HttpRequestExecutor {

  /** The message of the failure when the body is still arriving at the deadline. */
  private static final String TOO_SLOW = "The answer's body did not arrive within total-timeout";

  /** The message of the failure when the status line and headers are still arriving. */
  private static final String HEAD_TOO_SLOW =
      "The answer's status line and headers did not arrive within total-timeout";

  /** The client's {@code timeout}, the longest wait for one read of the answer. */
  private final Duration timeout;

  /** Whether a streamed download through this pool keeps its {@code timeout}. */
  private final boolean streamsAnswers;

  /**
   * Sends the request and reads the answer's head by the call's deadline, then bounds its body by
   * it too; a streamed download, or a call outside an attempt, keeps Apache's own reads.
   *
   * @param request the request
   * @param connection the connection it is sent on
   * @param informationCallback what is told of each 1xx answer
   * @param context the request's context
   * @return the answer, whose body ends by the deadline
   * @throws IOException when the request fails, or its head does not arrive in time
   * @throws HttpException when the answer breaks the protocol
   */
  @Override
  public ClassicHttpResponse execute(
      ClassicHttpRequest request,
      HttpClientConnection connection,
      @Nullable HttpResponseInformationCallback informationCallback,
      HttpContext context)
      throws IOException, HttpException {
    var timeLeft = AttemptTimeLeft.timeLeft();
    if (timeLeft.isEmpty() || !boundsAnswer()) {
      return super.execute(request, connection, informationCallback, context);
    }
    var head =
        new ReadDeadline(
            connection, System.nanoTime() + timeLeft.get().toNanos(), timeout, HEAD_TOO_SLOW);
    return bounded(
        readHead(head, () -> super.execute(request, connection, informationCallback, context)),
        connection);
  }

  /**
   * Sends a request and reads its answer's head with the given deadline bound on this thread, for
   * the connection's {@link HeadDeadlineParser}.
   *
   * @param deadline the deadline of the head's reads
   * @param exchange the sending of the request and the reading of its head
   * @return the answer
   * @throws IOException when the request fails, or its head does not arrive in time
   * @throws HttpException when the answer breaks the protocol
   */
  ClassicHttpResponse readHead(ReadDeadline deadline, Exchange exchange)
      throws IOException, HttpException {
    return ScopedValue.where(HeadDeadlineParser.DEADLINE, deadline)
        .call(() -> Outcome.of(exchange))
        .answerOrThrow();
  }

  /**
   * Returns whether the attempt running on this thread has its answer cut to the call's time left:
   * every attempt but a streamed download through a pool that streams answers.
   *
   * @return whether the answer ends by the deadline
   */
  boolean boundsAnswer() {
    return !streamsAnswers || !AttemptTimeLeft.keepsAnswerTimeout();
  }

  /**
   * Bounds the answer's body by the deadline of the attempt running on this thread, taken now so
   * that a body read after the attempt, as a client's beans do, is bounded too. Outside an attempt,
   * or with no body, the answer is unchanged.
   *
   * @param response the answer
   * @param connection the connection it came on
   * @return the answer
   * @throws IOException when the body cannot be opened
   */
  ClassicHttpResponse bounded(ClassicHttpResponse response, HttpClientConnection connection)
      throws IOException {
    var timeLeft = AttemptTimeLeft.timeLeft();
    if (response.getEntity() != null && timeLeft.isPresent() && boundsAnswer()) {
      var deadline =
          new ReadDeadline(
              connection, System.nanoTime() + timeLeft.get().toNanos(), timeout, TOO_SLOW);
      response.setEntity(new DeadlineEntity(response.getEntity(), deadline));
    }
    return response;
  }

  /** A body whose every read goes through one stream that ends by the deadline. */
  private static final class DeadlineEntity extends HttpEntityWrapper {

    /** The body's one stream. */
    private final BodyStream body;

    /**
     * Wraps the body.
     *
     * @param entity the answer's body
     * @param deadline the deadline of its reads
     * @throws IOException when the body cannot be opened
     */
    private DeadlineEntity(HttpEntity entity, ReadDeadline deadline) throws IOException {
      super(entity);
      body = new BodyStream(entity.getContent(), deadline);
    }

    /**
     * Returns the body's stream, which ends by the deadline.
     *
     * @return the stream
     */
    @Override
    public InputStream getContent() {
      return body;
    }

    /**
     * Writes the body, as long as it arrives by the deadline.
     *
     * @param out where the body is written
     * @throws IOException when it cannot be read in time or written
     */
    @Override
    public void writeTo(OutputStream out) throws IOException {
      try (var content = getContent()) {
        content.transferTo(out);
      }
    }
  }

  /** A body that ends by the deadline, and whose close reads its rest by the deadline too. */
  private static final class BodyStream extends DeadlineStream {

    /** Whether the body was read to its end and closed, as Apache and then Spring both close it. */
    private boolean closed;

    /**
     * Wraps the body.
     *
     * @param content the answer's body as Apache reads it
     * @param deadline the deadline of its reads
     */
    private BodyStream(InputStream content, ReadDeadline deadline) {
      super(content, deadline);
    }

    /**
     * Reads and drops the rest of the body by the deadline, so that the connection can be used
     * again, then closes it. Past the deadline it fails without closing Apache's stream, whose own
     * close would read the rest with no limit, and Apache then drops the connection. Once closed,
     * closing again does nothing.
     *
     * @throws IOException when the rest cannot be read, or not in time
     */
    @Override
    public void close() throws IOException {
      if (!closed) {
        transferTo(OutputStream.nullOutputStream());
        super.close();
        closed = true;
      }
    }
  }

  /** The sending of a request and the reading of its answer's head, as Apache does it. */
  @FunctionalInterface
  interface Exchange {

    /**
     * Sends the request and reads the answer's head.
     *
     * @return the answer
     * @throws IOException when the request fails
     * @throws HttpException when the answer breaks the protocol
     */
    ClassicHttpResponse run() throws IOException, HttpException;
  }

  /**
   * What an exchange ended with, so that its two kinds of failure leave the scoped call, which can
   * throw only one kind, and are thrown as they were.
   *
   * @param answer the answer, or {@code null} after a failure
   * @param failure the I/O or protocol failure, if any
   */
  private record Outcome(@Nullable ClassicHttpResponse answer, @Nullable Exception failure) {

    /**
     * Runs an exchange and keeps what it ended with.
     *
     * @param exchange the exchange
     * @return its outcome
     */
    static Outcome of(Exchange exchange) {
      try {
        return new Outcome(exchange.run(), null);
      } catch (IOException | HttpException failure) {
        return new Outcome(null, failure);
      }
    }

    /**
     * Returns the answer, or throws the failure the exchange ended with.
     *
     * @return the answer
     * @throws IOException when the exchange failed to send or read
     * @throws HttpException when the answer broke the protocol
     */
    ClassicHttpResponse answerOrThrow() throws IOException, HttpException {
      if (failure instanceof IOException ioFailure) {
        throw ioFailure;
      }
      if (failure instanceof HttpException protocolFailure) {
        throw protocolFailure;
      }
      return Objects.requireNonNull(answer);
    }
  }
}
