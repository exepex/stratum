package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientErrorCode;
import com.exepex.stratum.restclient.DownstreamException;
import com.exepex.stratum.restclient.testsupport.CallChecks;
import com.exepex.stratum.restclient.testsupport.timed.ReportsClient;
import com.exepex.stratum.restclient.testsupport.timed.SlowDownstream;
import com.exepex.stratum.restclient.testsupport.timed.TimedApplication;
import com.exepex.stratum.restclient.testsupport.timed.TimedTestData;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@SpringBootTest(classes = TimedApplication.class)
@TestPropertySource(locations = TimedTestData.SETTINGS)
@Timeout(30)
@DisplayName("A call of an operation takes the operation's timeouts, else the client's")
class OperationTimeoutIntegrationTest {

  /** The reports service. */
  @AutoClose private static final SlowDownstream REPORTS = SlowDownstream.start();

  @Autowired private ReportsClient reports;

  @Autowired
  @Qualifier(TimedTestData.REPORTS)
  private RestClient reportsBean;

  @Autowired private MeterRegistry registry;

  /**
   * Points the reports client at the server.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    registry.add(TimedTestData.REPORTS_URL, REPORTS::url);
  }

  /** Makes the server answer at once again. */
  @BeforeEach
  void answerAtOnce() {
    REPORTS.answerAfter(Duration.ZERO);
  }

  /** Returns the whole body of a successful answer. */
  private String wholeBody() {
    return SlowDownstream.FIRST_PART + SlowDownstream.SECOND_PART;
  }

  @Nested
  @DisplayName("an operation with longer timeouts than its client's")
  class LongerTimeouts {

    @Test
    @DisplayName("gets a slow answer that the client's own timeouts would cut off")
    void slowAnswerArrives() {
      REPORTS.answerAfter(TimedTestData.SLOW_ANSWER);

      assertThat(reports.monthlyReport()).isEqualTo(wholeBody());
    }

    @Test
    @DisplayName("leaves a method with no operation on the client's read-timeout")
    void plainMethodKeepsClientTimeout() {
      REPORTS.answerAfter(TimedTestData.SLOW_ANSWER);

      CallChecks.assertEndsWith(reports::plain, HttpClientErrorCode.DOWNSTREAM_TIMEOUT);
    }

    @Test
    @DisplayName("leaves a call through the client's RestClient bean on the client's read-timeout")
    void beanKeepsClientTimeout() {
      REPORTS.answerAfter(TimedTestData.SLOW_ANSWER);

      assertThatExceptionOfType(ResourceAccessException.class)
          .isThrownBy(
              () -> reportsBean.get().uri(TimedTestData.REPORT_PATH).retrieve().body(String.class))
          .withRootCauseInstanceOf(SocketTimeoutException.class);
    }

    @Test
    @DisplayName("keeps the meter's tags client and outcome only, with no operation tag")
    void meterHasNoOperationTag() {
      reports.monthlyReport();

      assertThat(registry.find(HttpClientConstants.CALLS_METER).meters())
          .isNotEmpty()
          .map(Meter::getId)
          .allSatisfy(
              id ->
                  assertThat(id.getTags())
                      .extracting(Tag::getKey)
                      .containsExactlyInAnyOrder(HttpClientConstants.CLIENT_TAG, "outcome"));
    }
  }

  @Nested
  @DisplayName("an operation with a shorter read-timeout than its client's")
  class ShorterTimeout {

    @Test
    @DisplayName("fails with SX-HTTPC-0001 on an answer the client's own read-timeout allows")
    void fairlyFastAnswerTimesOut() {
      REPORTS.answerAfter(TimedTestData.FAIRLY_FAST_ANSWER);

      CallChecks.assertEndsWith(reports::quickCheck, HttpClientErrorCode.DOWNSTREAM_TIMEOUT);
      assertThat(reports.plain()).isEqualTo(wholeBody());
    }
  }

  @Nested
  @DisplayName("a streamed download of an operation")
  class StreamedDownload {

    @Test
    @DisplayName("waits between bytes for the operation's read-timeout, not the client's")
    void keepsOperationReadTimeout() throws Exception {
      REPORTS.pauseBody(TimedTestData.SLOW_ANSWER);

      try (InputStream body = reports.export()) {
        assertThat(new String(body.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(wholeBody());
      }
    }
  }

  @Nested
  @TestPropertySource(
      properties = {
        TimedTestData.REPORTS_SETTINGS + ".retry.max-retries=" + RetriesWithinTotal.RETRIES,
        TimedTestData.REPORTS_SETTINGS + ".retry.backoff-policy=fixed",
        TimedTestData.REPORTS_SETTINGS + ".retry.delay=10ms"
      })
  @DisplayName("the retries of an operation")
  class RetriesWithinTotal {

    /** The retries the client may make. */
    static final int RETRIES = 9;

    /** How long each answer of 503 takes. */
    private static final Duration ANSWER_TIME = Duration.ofMillis(100);

    @Test
    @DisplayName("go on until the operation's total-timeout, longer than the client's")
    void retriesUntilOperationTotal() {
      REPORTS.answerAfter(ANSWER_TIME);
      REPORTS.answerWith(HttpStatus.SERVICE_UNAVAILABLE.value());

      assertThat(catchThrowable(reports::monthlyReport)).isInstanceOf(DownstreamException.class);

      assertThat(REPORTS.requests()).isEqualTo(RETRIES + 1);
    }

    @Test
    @DisplayName("of a method with no operation stop at the client's total-timeout")
    void plainMethodStopsAtClientTotal() {
      REPORTS.answerAfter(ANSWER_TIME);
      REPORTS.answerWith(HttpStatus.SERVICE_UNAVAILABLE.value());

      assertThat(catchThrowable(reports::plain)).isNotNull();

      assertThat(REPORTS.requests()).isLessThan(RETRIES + 1);
    }
  }
}
