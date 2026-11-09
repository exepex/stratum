package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.StreamingHttpOutputMessage;

@DisplayName("A body written while it is sent is streamed, so it is never sent twice")
class StreamedBodyTest {

  /** A body small enough for every test. */
  private static final byte[] BYTES = "order".getBytes(StandardCharsets.UTF_8);

  @Test
  @DisplayName("a streaming body and any resource are streamed")
  void streamed() {
    StreamingHttpOutputMessage.Body streaming = out -> out.write(BYTES);

    assertThat(StreamedBody.isStreamed(streaming)).isTrue();
    assertThat(StreamedBody.isStreamed(new InputStreamResource(new ByteArrayInputStream(BYTES))))
        .isTrue();
    assertThat(StreamedBody.isStreamed(new ByteArrayResource(BYTES))).isTrue();
  }

  @Test
  @DisplayName("no body, text, bytes and objects are not streamed")
  void notStreamed() {
    assertThat(StreamedBody.isStreamed(null)).isFalse();
    assertThat(StreamedBody.isStreamed("order")).isFalse();
    assertThat(StreamedBody.isStreamed(BYTES)).isFalse();
    assertThat(StreamedBody.isStreamed(Map.of("sku", "A1"))).isFalse();
  }
}
