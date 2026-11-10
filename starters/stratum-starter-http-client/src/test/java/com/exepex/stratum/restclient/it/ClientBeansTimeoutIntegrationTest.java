package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.ClientConnectionPools;
import com.exepex.stratum.restclient.StratumHttpClients;
import com.exepex.stratum.restclient.testsupport.CallChecks;
import com.exepex.stratum.restclient.testsupport.ClientBeanCalls;
import com.exepex.stratum.restclient.testsupport.ClientBeanCalls.ClientBean;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import com.exepex.stratum.restclient.testsupport.StreamingDownstream;
import com.exepex.stratum.restclient.testsupport.clients.ClientsApplication;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/**
 * Each client of this test shows one timeout through its beans: held a pool of one with a short
 * total-timeout, hung a second attempt with less time left than its timeout, stalled a body read
 * with a total-timeout far below its timeout, and slow its own timeout next to Spring Boot's.
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
