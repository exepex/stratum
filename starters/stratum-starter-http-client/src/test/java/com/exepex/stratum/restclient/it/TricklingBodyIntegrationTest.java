package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.httpclient.HttpClientErrorCode;
import com.exepex.stratum.restclient.testsupport.CallChecks;
import com.exepex.stratum.restclient.testsupport.ClientBeanCalls;
import com.exepex.stratum.restclient.testsupport.ClientBeanCalls.ClientBean;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.StreamingDownstream;
import com.exepex.stratum.restclient.testsupport.files.FilesApplication;
import com.exepex.stratum.restclient.testsupport.files.FilesClient;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClientException;

@SpringBootTest(
    classes = FilesApplication.class,
    properties = {
      RestClientTestData.FILES_SETTINGS + ".timeout=5s",
      RestClientTestData.FILES_SETTINGS + ".total-timeout=1s",
      RestClientTestData.FILES_SETTINGS + ".max-connections=1",
      RestClientTestData.FILES_SETTINGS + ".max-wait=300ms"
    })
@Timeout(30)
@DisplayName(
    "total-timeout ends an answer whose body keeps trickling in, except a streamed download")
class TricklingBodyIntegrationTest {

  /** The files client's total-timeout. */
  private static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(1);

  /** The latest a cut call may end: well under the 4s a trickle takes to arrive whole. */
  private static final Duration CUT_BY = RestClientTestData.FAILS_WITHIN;

  /**
   * The latest a call whose answer stops just before total-timeout may end: under the 1.8s it takes
   * when its last read keeps the 1s socket timeout set when the attempt began.
   */
  private static final Duration STALL_CUT_BY = Duration.ofMillis(1300);

  /** The server the files client calls. */
  @AutoClose private static final StreamingDownstream DOWNSTREAM = StreamingDownstream.start();

  @Autowired private FilesClient filesClient;

  @Autowired private ApplicationContext context;

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
  @DisplayName("a body that is read")
  class BodyRead {

    @Test
    @DisplayName("by an interface as text is cut at total-timeout with SX-HTTPC-0001")
    void interfaceTextIsCut() {
      var startedAt = System.nanoTime();

      CallChecks.assertEndsWith(filesClient::trickleText, HttpClientErrorCode.DOWNSTREAM_TIMEOUT)
          .withRootCauseInstanceOf(SocketTimeoutException.class);
      assertEndedWithin(startedAt, CUT_BY);
      assertNextCallWorks();
    }

    @Test
    @DisplayName("by an interface as a Resource is cut at total-timeout with SX-HTTPC-0001")
    void interfaceResourceIsCut() {
      var startedAt = System.nanoTime();

      CallChecks.assertEndsWith(
              filesClient::trickleResource, HttpClientErrorCode.DOWNSTREAM_TIMEOUT)
          .withRootCauseInstanceOf(SocketTimeoutException.class);
      assertEndedWithin(startedAt, CUT_BY);
    }

    @ParameterizedTest
    @FieldSource(ClientBeanCalls.BOTH_BEANS)
    @DisplayName("by a client's bean, after its call, is cut at total-timeout")
    void beanBodyIsCut(ClientBean bean) {
      var startedAt = System.nanoTime();

      assertThatThrownBy(
              () ->
                  ClientBeanCalls.get(
                      context, bean, RestClientTestData.FILES, StreamingDownstream.TRICKLE))
          .isInstanceOf(RestClientException.class)
          .hasRootCauseInstanceOf(SocketTimeoutException.class);
      assertEndedWithin(startedAt, CUT_BY);
    }

    @Test
    @DisplayName("that stops just before total-timeout is cut at total-timeout, not a read later")
    void lateStallIsCutAtTotalTimeout() {
      var startedAt = System.nanoTime();

      CallChecks.assertEndsWith(filesClient::lateStallText, HttpClientErrorCode.DOWNSTREAM_TIMEOUT)
          .withRootCauseInstanceOf(SocketTimeoutException.class);
      assertEndedWithin(startedAt, STALL_CUT_BY);
    }
  }

  @Nested
  @DisplayName("a body that is not read")
  class BodyNotRead {

    @Test
    @DisplayName("by an interface method that returns void is dropped at total-timeout")
    void interfaceVoidEndsAtTotalTimeout() {
      var startedAt = System.nanoTime();

      filesClient.trickleWithoutBody();
      assertEndedWithin(startedAt, CUT_BY);
      assertNextCallWorks();
    }

    @ParameterizedTest
    @FieldSource(ClientBeanCalls.BOTH_BEANS)
    @DisplayName("by a client's bean is dropped at total-timeout, and the call keeps its status")
    void beanWithoutBodyEndsAtTotalTimeout(ClientBean bean) {
      var startedAt = System.nanoTime();

      assertThat(
              ClientBeanCalls.getWithoutBody(
                  context, bean, RestClientTestData.FILES, StreamingDownstream.TRICKLE))
          .isEqualTo(HttpStatus.OK);
      assertEndedWithin(startedAt, CUT_BY);
      assertNextCallWorks();
    }
  }

  @Nested
  @DisplayName("a streamed download")
  class StreamedDownload {

    @Test
    @DisplayName("is read in full, past total-timeout")
    void streamedDownloadIsNotCut() throws IOException {
      var startedAt = System.nanoTime();

      try (var download = filesClient.trickleDownload()) {
        assertThat(download.readAllBytes()).hasSize(StreamingDownstream.TRICKLE_BYTES);
      }
      assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isGreaterThan(TOTAL_TIMEOUT);
    }
  }

  /** Checks that a call started at the given {@link System#nanoTime()} ended within the limit. */
  private void assertEndedWithin(long startedAt, Duration limit) {
    assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(limit);
  }

  /**
   * Checks that the next call on the pool's one connection gets its own answer, so the cut call
   * neither kept the connection nor left the rest of its body on it.
   */
  private void assertNextCallWorks() {
    assertThat(filesClient.upload(body -> body.write('x'))).isEqualTo("ok");
  }
}
