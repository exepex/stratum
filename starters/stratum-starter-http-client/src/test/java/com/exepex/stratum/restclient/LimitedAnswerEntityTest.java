package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("A successful answer's body fails on the byte that passes its limit")
class LimitedAnswerEntityTest {

  /** The limit of every body under test. */
  private static final int LIMIT = 10;

  /** The client named in the failure. */
  private static final String CLIENT = "inventory";

  /** Returns a body of the given size whose length is unknown, as a chunked one. */
  private InputStreamEntity chunked(int size) {
    return new InputStreamEntity(new ByteArrayInputStream(new byte[size]), -1, null);
  }

  @Test
  @DisplayName("is read byte by byte up to the limit, and fails on the byte past it")
  void singleBytesCounted() throws IOException {
    try (var body =
        LimitedAnswerEntity.failingPast(chunked(LIMIT + 1), LIMIT, CLIENT).getContent()) {
      for (var read = 0; read < LIMIT; read++) {
        assertThat(body.read()).isZero();
      }
      assertThatExceptionOfType(ResponseBodyTooLarge.class).isThrownBy(body::read);
      assertThatExceptionOfType(ResponseBodyTooLarge.class).isThrownBy(body::read);
    }
  }

  @Test
  @DisplayName("counts skipped bytes")
  void skippedBytesCounted() throws IOException {
    try (var body =
        LimitedAnswerEntity.failingPast(chunked(LIMIT + 1), LIMIT, CLIENT).getContent()) {
      assertThat(body.skip(LIMIT)).isEqualTo(LIMIT);
      assertThat(body.available()).isNotNegative();
      assertThatExceptionOfType(ResponseBodyTooLarge.class).isThrownBy(() -> body.skip(1));
    }
  }

  @Test
  @DisplayName("is written through the count, and its length is kept")
  void writtenThroughCount() throws IOException {
    try (var entity =
        LimitedAnswerEntity.failingPast(
            new ByteArrayEntity(new byte[LIMIT + 1], null), LIMIT, CLIENT)) {
      assertThat(entity.getContentLength()).isEqualTo(LIMIT + 1);
      assertThatExceptionOfType(ResponseBodyTooLarge.class)
          .isThrownBy(() -> entity.writeTo(new ByteArrayOutputStream()));
    }
  }
}
