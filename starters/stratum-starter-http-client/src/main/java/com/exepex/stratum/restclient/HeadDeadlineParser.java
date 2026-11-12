package com.exepex.stratum.restclient;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.apache.hc.client5.http.impl.io.LenientHttpResponseParser;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.io.HttpMessageParserFactory;
import org.apache.hc.core5.http.io.SessionInputBuffer;
import org.jspecify.annotations.Nullable;

/**
 * Apache's own lenient parser of an answer's status line and headers, which reads them by the
 * deadline of the attempt running on this thread, when {@link #within} binds one: a head sent a
 * byte at a time ends with the call's {@code total-timeout}. Apache reads the whole head through
 * the stream it hands the parser, so no read of it escapes the deadline.
 */
final class HeadDeadlineParser extends LenientHttpResponseParser {

  /** Makes the parser of each new connection of a pool. */
  static final HttpMessageParserFactory<ClassicHttpResponse> FACTORY = HeadDeadlineParser::new;

  /** The deadline of the head being read on this thread, unbound outside an attempt. */
  private static final ScopedValue<ReadDeadline> DEADLINE = ScopedValue.newInstance();

  /**
   * Makes the parser of one connection.
   *
   * @param config Apache's limits on a head, or {@code null} for its defaults
   */
  private HeadDeadlineParser(@Nullable Http1Config config) {
    super(config);
  }

  /**
   * Reads the head through a stream that keeps to the deadline bound on this thread, if any.
   *
   * @param buffer Apache's buffer of the connection
   * @param inputStream the connection's stream
   * @return the answer, with its status line and headers
   * @throws IOException when the head cannot be read, or not in time
   * @throws HttpException when the head breaks the protocol
   */
  @Override
  public ClassicHttpResponse parse(SessionInputBuffer buffer, InputStream inputStream)
      throws IOException, HttpException {
    return super.parse(
        buffer, DEADLINE.isBound() ? new HeadStream(inputStream, DEADLINE.get()) : inputStream);
  }

  /**
   * Sends a request and reads its answer's head with the deadline given bound on this thread.
   *
   * @param deadline the deadline of the head's reads
   * @param exchange the sending of the request and the reading of its head
   * @return the answer
   * @throws IOException when the request fails, or its head does not arrive in time
   * @throws HttpException when the answer breaks the protocol
   */
  static ClassicHttpResponse within(ReadDeadline deadline, Exchange exchange)
      throws IOException, HttpException {
    return ScopedValue.where(DEADLINE, deadline).call(() -> Outcome.of(exchange)).answerOrThrow();
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
   * @param ioFailure the I/O failure, if any
   * @param protocolFailure the protocol failure, if any
   */
  private record Outcome(
      @Nullable ClassicHttpResponse answer,
      @Nullable IOException ioFailure,
      @Nullable HttpException protocolFailure) {

    /**
     * Runs an exchange and keeps what it ended with.
     *
     * @param exchange the exchange
     * @return its outcome
     */
    static Outcome of(Exchange exchange) {
      try {
        return new Outcome(exchange.run(), null, null);
      } catch (IOException failure) {
        return new Outcome(null, failure, null);
      } catch (HttpException failure) {
        return new Outcome(null, null, failure);
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
      if (ioFailure != null) {
        throw ioFailure;
      }
      if (protocolFailure != null) {
        throw protocolFailure;
      }
      return Objects.requireNonNull(answer);
    }
  }

  /** The connection's stream, whose every read keeps to the head's deadline. */
  private static final class HeadStream extends FilterInputStream {

    /** The deadline of the head's reads. */
    private final ReadDeadline deadline;

    /**
     * Wraps the connection's stream.
     *
     * @param content the connection's stream
     * @param deadline the deadline of the head's reads
     */
    private HeadStream(InputStream content, ReadDeadline deadline) {
      super(content);
      this.deadline = deadline;
    }

    /**
     * Reads one byte, by the deadline.
     *
     * @return the byte, or -1 at the end
     * @throws IOException when it cannot be read, or not in time
     */
    @Override
    public int read() throws IOException {
      deadline.beforeRead();
      return super.read();
    }

    /**
     * Reads bytes, by the deadline.
     *
     * @param buffer where the bytes go
     * @param offset where in the buffer
     * @param length how many at most
     * @return how many were read, or -1 at the end
     * @throws IOException when they cannot be read, or not in time
     */
    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      deadline.beforeRead();
      return super.read(buffer, offset, length);
    }
  }
}
