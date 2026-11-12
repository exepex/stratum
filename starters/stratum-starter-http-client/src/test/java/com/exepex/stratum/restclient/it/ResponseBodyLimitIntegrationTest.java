package com.exepex.stratum.restclient.it;

import static com.exepex.stratum.restclient.testsupport.answers.AnswerChecks.LIMIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.FailureCauses;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.restclient.ClientResilience;
import com.exepex.stratum.restclient.testsupport.ClientBeanCalls;
import com.exepex.stratum.restclient.testsupport.ClientBeanCalls.ClientBean;
import com.exepex.stratum.restclient.testsupport.LargeAnswerDownstream;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.answers.AnswerChecks;
import com.exepex.stratum.restclient.testsupport.answers.AnswersApplication;
import com.exepex.stratum.restclient.testsupport.answers.AnswersClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.IOException;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClientException;

@SpringBootTest(
    classes = AnswersApplication.class,
    properties = {
      AnswerChecks.LIMIT_SETTING,
      AnswerChecks.POOL_OF_ONE,
      AnswerChecks.SHORT_MAX_WAIT
    })
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
@DisplayName("A successful answer's body is read up to the client's response-body-limit")
class ResponseBodyLimitIntegrationTest {

  /** One byte over the limit. */
  private static final int OVER_LIMIT = LIMIT + 1;

  /** A size far over the limit, for a download and a gzip body. */
  private static final int FAR_OVER_LIMIT = 4 * LIMIT;

  /** The server the answers client calls. */
  @AutoClose private static final LargeAnswerDownstream DOWNSTREAM = LargeAnswerDownstream.start();

  @Autowired private AnswersClient answers;

  @Autowired private ClientResilience resilience;

  @Autowired private ApplicationContext beans;

  /**
   * Points the answers client at the server.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void address(DynamicPropertyRegistry registry) {
    registry.add(RestClientTestData.INVENTORY_URL, DOWNSTREAM::url);
  }

  @Nested
  @DisplayName("read by a client's interface")
  class Reads {

    @Test
    @DisplayName("a body of exactly the limit is read, and its connection used again")
    void bodyAtLimitRead() {
      var opened =
          AnswerChecks.connectionsOpenedAfter(
              answers, DOWNSTREAM, () -> assertThat(answers.sized(LIMIT)).hasSize(LIMIT));

      assertThat(opened).isZero();
    }

    @Test
    @DisplayName("a chunked body one byte over fails with SX-HTTPC-0006, and its connection drops")
    void chunkedBodyOverLimitFails() {
      var opened =
          AnswerChecks.connectionsOpenedAfter(
              answers,
              DOWNSTREAM,
              () -> AnswerChecks.assertTooLarge(() -> answers.chunked(OVER_LIMIT)));

      assertThat(opened).isOne();
    }

    @Test
    @DisplayName("a Content-Length over the limit fails before any byte comes, not at the timeout")
    void lengthOverLimitFailsAtOnce() {
      AnswerChecks.assertTooLarge(answers::stalledText);
    }

    @Test
    @DisplayName("a gzip body is counted once decoded")
    void gzipCountedDecoded() {
      AnswerChecks.assertTooLarge(() -> answers.gzipped(FAR_OVER_LIMIT));
    }

    @Test
    @DisplayName("a Resource is read whole, so it is limited")
    void resourceLimited() {
      AnswerChecks.assertTooLarge(() -> answers.resource(OVER_LIMIT));
    }

    @Test
    @DisplayName("an InputStream download is not limited")
    void downloadNotLimited() throws IOException {
      try (var download = answers.download(FAR_OVER_LIMIT)) {
        assertThat(download.readAllBytes()).hasSize(FAR_OVER_LIMIT);
      }
    }
  }

  @Nested
  @DisplayName("over the limit")
  class OverLimit {

    @Test
    @DisplayName("an idempotent GET is sent once, and the breaker counts it as a success")
    void notRetriedNorCounted() {
      var path = LargeAnswerDownstream.CHUNKED + OVER_LIMIT;
      var requestsBefore = DOWNSTREAM.requests(path);
      final var failedBefore = breaker().getMetrics().getNumberOfFailedCalls();
      final var succeededBefore = breaker().getMetrics().getNumberOfSuccessfulCalls();

      AnswerChecks.assertTooLarge(() -> answers.chunked(OVER_LIMIT));

      assertThat(DOWNSTREAM.requests(path)).isEqualTo(requestsBefore + 1);
      assertThat(breaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(failedBefore);
      assertThat(breaker().getMetrics().getNumberOfSuccessfulCalls())
          .isEqualTo(succeededBefore + 1);
    }

    @Test
    @DisplayName("the failure and its causes name the client and the limit, never a body byte")
    void noBodyInMessages() {
      var failure =
          assertThatExceptionOfType(StratumException.class)
              .isThrownBy(() -> answers.chunked(OVER_LIMIT))
              .actual();

      assertThat(failure.getReport().getDetail()).isEmpty();
      assertThat(FailureCauses.causesOf(failure))
          .allSatisfy(
              cause ->
                  assertThat(String.valueOf(cause.getMessage()))
                      .doesNotContain(LargeAnswerDownstream.MARKER));
      assertThat(failure.getCause())
          .hasMessage(
              "SX-HTTPC-0006: the answer to client inventory is larger than its"
                  + " response-body-limit of "
                  + LIMIT
                  + " bytes")
          .hasNoCause();
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource(ClientBeanCalls.BOTH_BEANS)
    @DisplayName("a bean's call is sent once and fails with Spring's exception, caused by 0006")
    void beanCallLimited(ClientBean bean) {
      var path = LargeAnswerDownstream.CHUNKED + OVER_LIMIT;
      var requestsBefore = DOWNSTREAM.requests(path);

      assertThatExceptionOfType(RestClientException.class)
          .isThrownBy(() -> ClientBeanCalls.get(beans, bean, RestClientTestData.INVENTORY, path))
          .satisfies(
              failure ->
                  assertThat(FailureCauses.causesOf(failure))
                      .anySatisfy(
                          cause -> assertThat(cause).hasMessageStartingWith("SX-HTTPC-0006")));
      assertThat(DOWNSTREAM.requests(path)).isEqualTo(requestsBefore + 1);
    }
  }

  /** Returns the answers client's breaker. */
  private CircuitBreaker breaker() {
    return resilience.circuitBreaker(RestClientTestData.INVENTORY).orElseThrow();
  }
}
