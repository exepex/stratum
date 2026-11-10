package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.restclient.StratumHttpClients;
import com.exepex.stratum.restclient.testsupport.CallChecks;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.StreamingDownstream;
import com.exepex.stratum.restclient.testsupport.files.FilesApplication;
import com.exepex.stratum.restclient.testsupport.files.FilesClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;

@SpringBootTest(
    classes = FilesApplication.class,
    properties = {
      RestClientTestData.FILES_SETTINGS + ".timeout=500ms",
      RestClientTestData.FILES_SETTINGS + ".max-connections=1",
      RestClientTestData.FILES_SETTINGS + ".max-wait=300ms"
    })
@Timeout(30)
@DisplayName("A client streams downloads and uploads instead of holding them in memory")
class StreamingIntegrationTest {

  /** The size of an upload, large enough that a buffered body would show. */
  private static final int UPLOAD_SIZE = 5_000_000;

  /** The client's {@code timeout}, below the whole time of a slow answer. */
  private static final Duration TIMEOUT = Duration.ofMillis(500);

  /** The server the files client calls. */
  @AutoClose private static final StreamingDownstream DOWNSTREAM = StreamingDownstream.start();

  @Autowired private FilesClient filesClient;

  @Autowired private StratumHttpClients stratumHttpClients;

  /**
   * Points the files client at the server.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void address(DynamicPropertyRegistry registry) {
    registry.add(RestClientTestData.FILES_SETTINGS + ".url", DOWNSTREAM::url);
  }

  @Nested
  @DisplayName("a download")
  class Downloads {

    @Test
    @DisplayName("holds its connection until it is closed, then gives it back")
    void holdsConnectionUntilClosed() throws IOException {
      try (var download = filesClient.download()) {
        CallChecks.assertFailsFast(
            () -> filesClient.download().close(), ConnectionRequestTimeoutException.class);
        assertThat(text(download)).isEqualTo(StreamingDownstream.SLOW_BODY);
      }

      try (var next = filesClient.download()) {
        assertThat(text(next)).isEqualTo(StreamingDownstream.SLOW_BODY);
      }
    }

    @Test
    @DisplayName("as ResponseEntity<InputStream> holds its connection until it is closed")
    void entityHoldsConnectionUntilClosed() throws IOException {
      try (var download = filesClient.downloadWithHeaders().getBody()) {
        CallChecks.assertFailsFast(
            () -> filesClient.download().close(), ConnectionRequestTimeoutException.class);
        assertThat(text(download)).isEqualTo(StreamingDownstream.SLOW_BODY);
      }

      try (var next = filesClient.download()) {
        assertThat(text(next)).isEqualTo(StreamingDownstream.SLOW_BODY);
      }
    }

    @Test
    @DisplayName("is read in full when it takes longer than timeout, as each byte comes in time")
    void timeoutIsTheGapBetweenBytes() throws IOException {
      var startedAt = System.nanoTime();

      try (var download = filesClient.download()) {
        assertThat(text(download)).isEqualTo(StreamingDownstream.SLOW_BODY);
      }
      assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isGreaterThan(TIMEOUT);
    }

    @Test
    @DisplayName("fails when the answer stops for longer than timeout")
    void stallFailsTheRead() throws IOException {
      var startedAt = System.nanoTime();

      try (var download = filesClient.downloadStalled()) {
        assertThatExceptionOfType(SocketTimeoutException.class).isThrownBy(download::readAllBytes);
      }
      assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
          .isLessThan(RestClientTestData.FAILS_WITHIN);
    }
  }

  @Test
  @DisplayName("an answer read as text takes longer than timeout and is read in full")
  void textAnswerTimeoutIsTheGapBetweenBytes() {
    var startedAt = System.nanoTime();

    assertThat(filesClient.downloadText()).isEqualTo(StreamingDownstream.SLOW_BODY);
    assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isGreaterThan(TIMEOUT);
  }

  @Nested
  @DisplayName("an upload is sent chunked, never held in memory")
  class Uploads {

    @Test
    @DisplayName("of a body the caller writes")
    void writtenBodyIsStreamed() {
      assertThat(filesClient.upload(StreamingIntegrationTest.this::writeUpload)).isEqualTo("ok");

      assertStreamed();
    }

    @Test
    @DisplayName("of an InputStreamResource")
    void resourceIsStreamed() {
      assertThat(filesClient.upload(new InputStreamResource(uploadStream()))).isEqualTo("ok");

      assertStreamed();
    }

    @Test
    @DisplayName("through stratumHttpClients.restClient(name)")
    void restClientOfClientStreams() {
      var answer =
          stratumHttpClients
              .restClient(RestClientTestData.FILES)
              .post()
              .uri(StreamingDownstream.UPLOAD)
              .body(StreamingIntegrationTest.this::writeUpload)
              .retrieve()
              .body(String.class);

      assertThat(answer).isEqualTo("ok");
      assertStreamed();
    }

    @Test
    @DisplayName("through the client's RestTemplate bean")
    void restTemplateBeanStreams(
        @Autowired @Qualifier(RestClientTestData.FILES) RestTemplate files) {
      var answer =
          files.execute(
              StreamingDownstream.UPLOAD,
              HttpMethod.POST,
              request ->
                  ((StreamingHttpOutputMessage) request).setBody(this::writeWhileServerReads),
              response -> new String(response.getBody().readAllBytes(), StandardCharsets.US_ASCII));

      assertThat(answer).isEqualTo("ok");
      assertStreamed();
    }

    /**
     * Writes half the upload, waits until the server has received some of it, which a body held in
     * memory until it is written whole never lets happen, then writes the rest.
     */
    private void writeWhileServerReads(OutputStream body) throws IOException {
      var receivedBefore = DOWNSTREAM.receivedBytes();
      body.write(new byte[UPLOAD_SIZE / 2]);
      body.flush();
      await()
          .atMost(RestClientTestData.WAIT)
          .until(() -> DOWNSTREAM.receivedBytes() > receivedBefore);
      body.write(new byte[UPLOAD_SIZE - UPLOAD_SIZE / 2]);
    }

    /** Checks that the last upload came chunked, with no length, and in full. */
    private void assertStreamed() {
      assertThat(DOWNSTREAM.lastUpload())
          .isEqualTo(
              StreamingDownstream.Upload.builder()
                  .transferEncoding("chunked")
                  .size(UPLOAD_SIZE)
                  .build());
    }
  }

  /** Reads a download to its end, as text. */
  private String text(InputStream download) throws IOException {
    return new String(download.readAllBytes(), StandardCharsets.US_ASCII);
  }

  /** Writes the upload into the request body. */
  private void writeUpload(OutputStream body) throws IOException {
    uploadStream().transferTo(body);
  }

  /** Returns a fresh stream of {@link #UPLOAD_SIZE} bytes. */
  private InputStream uploadStream() {
    return new ByteArrayInputStream(new byte[UPLOAD_SIZE]);
  }
}
