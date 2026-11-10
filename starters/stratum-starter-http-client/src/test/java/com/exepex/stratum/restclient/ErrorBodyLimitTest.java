package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.httpclient.HttpClientConstants;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("An error body is read, and drained when closed, up to 64 KB")
class ErrorBodyLimitTest {

  /** The limit on an error body. */
  private static final int LIMIT = HttpClientConstants.ERROR_BODY_LIMIT;

  /** Returns a 500 whose body, of unknown length, is the given stream. */
  private ClassicHttpResponse errorAnswer(InputStream apacheStream) {
    var answer = new BasicClassicHttpResponse(HttpStatus.SC_INTERNAL_SERVER_ERROR);
    answer.setEntity(new InputStreamEntity(apacheStream, -1, null));
    return answer;
  }

  /** Returns an answer's body as the handler limits it. */
  private HttpEntity limited(ClassicHttpResponse answer) throws IOException, HttpException {
    return new ErrorBodyLimit()
        .execute(new BasicClassicHttpRequest("GET", "/"), null, (request, scope) -> answer)
        .getEntity();
  }

  @Nested
  @DisplayName("when read")
  class Read {

    @Test
    @DisplayName("ends at 64 KB, byte by byte and when skipped, with nothing more available")
    void endsAtLimit() throws IOException, HttpException {
      try (var apacheStream = new ClosingStream(LIMIT * 2);
          var answer = errorAnswer(apacheStream);
          var entity = limited(answer);
          var body = entity.getContent()) {
        assertThat(body.skip(LIMIT - 1)).isEqualTo(LIMIT - 1);
        assertThat(body.read()).isZero();
        assertThat(body.read()).isEqualTo(-1);
        assertThat(body.skip(1)).isZero();
        assertThat(body.available()).isZero();
      }
    }

    @Test
    @DisplayName("writes only 64 KB")
    void writesOnlyLimit() throws IOException, HttpException {
      var written = new ByteArrayOutputStream();
      try (var apacheStream = new ClosingStream(LIMIT * 2);
          var answer = errorAnswer(apacheStream);
          var entity = limited(answer)) {
        entity.writeTo(written);
      }
      assertThat(written.size()).isEqualTo(LIMIT);
    }
  }

  @Nested
  @DisplayName("when closed")
  class Close {

    @Test
    @DisplayName("a body that ends within 64 KB is drained and Apache's stream closed")
    void drainedWithinLimit() throws IOException, HttpException {
      try (var apacheStream = new ClosingStream(LIMIT - 1);
          var answer = errorAnswer(apacheStream);
          var entity = limited(answer)) {
        entity.getContent().close();

        assertThat(apacheStream.closed).isTrue();
      }
    }

    @Test
    @DisplayName("a body past 64 KB is left open, and the entity's close skips it")
    void leftOpenPastLimit() throws IOException, HttpException {
      try (var apacheStream = new ClosingStream(LIMIT + 1);
          var answer = errorAnswer(apacheStream);
          var entity = limited(answer)) {
        entity.getContent().close();
        entity.close();

        assertThat(apacheStream.closed).isFalse();
      }
    }

    @Test
    @DisplayName("a failed drain leaves the body open, so its connection drops, and is thrown")
    void failedDrainLeftOpen() throws IOException, HttpException {
      try (var apacheStream = new FailingStream();
          var answer = errorAnswer(apacheStream);
          var entity = limited(answer);
          var body = entity.getContent()) {
        assertThatExceptionOfType(IOException.class).isThrownBy(body::close);
        entity.close();

        assertThat(apacheStream.closed).isFalse();
      }
    }

    @Test
    @DisplayName("a Content-Length over 64 KB leaves Apache's stream open, unread, at once")
    void lengthOverLimitLeftUnread() throws IOException, HttpException {
      var answer = new BasicClassicHttpResponse(HttpStatus.SC_INTERNAL_SERVER_ERROR);
      try (var apacheStream = new FailingStream()) {
        answer.setEntity(new InputStreamEntity(apacheStream, LIMIT + 1L, null));
        try (answer;
            var entity = limited(answer);
            var body = entity.getContent()) {
          assertThatCode(body::close).doesNotThrowAnyException();
          assertThat(apacheStream.closed).isFalse();
        }
      }
    }

    @Test
    @DisplayName("once 64 KB are read, Apache's stream is left open without another read")
    void noReadPastLimit() throws IOException, HttpException {
      try (var apacheStream = new StallingStream();
          var answer = errorAnswer(apacheStream);
          var entity = limited(answer);
          var body = entity.getContent()) {
        assertThat(body.readAllBytes()).hasSize(LIMIT);

        assertThatCode(body::close).doesNotThrowAnyException();
        assertThat(apacheStream.closed).isFalse();
      }
    }
  }

  /** A body of zeros that remembers whether it was closed. */
  private static final class ClosingStream extends ByteArrayInputStream {

    /** Whether the body was closed. */
    private boolean closed;

    /**
     * Makes a body of the given size.
     *
     * @param size the size
     */
    private ClosingStream(int size) {
      super(new byte[size]);
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  /** A body of 64 KB that then stalls: a read past it fails, as at a read timeout. */
  private static final class StallingStream extends InputStream {

    /** The bytes still to send before the stall. */
    private int left = LIMIT;

    /** Whether the body was closed. */
    private boolean closed;

    @Override
    public int read() throws IOException {
      if (left <= 0) {
        throw new IOException("read timed out");
      }
      left--;
      return 0;
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  /** A body whose every read fails, as after a read timeout. */
  private static final class FailingStream extends InputStream {

    /** Whether the body was closed. */
    private boolean closed;

    @Override
    public int read() throws IOException {
      throw new IOException("read timed out");
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}
