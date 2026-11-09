package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.util.LinkedMultiValueMap;

@DisplayName("A body written while it is sent is streamed, so it is never sent twice")
class StreamedBodyTest {

  /** A text body small enough for every test. */
  private static final String TEXT = "order";

  /** The same body as bytes. */
  private static final byte[] BYTES = TEXT.getBytes(StandardCharsets.UTF_8);

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
  @DisplayName("a multipart body with a streamed part, bare or in an HttpEntity, is streamed")
  void multipartWithStreamedPart() {
    var bare = new LinkedMultiValueMap<String, Object>();
    bare.add("note", TEXT);
    bare.add("file", new InputStreamResource(new ByteArrayInputStream(BYTES)));
    var wrapped = new LinkedMultiValueMap<String, Object>();
    wrapped.add("file", new HttpEntity<>(new InputStreamResource(new ByteArrayInputStream(BYTES))));

    assertThat(StreamedBody.isStreamed(bare)).isTrue();
    assertThat(StreamedBody.isStreamed(wrapped)).isTrue();
  }

  @Test
  @DisplayName("a multipart body of text parts is not streamed")
  void multipartOfTextNotStreamed() {
    var parts = new LinkedMultiValueMap<String, Object>();
    parts.add("note", TEXT);
    parts.add("sku", new HttpEntity<>("A1"));

    assertThat(StreamedBody.isStreamed(parts)).isFalse();
  }

  @Test
  @DisplayName("no body, text, bytes and objects are not streamed")
  void notStreamed() {
    assertThat(StreamedBody.isStreamed(null)).isFalse();
    assertThat(StreamedBody.isStreamed(TEXT)).isFalse();
    assertThat(StreamedBody.isStreamed(BYTES)).isFalse();
    assertThat(StreamedBody.isStreamed(Map.of("sku", "A1"))).isFalse();
  }
}
