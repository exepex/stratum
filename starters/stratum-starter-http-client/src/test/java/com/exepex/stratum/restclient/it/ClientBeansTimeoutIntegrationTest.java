package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.ClientConnectionPools;
import com.exepex.stratum.restclient.StratumHttpClients;
import com.exepex.stratum.restclient.testsupport.CallChecks;
import com.exepex.stratum.restclient.testsupport.ClientBeanCalls;
import com.exepex.stratum.restclient.testsupport.ClientBeanCalls.ClientBean;
import com.exepex.stratum.restclient.testsupport.QueuedDownstream;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import com.exepex.stratum.restclient.testsupport.StreamingDownstream;
import com.exepex.stratum.restclient.testsupport.clients.ClientsApplication;
import com.exepex.stratum.restclient.testsupport.clients.FlakyInventory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/**
 * Each client of this test shows one timeout through its beans: held a pool of one with a short
 * total-timeout, hung a second attempt with less time left than its timeout, stalled a body read
 * with a total-timeout far below its timeout, and slow its own timeout next to Spring Boot's;
 * inventory has a pool of one and a server whose next error may have a large body, for the wait
 * between attempts.
 */
@SpringBootTest(
    classes = ClientsApplication.class,
    properties = {
      ClientBeansTimeoutIntegrationTest.HELD_SETTINGS + ".max-connections=1",
      ClientBeansTimeoutIntegrationTest.HELD_SETTINGS + ".max-wait=5s",
      ClientBeansTimeoutIntegrationTest.HELD_SETTINGS + ".total-timeout=1s",
      ClientBeansTimeoutIntegrationTest.HUNG_SETTINGS + ".timeout=1s",
      ClientBeansTimeoutIntegrationTest.HUNG_SETTINGS + ".total-timeout=1500ms",
      ClientBeansTimeoutIntegrationTest.HUNG_SETTINGS + ".retry.delay=100ms",
      ClientBeansTimeoutIntegrationTest.STALLED_SETTINGS + ".total-timeout=300ms",
      ClientBeansTimeoutIntegrationTest.SLOW_SETTINGS + ".retry.max-retries=0",
      ClientBeansTimeoutIntegrationTest.SLOW_SETTINGS + ".timeout=600ms",
      RestClientTestData.INVENTORY_SETTINGS + ".max-connections=1",
      RestClientTestData.INVENTORY_SETTINGS + ".max-wait=300ms",
      RestClientTestData.INVENTORY_SETTINGS + ".retry.max-retries=1",
      "spring.http.clients.read-timeout=100ms",
      "spring.http.clients.connect-timeout=100ms"
    })
@Timeout(60)
@DisplayName("A client's beans end each call by its total-timeout, whatever Spring Boot's own keys")
class ClientBeansTimeoutIntegrationTest {

  /** A client with a pool of one connection and a short total-timeout. */
  static final String HELD = "held";

  /** A client whose second attempt has less time left than its timeout. */
  static final String HUNG = "hung";

  /** A client whose total-timeout is far below its timeout. */
  static final String STALLED = "stalled";

  /** A client with a short timeout and no retries. */
  static final String SLOW = "slow";

  /** The settings of the held client. */
  static final String HELD_SETTINGS = HttpClientConstants.CLIENTS + "." + HELD;

  /** The settings of the hung client. */
  static final String HUNG_SETTINGS = HttpClientConstants.CLIENTS + "." + HUNG;

  /** The settings of the stalled client. */
  static final String STALLED_SETTINGS = HttpClientConstants.CLIENTS + "." + STALLED;

  /** The settings of the slow client. */
  static final String SLOW_SETTINGS = HttpClientConstants.CLIENTS + "." + SLOW;

  /**
   * The earliest end of a hung call: its first attempt's whole timeout of 1s, a wait and part of a
   * second attempt.
   */
  private static final Duration HUNG_ENDS_AFTER = Duration.ofMillis(1400);

  /**
   * The latest end of a hung call: its total-timeout of 1.5s and some slack, well below the 2.1s a
   * second attempt with its whole timeout would take.
   */
  private static final Duration HUNG_ENDS_BEFORE = Duration.ofMillis(1800);

  /** The earliest end of the slow client's call: near its own 600ms, far above Boot's 100ms. */
  private static final Duration SLOW_ENDS_AFTER = Duration.ofMillis(500);

  /** The latest end of the slow client's call. */
  private static final Duration SLOW_ENDS_BEFORE = Duration.ofMillis(1200);

  /** The server that holds answers. */
  @AutoClose private static final ScriptedDownstream SCRIPTED = ScriptedDownstream.start();

  /** The inventory service, whose next error answer may have a large body. */
  @AutoClose private static final QueuedDownstream QUEUED = QueuedDownstream.start();

  /** The server that stalls a body. */
  @AutoClose private static final StreamingDownstream STREAMING = StreamingDownstream.start();

  @Autowired private ApplicationContext context;

  /**
   * Points each client at its server.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    RestClientTestData.pointClientsAt(registry, SCRIPTED);
    List.of(HELD_SETTINGS, HUNG_SETTINGS, SLOW_SETTINGS)
        .forEach(client -> registry.add(client + ".url", SCRIPTED::url));
    registry.add(STALLED_SETTINGS + ".url", STREAMING::url);
    registry.add(RestClientTestData.INVENTORY_URL, QUEUED::url);
  }

  @ParameterizedTest
  @FieldSource(ClientBeanCalls.BOTH_BEANS)
  @DisplayName("a call waiting for its Retry-After has freed its connection for another call")
  void connectionFreeDuringRetryWait(ClientBean bean) {
    assertConnectionFreeWhile(
        () ->
            ClientBeanCalls.get(
                context, bean, RestClientTestData.INVENTORY, ScriptedDownstream.STOCK),
        bean);
  }

  @Test
  @DisplayName("an interface call waiting for its Retry-After has freed its connection too")
  void interfaceConnectionFreeDuringRetryWait(@Autowired FlakyInventory inventory) {
    assertConnectionFreeWhile(() -> inventory.getRetryingAfter("1"), ClientBean.REST_CLIENT);
  }

  /**
   * Starts a call answered 503 with {@code Retry-After: 1} and a 1 MB body, more than the call
   * reads of it, on the inventory client's pool of one, and checks that, once the server has its
   * first request, another call through a bean gets the connection within max-wait, while the first
   * one waits before its second attempt, which then succeeds.
   */
  private void assertConnectionFreeWhile(Runnable waitingCall, ClientBean bean) {
    var requestsBefore = QUEUED.requests().size();
    QUEUED.answerNextLarge(HttpStatus.SERVICE_UNAVAILABLE.value());
    var waiting = CompletableFuture.runAsync(waitingCall, Thread::startVirtualThread);
    try {
      await()
          .atMost(RestClientTestData.WAIT)
          .until(() -> QUEUED.requests().size() > requestsBefore);

      assertThat(
              ClientBeanCalls.get(
                  context, bean, RestClientTestData.INVENTORY, ScriptedDownstream.STOCK))
          .isEqualTo(ScriptedDownstream.BODY);
    } finally {
      // The waiting call ends either way, so the next test finds the pool free.
      assertThat(waiting).succeedsWithin(RestClientTestData.WAIT);
    }
  }

  @ParameterizedTest
  @FieldSource(ClientBeanCalls.BOTH_BEANS)
  @DisplayName(
      "a call that never gets an answer fails with Spring's own I/O error, after its retries")
  void noAnswerIsSpringsOwnFailure(ClientBean bean) {
    assertThatExceptionOfType(ResourceAccessException.class)
        .isThrownBy(() -> ClientBeanCalls.get(context, bean, HUNG, ScriptedDownstream.DROPPED))
        .withMessageStartingWith("I/O error on GET request for")
        .withCauseInstanceOf(IOException.class);
  }

  @ParameterizedTest
  @FieldSource(ClientBeanCalls.BOTH_BEANS)
  @DisplayName("a success answer is handed over unread, before its body has ended")
  void successHandedOverUnread(ClientBean bean) {
    assertThat(statusBeforeBodyEnds(bean)).isEqualTo(HttpStatus.OK.value());
  }

  /**
   * Returns the status of an answer whose body stalls after one byte, read before the body ends:
   * the service's own code gets the answer while the body is still coming.
   */
  private int statusBeforeBodyEnds(ClientBean bean) {
    return switch (bean) {
      case REST_CLIENT ->
          ClientBeanCalls.restClient(context, STALLED)
              .get()
              .uri(StreamingDownstream.STALL)
              .exchange((request, response) -> response.getStatusCode().value(), false);
      case REST_TEMPLATE ->
          Objects.requireNonNull(
              ClientBeanCalls.restTemplate(context, STALLED)
                  .execute(
                      StreamingDownstream.STALL,
                      HttpMethod.GET,
                      null,
                      response -> response.getStatusCode().value()));
    };
  }

  /** Lets every held answer end. */
  @AfterEach
  void releaseHeldAnswers() {
    SCRIPTED.release();
  }

  @ParameterizedTest
  @FieldSource(ClientBeanCalls.BOTH_BEANS)
  @DisplayName("total-timeout cuts each attempt's wait for a connection")
  void totalTimeoutCutsPoolWait(ClientBean bean) {
    CallChecks.holdOneConnection(this::holdHeldConnection, SCRIPTED);

    CallChecks.assertFailsFast(
        () -> ClientBeanCalls.get(context, bean, HELD, ScriptedDownstream.STOCK),
        ConnectionRequestTimeoutException.class);
  }

  @ParameterizedTest
  @FieldSource(ClientBeanCalls.BOTH_BEANS)
  @DisplayName("a hung answer ends by total-timeout: a late attempt waits only the time left")
  void hungAnswerEndsByTotalTimeout(ClientBean bean) {
    var startedAt = System.nanoTime();

    assertThatExceptionOfType(ResourceAccessException.class)
        .isThrownBy(() -> ClientBeanCalls.get(context, bean, HUNG, ScriptedDownstream.HELD));
    assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
        .isBetween(HUNG_ENDS_AFTER, HUNG_ENDS_BEFORE);
  }

  @ParameterizedTest
  @FieldSource(ClientBeanCalls.BOTH_BEANS)
  @DisplayName("a body read after the answer's head is bounded by the attempt's answer timeout")
  void bodyReadBoundedByAttemptTimeout(ClientBean bean) {
    var startedAt = System.nanoTime();

    assertThatThrownBy(() -> ClientBeanCalls.get(context, bean, STALLED, StreamingDownstream.STALL))
        .isInstanceOf(RestClientException.class)
        .hasRootCauseInstanceOf(SocketTimeoutException.class);
    assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
        .isLessThan(RestClientTestData.FAILS_WITHIN);
  }

  @Test
  @DisplayName("Spring Boot's spring.http.clients timeouts leave the RestTemplate's own")
  void bootTimeoutsLeaveRestTemplate() {
    var startedAt = System.nanoTime();

    assertThatExceptionOfType(ResourceAccessException.class)
        .isThrownBy(
            () ->
                ClientBeanCalls.get(
                    context, ClientBean.REST_TEMPLATE, SLOW, ScriptedDownstream.HELD))
        .withRootCauseInstanceOf(SocketTimeoutException.class);
    assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
        .isBetween(SLOW_ENDS_AFTER, SLOW_ENDS_BEFORE);
  }

  /**
   * Holds the held client's one connection outside any call, so that it keeps it until the server
   * answers, and returns the answer.
   */
  private String holdHeldConnection() {
    var settings = context.getBean(StratumHttpClients.class).settings(HELD);
    try (var answer =
        context
            .getBean(ClientConnectionPools.class)
            .requestFactory(HELD, settings)
            .createRequest(URI.create(SCRIPTED.url() + ScriptedDownstream.HELD), HttpMethod.GET)
            .execute()) {
      return new String(answer.getBody().readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException failed) {
      throw new UncheckedIOException(failed);
    }
  }
}
