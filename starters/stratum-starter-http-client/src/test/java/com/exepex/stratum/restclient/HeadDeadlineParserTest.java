package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.exepex.stratum.test.CpuCostMeter;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.impl.io.SessionInputBufferImpl;
import org.apache.hc.core5.http.io.HttpClientConnection;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName(
    "An answer's head is read by the deadline bound for it, and as Apache reads it outside")
class HeadDeadlineParserTest {

  /** The client's timeout, the longest wait for one read. */
  private static final Duration TIMEOUT = Duration.ofMillis(100);

  /** A deadline far enough away that the timeout is the smaller. */
  private static final Duration TEN_SECONDS = Duration.ofSeconds(10);

  /** The message of the failure past the deadline. */
  private static final String TOO_SLOW = "the head did not arrive";

  /** The length of each filler header's value, so that the header line is 80 bytes. */
  private static final int FILLER_LENGTH = 70;

  /** The size of Apache's buffer of a connection, its default. */
  private static final int BUFFER_SIZE = 8192;

  /**
   * A head with as many lines as Apache's default limit of 100 headers allows, each of about 80
   * bytes, so about 8 KB. Apache lets each line be up to 8192 characters, so a head may be larger.
   */
  private static final String LARGE_HEAD =
      "HTTP/1.1 200 OK\r\n"
          + ("X-Filler: " + "a".repeat(FILLER_LENGTH) + "\r\n").repeat(99)
          + "\r\n";

  /**
   * CPU time the parse of {@link #LARGE_HEAD}, read 1 byte per read, may take: 10 times M = 650 µs,
   * the largest best value of 3 runs on a 4-CPU build machine (497, 648 and 650 µs), on JDK 25,
   * 2026-11-12. A check that cost more than a compare and a clock read per byte would take far
   * longer.
   */
  private static final Duration LARGE_HEAD_LIMIT = Duration.ofNanos(6_500_000);

  /** Parses in each timed batch, enough that a batch takes a few milliseconds. */
  private static final int PARSES_PER_BATCH = 20;

  /** The connection the answer comes on, Apache's boundary to the socket. */
  private final HttpClientConnection connection = mock(HttpClientConnection.class);

  /** The executor that binds the head's deadline, as each pool's is. */
  private final AnswerDeadline answerDeadline = new AnswerDeadline(TIMEOUT, true);

  /** The parser of one connection, as the pool makes it. */
  private final HeadDeadlineParser parser =
      (HeadDeadlineParser) HeadDeadlineParser.FACTORY.create(null);

  /** Returns a deadline of the head's reads the given time from now. */
  private ReadDeadline deadlineIn(Duration timeLeft) {
    return new ReadDeadline(connection, System.nanoTime() + timeLeft.toNanos(), TIMEOUT, TOO_SLOW);
  }

  /** Parses a head from a stream through the parser. */
  private ClassicHttpResponse parsed(InputStream head) throws IOException, HttpException {
    return parser.parse(new SessionInputBufferImpl(BUFFER_SIZE), head);
  }

  /** Returns a head's bytes as a stream. */
  private InputStream streamOf(String head) {
    return new ByteArrayInputStream(head.getBytes(StandardCharsets.US_ASCII));
  }

  @Test
  @DisplayName("with no deadline bound, the head is read as Apache reads it")
  void noDeadlineReadsAsApache() throws IOException, HttpException {
    assertThat(parsed(streamOf("HTTP/1.1 204 No Content\r\n\r\n")).getCode())
        .isEqualTo(HttpStatus.SC_NO_CONTENT);
    verifyNoInteractions(connection);
  }

  @Nested
  @DisplayName("with a deadline bound")
  class Bound {

    @Test
    @DisplayName("each read waits at most the timeout, and the head is read")
    void readsWithinTimeout() throws IOException, HttpException {
      try (var answer =
          answerDeadline.readHead(
              deadlineIn(TEN_SECONDS), () -> parsed(streamOf("HTTP/1.1 200 OK\r\n\r\n")))) {
        assertThat(answer.getCode()).isEqualTo(HttpStatus.SC_OK);
      }
      verify(connection).setSocketTimeout(Timeout.of(TIMEOUT));
    }

    @Test
    @DisplayName("a wait cut below 1 ms is set to 1 ms, as Apache reads 0 as no limit")
    void waitAtLeastOneMillisecond() throws SocketTimeoutException {
      new ReadDeadline(
              connection, System.nanoTime() + TEN_SECONDS.toNanos(), Duration.ZERO, TOO_SLOW)
          .beforeRead();

      verify(connection).setSocketTimeout(Timeout.ofMilliseconds(1));
    }

    @Test
    @DisplayName("past the deadline, no byte is read and the read fails as a timeout")
    void pastDeadlineFails() throws IOException {
      try (var head = streamOf("HTTP/1.1 200 OK\r\n\r\n")) {
        assertThatExceptionOfType(SocketTimeoutException.class)
            .isThrownBy(
                () -> answerDeadline.readHead(deadlineIn(Duration.ZERO), () -> parsed(head)))
            .withMessage(TOO_SLOW);
        assertThat(head.available()).isPositive();
      }
    }

    @Test
    @DisplayName("a head that breaks the protocol fails with Apache's own exception")
    void protocolFailureKept() {
      assertThatThrownBy(
              () ->
                  answerDeadline.readHead(
                      deadlineIn(TEN_SECONDS),
                      () -> parsed(streamOf("HTTP/1.1 200 OK\r\nno colon\r\n\r\n"))))
          .isInstanceOf(ProtocolException.class);
    }

    @Test
    @DisplayName("a head at Apache's limits, read 1 byte per read, is read within the time limit")
    void largeHeadWithinLimit() {
      new CpuCostMeter(PARSES_PER_BATCH)
          .assertCpuCostWithin(
              "a 8 KB head read 1 byte per read", LARGE_HEAD_LIMIT, this::parseLarge);
    }

    /** Parses {@link #LARGE_HEAD}, one byte per read, with a deadline bound. */
    private void parseLarge() {
      try {
        answerDeadline.readHead(deadlineIn(TEN_SECONDS), () -> parsed(oneBytePerRead()));
      } catch (IOException failure) {
        throw new UncheckedIOException(failure);
      } catch (HttpException failure) {
        throw new IllegalStateException(failure);
      }
    }

    /** Returns {@link #LARGE_HEAD} as a stream that gives at most one byte per read. */
    private InputStream oneBytePerRead() {
      return new FilterInputStream(streamOf(LARGE_HEAD)) {
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
          return super.read(buffer, offset, Math.min(1, length));
        }
      };
    }
  }
}
