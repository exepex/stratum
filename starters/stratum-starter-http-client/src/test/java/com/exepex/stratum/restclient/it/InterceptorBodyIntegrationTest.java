package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.restclient.StratumHttpClients;
import com.exepex.stratum.restclient.testsupport.EchoDownstream;
import com.exepex.stratum.restclient.testsupport.headers.CallLog;
import com.exepex.stratum.restclient.testsupport.headers.HeadersApplication;
import com.exepex.stratum.restclient.testsupport.headers.HeadersTestData;
import com.exepex.stratum.restclient.testsupport.headers.Note;
import com.exepex.stratum.restclient.testsupport.headers.SignedClient;
import com.exepex.stratum.restclient.testsupport.headers.SigningInterceptor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The signing interceptor reads the body when it can, and signs the call with its size, or {@code
 * none}. The server must always get the body once, whole.
 */
@SpringBootTest(classes = HeadersApplication.class)
@DisplayName("An interceptor reads a body only when it is not streamed, and the body is sent once")
class InterceptorBodyIntegrationTest {

  /** The order the JSON tests send, and the JSON it becomes. */
  private static final Map<String, String> ORDER = Map.of("id", "7");

  /** The JSON of {@link #ORDER}. */
  private static final String ORDER_JSON = "{\"id\":\"7\"}";

  /** The size of the files the upload tests send. */
  private static final int FILE_SIZE = 64 * 1024;

  /** How long the streaming test waits for the first half of the body to reach the server. */
  private static final Duration BODY_WAIT = Duration.ofSeconds(3);

  /** The server every client calls. */
  @AutoClose private static final EchoDownstream DOWNSTREAM = EchoDownstream.start();

  @Autowired private CallLog log;

  @Autowired private SignedClient signed;

  /**
   * Points the clients at the server.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    HeadersTestData.pointClientsAt(registry, DOWNSTREAM::url);
  }

  /** Forgets what earlier tests' calls recorded. */
  @BeforeEach
  void clearLog() {
    log.clear();
  }

  /** Returns the body the signing interceptor could read on the last call, or null. */
  private byte[] bodySigned() {
    return log.runs().stream()
        .filter(run -> SigningInterceptor.NAME.equals(run.interceptor()))
        .reduce((first, second) -> second)
        .orElseThrow()
        .body();
  }

  @Test
  @DisplayName("a JSON body is readable, and the server gets it once, whole")
  void jsonReadableAndSentOnce() {
    signed.send(ORDER);

    assertThat(bodySigned()).asString(StandardCharsets.UTF_8).isEqualTo(ORDER_JSON);
    assertThat(DOWNSTREAM.last().text()).isEqualTo(ORDER_JSON);
    assertThat(DOWNSTREAM.last().values(HeadersTestData.SIGNATURE))
        .containsExactly(String.valueOf(ORDER_JSON.length()));
  }

  @Test
  @DisplayName("a file is never read, and the server gets it whole")
  void fileNotRead(@TempDir Path folder) throws IOException {
    var file = Files.write(folder.resolve("upload.bin"), new byte[FILE_SIZE]);

    signed.upload(new FileSystemResource(file));

    assertThat(bodySigned()).isNull();
    assertThat(DOWNSTREAM.last().body()).hasSize(FILE_SIZE);
  }

  @Test
  @DisplayName("a file after a path variable is never read either, and the server gets it whole")
  void fileAfterPathVariableNotRead(@TempDir Path folder) throws IOException {
    var file = Files.write(folder.resolve("upload.bin"), new byte[FILE_SIZE]);

    signed.uploadTo("reports", new FileSystemResource(file));

    assertThat(bodySigned()).isNull();
    assertThat(DOWNSTREAM.last().body()).hasSize(FILE_SIZE);
  }

  @Test
  @DisplayName("a streamed body is written to the connection, never to a buffer in memory")
  void streamedBodyNotBuffered() {
    var arrivedWhileWriting = new AtomicBoolean();
    DOWNSTREAM.expectBodyBytes();

    signed.stream(output -> writeInTwoHalves(output, arrivedWhileWriting));

    assertThat(arrivedWhileWriting).isTrue();
    assertThat(DOWNSTREAM.last().body()).hasSize(FILE_SIZE);
  }

  /**
   * Writes half the body, notes whether the server already has bytes of it, then writes the rest. A
   * buffered body reaches the server only after the whole of it is written.
   */
  private void writeInTwoHalves(OutputStream output, AtomicBoolean arrivedWhileWriting)
      throws IOException {
    output.write(new byte[FILE_SIZE / 2]);
    output.flush();
    try {
      arrivedWhileWriting.set(DOWNSTREAM.awaitBodyBytes(BODY_WAIT));
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
    output.write(new byte[FILE_SIZE / 2]);
  }

  @Test
  @DisplayName("an InputStream is never read, and goes chunked, whole")
  void streamNotReadAndChunked() {
    signed.upload(new InputStreamResource(new ByteArrayInputStream(new byte[FILE_SIZE])));

    assertThat(bodySigned()).isNull();
    assertThat(DOWNSTREAM.last().transferEncoding()).isEqualTo("chunked");
    assertThat(DOWNSTREAM.last().body()).hasSize(FILE_SIZE);
  }

  @Test
  @DisplayName("a body a converter writes to the request's stream reaches the server once, whole")
  void converterWritingToStream() {
    signed.write(new Note("hello"));

    assertThat(DOWNSTREAM.last().text()).isEqualTo("hello");
  }

  @Test
  @DisplayName("on an ad-hoc call the body is not readable, and is sent")
  void adHocBodyNotReadable(@Autowired StratumHttpClients clients) {
    clients
        .restClient(HeadersTestData.SIGNED)
        .post()
        .uri(EchoDownstream.ECHO)
        .body(ORDER)
        .retrieve()
        .toBodilessEntity();

    assertThat(bodySigned()).isNull();
    assertThat(DOWNSTREAM.last().text()).isEqualTo(ORDER_JSON);
  }
}
