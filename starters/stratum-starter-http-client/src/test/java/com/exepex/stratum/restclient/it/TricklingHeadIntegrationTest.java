package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.httpclient.HttpClientErrorCode;
import com.exepex.stratum.restclient.testsupport.CallChecks;
import com.exepex.stratum.restclient.testsupport.ClientBeanCalls;
import com.exepex.stratum.restclient.testsupport.ClientBeanCalls.ClientBean;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.TricklingHeadDownstream;
import com.exepex.stratum.restclient.testsupport.files.FilesApplication;
import com.exepex.stratum.restclient.testsupport.files.FilesClient;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
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
    "total-timeout ends an answer whose status line and headers keep trickling in, except a"
        + " streamed download")
class TricklingHeadIntegrationTest {

  /** The files client's total-timeout. */
  private static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(1);

  /** The latest a cut call may end: well under the 2s a trickled head takes to arrive whole. */
  private static final Duration CUT_BY = RestClientTestData.FAILS_WITHIN;

  /**
   * The latest a call whose status line comes late, then stops, may end: under the 1.8s it takes
   * when its last read keeps the 1s socket timeout set when the attempt began.
   */
  private static final Duration STALL_CUT_BY = Duration.ofMillis(1300);

  /** The server the files client calls, which writes each answer's head itself. */
  @AutoClose
  private static final TricklingHeadDownstream DOWNSTREAM = TricklingHeadDownstream.start();

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

  @Test
  @DisplayName("a head sent a byte at a time is cut at total-timeout with SX-HTTPC-0001")
  void trickledHeadIsCut() {
    var startedAt = System.nanoTime();

    CallChecks.assertEndsWith(filesClient::trickledHead, HttpClientErrorCode.DOWNSTREAM_TIMEOUT)
        .withRootCauseInstanceOf(SocketTimeoutException.class);
    assertEndedWithin(startedAt, CUT_BY);
  }

  @Test
  @DisplayName("the cut call's connection is dropped, and the next call opens a new one")
  void cutConnectionDropped() {
    assertThat(filesClient.headOk()).isEqualTo(TricklingHeadDownstream.BODY);
    var openedBefore = DOWNSTREAM.openedConnections();

    assertThatThrownBy(filesClient::trickledHead).isInstanceOf(RuntimeException.class);

    assertThat(filesClient.headOk()).isEqualTo(TricklingHeadDownstream.BODY);
    assertThat(DOWNSTREAM.openedConnections()).isEqualTo(openedBefore + 1);
  }

  @ParameterizedTest
  @FieldSource(ClientBeanCalls.BOTH_BEANS)
  @DisplayName("a head sent a byte at a time to a client's bean is cut at total-timeout")
  void beanHeadIsCut(ClientBean bean) {
    var startedAt = System.nanoTime();

    assertThatThrownBy(
            () ->
                ClientBeanCalls.get(
                    context, bean, RestClientTestData.FILES, TricklingHeadDownstream.TRICKLED_HEAD))
        .isInstanceOf(RestClientException.class)
        .hasRootCauseInstanceOf(SocketTimeoutException.class);
    assertEndedWithin(startedAt, CUT_BY);
  }

  @Test
  @DisplayName(
      "a status line that comes late, then stops, is cut at total-timeout, not a read later")
  void lateStatusIsCutAtTotalTimeout() {
    var startedAt = System.nanoTime();

    CallChecks.assertEndsWith(filesClient::stalledHead, HttpClientErrorCode.DOWNSTREAM_TIMEOUT)
        .withRootCauseInstanceOf(SocketTimeoutException.class);
    assertEndedWithin(startedAt, STALL_CUT_BY);
  }

  @Test
  @DisplayName("a line of junk before the status line is skipped, as Apache's own parser does")
  void junkLineSkipped() {
    assertThat(filesClient.junkFirst()).isEqualTo(TricklingHeadDownstream.BODY);
  }

  @Test
  @DisplayName(
      "a streamed download keeps its timeout: its trickled head arrives past total-timeout")
  void streamedDownloadHeadNotCut() throws IOException {
    var startedAt = System.nanoTime();

    try (var download = filesClient.trickledHeadDownload()) {
      assertThat(new String(download.readAllBytes(), StandardCharsets.US_ASCII))
          .isEqualTo(TricklingHeadDownstream.BODY);
    }
    assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isGreaterThan(TOTAL_TIMEOUT);
  }

  /** Checks that a call started at the given {@link System#nanoTime()} ended within the limit. */
  private void assertEndedWithin(long startedAt, Duration limit) {
    assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(limit);
  }
}
