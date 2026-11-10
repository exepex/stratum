package com.exepex.stratum.restclient.it;

import static com.exepex.stratum.restclient.testsupport.answers.AnswerChecks.LIMIT;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.restclient.testsupport.ClientBeanCalls;
import com.exepex.stratum.restclient.testsupport.LargeAnswerDownstream;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.answers.AnswerChecks;
import com.exepex.stratum.restclient.testsupport.answers.AnswersApplication;
import com.exepex.stratum.restclient.testsupport.answers.AnswersClient;
import java.util.Map;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A body the call leaves unread, or reads only in part, is drained when the answer closes so that
 * its connection can be used again, but never past the limit: a longer one is dropped with its
 * connection, at once. The client has a pool of one, so that a connection held by a hung drain
 * would fail the next call.
 */
@SpringBootTest(
    classes = AnswersApplication.class,
    properties = {
      AnswerChecks.LIMIT_SETTING,
      AnswerChecks.POOL_OF_ONE,
      AnswerChecks.SHORT_MAX_WAIT
    })
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
@DisplayName("Closing an answer drains its body only up to the limit")
class ResponseBodyDrainIntegrationTest {

  /** The server the answers client calls. */
  @AutoClose private static final LargeAnswerDownstream DOWNSTREAM = LargeAnswerDownstream.start();

  @Autowired private AnswersClient answers;

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

  /** Returns the connections opened after a check's call, as {@link AnswerChecks} counts them. */
  private int connectionsOpenedAfter(Runnable check) {
    return AnswerChecks.connectionsOpenedAfter(answers, DOWNSTREAM, check);
  }

  @Nested
  @DisplayName("of a successful answer")
  class Successful {

    @Test
    @DisplayName("a method returning void on an endless body returns at once, dropping it")
    void voidOnEndlessBody() {
      assertThat(connectionsOpenedAfter(() -> AnswerChecks.assertQuick(answers::endless))).isOne();
    }

    @Test
    @DisplayName("a bean's toBodilessEntity on an endless body returns at once, dropping it")
    void bodilessBeanOnEndlessBody() {
      var restClient = ClientBeanCalls.restClient(beans, RestClientTestData.INVENTORY);

      assertThat(
              connectionsOpenedAfter(
                  () ->
                      AnswerChecks.assertQuick(
                          () ->
                              restClient
                                  .get()
                                  .uri(LargeAnswerDownstream.ENDLESS)
                                  .retrieve()
                                  .toBodilessEntity())))
          .isOne();
    }

    @Test
    @DisplayName("a method returning void on a Content-Length over the limit returns at once")
    void voidOnLengthOverLimit() {
      assertThat(connectionsOpenedAfter(() -> AnswerChecks.assertQuick(answers::stalled))).isOne();
    }

    @Test
    @DisplayName("a bean's toBodilessEntity on a Content-Length over the limit returns at once")
    void bodilessBeanOnLengthOverLimit() {
      var restClient = ClientBeanCalls.restClient(beans, RestClientTestData.INVENTORY);

      assertThat(
              connectionsOpenedAfter(
                  () ->
                      AnswerChecks.assertQuick(
                          () ->
                              restClient
                                  .get()
                                  .uri(LargeAnswerDownstream.STALLED)
                                  .retrieve()
                                  .toBodilessEntity())))
          .isOne();
    }

    @Test
    @DisplayName("a JSON value followed by endless bytes fails with SX-HTTPC-0006 at once")
    void valueWithEndlessTail() {
      assertThat(connectionsOpenedAfter(() -> AnswerChecks.assertTooLarge(answers::endlessValue)))
          .isOne();
    }

    @Test
    @DisplayName("a JSON value followed by bytes within the limit is returned, and reused")
    void valueWithTailWithinLimit() {
      assertThat(
              connectionsOpenedAfter(
                  () -> assertThat(answers.trailing(LIMIT / 2)).isEqualTo(Map.of("stock", 1))))
          .isZero();
    }
  }
}
