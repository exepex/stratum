package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.util.unit.DataSize;

@DisplayName(
    "LimitedBodyStream accepts a body up to its limit and refuses the write that passes it")
class LimitedBodyStreamTest {

  /** The limit of the stream under test. */
  private static final int LIMIT = 1024;

  /** The size of each write of the large body. */
  private static final int CHUNK = 8 * 1024;

  /** The size of the large body: 64 MiB. */
  private static final long LARGE_BODY = DataSize.parse("64MB").toBytes();

  /** The stream under test. */
  private final LimitedBodyStream stream = new LimitedBodyStream(LIMIT);

  @Nested
  @DisplayName("at its limit")
  class Limit {

    @Test
    @DisplayName("a body of exactly the limit is accepted whole")
    void atLimitAccepted() throws IOException {
      stream.write(new byte[LIMIT]);

      assertThat(stream.toByteArray()).hasSize(LIMIT);
    }

    @Test
    @DisplayName("one byte over the limit is refused, and nothing of that write is accepted")
    void overLimitRefused() throws IOException {
      stream.write(new byte[LIMIT]);

      var refusal = assertThatIOException().isThrownBy(() -> stream.write(0)).actual();

      assertThat(stream.isLimitReachedIn(refusal)).isTrue();
      assertThat(stream.toByteArray()).hasSize(LIMIT);
    }
  }

  @Nested
  @DisplayName("with a body far larger than its limit")
  class LargeBody {

    @Test
    @DisplayName(
        "a 64 MiB body stops at the limit: bytes accepted <= limit, and writing ends early")
    void largeBodyStopsEarly() {
      var written = new AtomicLong();

      assertThatIOException().isThrownBy(() -> writeLargeBody(written));

      assertThat(stream.toByteArray()).hasSizeLessThanOrEqualTo(LIMIT);
      assertThat(written).hasValueLessThanOrEqualTo(LIMIT + CHUNK);
    }

    /** Writes up to 64 MiB in chunks, counting the bytes handed to the stream. */
    private void writeLargeBody(AtomicLong written) throws IOException {
      var chunk = new byte[CHUNK];
      // A loop: each chunk is counted before it is written, until the stream refuses one.
      while (written.get() < LARGE_BODY) {
        written.addAndGet(CHUNK);
        stream.write(chunk);
      }
    }
  }

  @Nested
  @DisplayName("when it looks for its refusal")
  class Refusal {

    @Test
    @DisplayName("it finds it wrapped by a converter, as Jackson wraps it")
    void findsWrappedRefusal() throws IOException {
      stream.write(new byte[LIMIT]);
      var refusal = assertThatIOException().isThrownBy(() -> stream.write(0)).actual();

      var wrapped = new HttpMessageNotWritableException("could not write", refusal);

      assertThat(stream.isLimitReachedIn(wrapped)).isTrue();
    }

    @Test
    @DisplayName("another failure is not its refusal")
    void otherFailureIsNot() {
      assertThat(stream.isLimitReachedIn(new IOException("connection reset"))).isFalse();
    }
  }
}
