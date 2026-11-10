package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.DownstreamException;
import com.exepex.stratum.restclient.testsupport.CallChecks;
import com.exepex.stratum.restclient.testsupport.ClientBeanCalls;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.errors.ErrorDownstream;
import com.exepex.stratum.restclient.testsupport.errors.ErrorsApplication;
import com.exepex.stratum.restclient.testsupport.errors.OrdersClient;
import java.time.Duration;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpServerErrorException;

/**
 * The orders client has a pool of one and keeps its 5s timeout, far above {@link
 * RestClientTestData#FAILS_WITHIN}, so that a drain that reads past 64 KB, or waits for bytes that
 * never come, shows as a slow call or a held connection. A drain with no bound blocks in a socket
 * read, which only a timeout on a separate thread can end.
 */
@SpringBootTest(
    classes = ErrorsApplication.class,
    properties = {
      HttpClientConstants.CLIENTS + "." + RestClientTestData.ORDERS + ".max-connections=1",
      HttpClientConstants.CLIENTS + "." + RestClientTestData.ORDERS + ".max-wait=300ms"
    })
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
@DisplayName("Closing an error answer drains its body only up to 64 KB")
class ErrorBodyDrainIntegrationTest {

  /** A path value of a small error answer. */
  private static final String ORDER_ID = "o-1742";

  /** A query value of a small error answer. */
  private static final String CUSTOMER = "c-9931";

  /** The orders service. */
  @AutoClose private static final ErrorDownstream DOWNSTREAM = ErrorDownstream.start();

  @Autowired private OrdersClient orders;

  @Autowired private ApplicationContext beans;

  /**
   * Points the orders client at the server.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    registry.add(RestClientTestData.ORDERS_URL, DOWNSTREAM::url);
  }

  @Test
  @DisplayName(
      "an endless error body fails the call at once, and the next call has a new connection")
  void endlessErrorBody() {
    assertThat(connectionsOpenedAfter(() -> assertFailsQuickly(orders::endlessError))).isOne();
  }

  @Test
  @DisplayName("an error Content-Length over 64 KB fails the call at once, dropping its connection")
  void errorLengthOverLimit() {
    assertThat(connectionsOpenedAfter(() -> assertFailsQuickly(orders::stalledError))).isOne();
  }

  @Test
  @DisplayName("a bean's call on an endless error body fails at once, dropping its connection")
  void endlessErrorBodyOnBean() {
    var restClient = ClientBeanCalls.restClient(beans, RestClientTestData.ORDERS);
    var started = System.nanoTime();

    var opened =
        connectionsOpenedAfter(
            () ->
                assertThatExceptionOfType(HttpServerErrorException.class)
                    .isThrownBy(
                        () ->
                            restClient
                                .get()
                                .uri(ErrorDownstream.ENDLESS_ERROR)
                                .retrieve()
                                .body(String.class)));

    assertThat(Duration.ofNanos(System.nanoTime() - started))
        .isLessThan(RestClientTestData.FAILS_WITHIN);
    assertThat(opened).isOne();
  }

  @Test
  @DisplayName("a small error body is read, and its connection used again")
  void smallErrorBodyReused() {
    assertThat(
            connectionsOpenedAfter(
                () ->
                    assertThatExceptionOfType(DownstreamException.class)
                        .isThrownBy(() -> orders.order(ORDER_ID, CUSTOMER))))
        .isZero();
  }

  /** Checks that a call fails with a {@link DownstreamException} well before the timeout. */
  private void assertFailsQuickly(ThrowingCallable call) {
    var started = System.nanoTime();

    assertThatExceptionOfType(DownstreamException.class).isThrownBy(call);
    assertThat(Duration.ofNanos(System.nanoTime() - started))
        .isLessThan(RestClientTestData.FAILS_WITHIN);
  }

  /** Returns the connections opened after a check's call, as {@link CallChecks} counts them. */
  private int connectionsOpenedAfter(Runnable check) {
    return CallChecks.connectionsOpenedAfter(DOWNSTREAM::connections, this::smallError, check);
  }

  /** Makes a call whose error body is small, which keeps its connection. */
  private void smallError() {
    assertThatExceptionOfType(DownstreamException.class)
        .isThrownBy(() -> orders.order(ORDER_ID, CUSTOMER));
  }
}
