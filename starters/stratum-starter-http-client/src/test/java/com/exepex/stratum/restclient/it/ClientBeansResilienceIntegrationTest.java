package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientErrorCode;
import com.exepex.stratum.restclient.ClientResilience;
import com.exepex.stratum.restclient.DownstreamException;
import com.exepex.stratum.restclient.testsupport.CallChecks;
import com.exepex.stratum.restclient.testsupport.ClientBeanCalls;
import com.exepex.stratum.restclient.testsupport.ClientBeanCalls.ClientBean;
import com.exepex.stratum.restclient.testsupport.QueuedDownstream;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import com.exepex.stratum.restclient.testsupport.beans.GuardRules;
import com.exepex.stratum.restclient.testsupport.clients.ClientsApplication;
import com.exepex.stratum.restclient.testsupport.clients.FlakyInventory;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

/**
 * The inventory client calls a server whose answer changes per attempt, and pricing and ledger a
 * scripted one through a pool of one connection. The error answers are in {@link
 * ClientBeansErrorAnswersIntegrationTest}.
 */
@SpringBootTest(
    classes = {ClientsApplication.class, GuardRules.class},
    properties = {
      HttpClientConstants.DEFAULTS
          + ".retry.max-retries="
          + ClientBeansResilienceIntegrationTest.RETRIES,
      HttpClientConstants.DEFAULTS + ".retry.delay=10ms",
      RestClientTestData.PRICING_SETTINGS + ".max-connections=1",
      RestClientTestData.PRICING_SETTINGS + ".max-wait=300ms",
      HttpClientConstants.CLIENTS + "." + GuardRules.LEDGER + ".max-connections=1",
      HttpClientConstants.CLIENTS + "." + GuardRules.LEDGER + ".max-wait=1s"
    })
@Timeout(60)
@DisplayName("A client's beans retry calls without a body and share the client's circuit breaker")
class ClientBeansResilienceIntegrationTest {

  /** The retries of every client in this test. */
  static final int RETRIES = 2;

  /** A 503, retried for a call without a body. */
  private static final int UNAVAILABLE = HttpStatus.SERVICE_UNAVAILABLE.value();

  /** A 500, never retried and counted by the breaker. */
  private static final int FAILED = HttpStatus.INTERNAL_SERVER_ERROR.value();

  /** A 404, neither retried nor counted. */
  private static final int NOT_FOUND = HttpStatus.NOT_FOUND.value();

  /** The inventory service, whose answer changes per attempt. */
  @AutoClose private static final QueuedDownstream QUEUED = QueuedDownstream.start();

  /** The pricing and ledger service. */
  @AutoClose private static final ScriptedDownstream SCRIPTED = ScriptedDownstream.start();

  @Autowired private ApplicationContext context;

  @Autowired private ClientResilience resilience;

  /**
   * Points each client at its server.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    registry.add(RestClientTestData.INVENTORY_URL, QUEUED::url);
    registry.add(RestClientTestData.PRICING_URL, SCRIPTED::url);
    registry.add(HttpClientConstants.CLIENTS + "." + GuardRules.LEDGER + ".url", SCRIPTED::url);
  }

  /** Closes every breaker and empties the queued answers, so each test starts afresh. */
  @BeforeEach
  void startAfresh() {
    QUEUED.answerNext();
    Stream.of(RestClientTestData.INVENTORY, GuardRules.LEDGER)
        .forEach(client -> resilience.circuitBreaker(client).ifPresent(CircuitBreaker::reset));
  }

  @Nested
  @DisplayName("retries")
  class Retries {

    /** The statuses after which a GET is sent again and the breaker counts, on each bean. */
    private static final List<Arguments> RETRIED_ANSWERS =
        ClientBeanCalls.BEANS.stream()
            .flatMap(
                bean ->
                    Stream.of(
                        Arguments.of(bean, UNAVAILABLE),
                        Arguments.of(bean, HttpStatus.TOO_MANY_REQUESTS.value())))
            .toList();

    @ParameterizedTest
    @FieldSource("RETRIED_ANSWERS")
    @DisplayName("a GET answered 503 or 429, then 200, returns the 200, and the breaker counts one")
    void getRetried(ClientBean bean, int status) {
      var requestsBefore = QUEUED.requests().size();
      QUEUED.answerNext(status);

      assertThat(inventoryGet(bean)).isEqualTo(ScriptedDownstream.BODY);
      assertThat(QUEUED.requests()).hasSize(requestsBefore + 2);
      assertThat(inventoryFailedCalls()).isEqualTo(1);
    }

    @ParameterizedTest
    @FieldSource(ClientBeanCalls.BOTH_BEANS)
    @DisplayName("a PUT without a body answered 503, then 200, is sent again")
    void putWithoutBodyRetried(ClientBean bean) {
      var requestsBefore = QUEUED.requests().size();
      QUEUED.answerNext(UNAVAILABLE);

      ClientBeanCalls.put(
          context, bean, RestClientTestData.INVENTORY, ScriptedDownstream.STOCK, null);

      assertThat(QUEUED.requests().subList(requestsBefore, QUEUED.requests().size()))
          .containsExactly("PUT /stock", "PUT /stock");
    }

    @Test
    @DisplayName("an interface's call is retried once per attempt, not by the beans' retries too")
    void interfaceNotRetriedTwice(@Autowired FlakyInventory inventory) {
      var requestsBefore = QUEUED.requests().size();
      QUEUED.answerNext(
          IntStream.range(0, (RETRIES + 1) * (RETRIES + 1)).map(_ -> UNAVAILABLE).toArray());

      assertThatExceptionOfType(DownstreamException.class)
          .isThrownBy(() -> inventory.get(UNAVAILABLE));
      assertThat(QUEUED.requests()).hasSize(requestsBefore + RETRIES + 1);
    }

    @ParameterizedTest
    @FieldSource(ClientBeanCalls.BOTH_BEANS)
    @DisplayName("a PUT with a body answered 503 is sent once, though PUT is idempotent")
    void putWithBodySentOnce(ClientBean bean) {
      var requestsBefore = QUEUED.requests().size();
      QUEUED.answerNext(UNAVAILABLE);

      assertThatExceptionOfType(HttpServerErrorException.ServiceUnavailable.class)
          .isThrownBy(
              () ->
                  ClientBeanCalls.put(
                      context,
                      bean,
                      RestClientTestData.INVENTORY,
                      ScriptedDownstream.STOCK,
                      "o-1"));
      assertThat(QUEUED.requests()).hasSize(requestsBefore + 1);
    }

    @ParameterizedTest
    @FieldSource(ClientBeanCalls.BOTH_BEANS)
    @DisplayName("a 503's Retry-After sets the wait before each next attempt")
    void retryAfterHonoured(ClientBean bean) {
      var startedAt = System.nanoTime();

      assertThatExceptionOfType(HttpServerErrorException.ServiceUnavailable.class)
          .isThrownBy(() -> pricingGet(bean, ScriptedDownstream.RETRY_AFTER + "1"));
      assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
          .isGreaterThanOrEqualTo(Duration.ofSeconds(RETRIES));
    }

    @ParameterizedTest
    @FieldSource(ClientBeanCalls.BOTH_BEANS)
    @DisplayName("each dropped 503 frees its connection, so a pool of one serves every attempt")
    void droppedAnswersFreeTheirConnection(ClientBean bean) {
      var requestsBefore = SCRIPTED.paths().size();

      assertThatExceptionOfType(HttpServerErrorException.ServiceUnavailable.class)
          .isThrownBy(() -> pricingGet(bean, ScriptedDownstream.UNAVAILABLE));
      assertThat(SCRIPTED.paths()).hasSize(requestsBefore + RETRIES + 1);
    }

    @ParameterizedTest
    @FieldSource(ClientBeanCalls.BOTH_BEANS)
    @DisplayName("a 503 kept when the breaker opens is freed, so the next call gets the connection")
    void answerKeptWhenBreakerOpensIsFreed(ClientBean bean) {
      CallChecks.assertEndsWith(
          () ->
              ClientBeanCalls.get(context, bean, GuardRules.LEDGER, ScriptedDownstream.UNAVAILABLE),
          HttpClientErrorCode.BREAKER_OPEN);
      resilience.circuitBreaker(GuardRules.LEDGER).orElseThrow().transitionToClosedState();

      assertThat(ClientBeanCalls.get(context, bean, GuardRules.LEDGER, ScriptedDownstream.STOCK))
          .isEqualTo(ScriptedDownstream.BODY);
    }
  }

  @Nested
  @DisplayName("the circuit breaker")
  class Breaker {

    @ParameterizedTest
    @FieldSource(ClientBeanCalls.BOTH_BEANS)
    @DisplayName("opened by the client's interface, it refuses the bean's call with SX-HTTPC-0003")
    void openedByInterfaceRefusesBean(ClientBean bean, @Autowired FlakyInventory inventory) {
      // The bean's call goes through first, then the breaker starts its window afresh.
      assertThat(inventoryGet(bean)).isEqualTo(ScriptedDownstream.BODY);
      resilience.circuitBreaker(RestClientTestData.INVENTORY).orElseThrow().reset();
      QUEUED.answerNext(failures(FAILED));
      IntStream.range(0, GuardRules.INVENTORY_WINDOW)
          .forEach(
              _ ->
                  assertThatExceptionOfType(DownstreamException.class)
                      .isThrownBy(() -> inventory.get(FAILED)));
      var requestsBefore = QUEUED.requests().size();

      assertBreakerOpen(bean);
      assertThat(QUEUED.requests()).hasSize(requestsBefore);
    }

    @ParameterizedTest
    @FieldSource(ClientBeanCalls.BOTH_BEANS)
    @DisplayName("500 answers through the bean open it")
    void beanFailuresOpenIt(ClientBean bean) {
      QUEUED.answerNext(failures(FAILED));
      IntStream.range(0, GuardRules.INVENTORY_WINDOW)
          .forEach(
              _ ->
                  assertThatExceptionOfType(HttpServerErrorException.InternalServerError.class)
                      .isThrownBy(() -> inventoryGet(bean)));

      assertBreakerOpen(bean);
    }

    @ParameterizedTest
    @FieldSource(ClientBeanCalls.BOTH_BEANS)
    @DisplayName("a 404 is Spring's NotFound, sent once, not counted and not mapped")
    void notFoundKept(ClientBean bean, @Autowired GuardRules rules) {
      var requestsBefore = QUEUED.requests().size();
      final var mappedBefore = rules.mappedErrors();
      QUEUED.answerNext(NOT_FOUND);

      assertThatExceptionOfType(HttpClientErrorException.NotFound.class)
          .isThrownBy(() -> inventoryGet(bean));
      assertThat(QUEUED.requests()).hasSize(requestsBefore + 1);
      assertThat(inventoryFailedCalls()).isZero();
      assertThat(rules.mappedErrors()).isEqualTo(mappedBefore);
    }

    /** Checks that the inventory client's bean is refused with SX-HTTPC-0003. */
    private void assertBreakerOpen(ClientBean bean) {
      CallChecks.assertEndsWith(() -> inventoryGet(bean), HttpClientErrorCode.BREAKER_OPEN);
    }

    /** Returns the given status, once for each call the inventory client's breaker judges. */
    private int[] failures(int status) {
      return IntStream.range(0, GuardRules.INVENTORY_WINDOW).map(_ -> status).toArray();
    }
  }

  /** Gets the inventory service's stock through one of its client's beans. */
  private String inventoryGet(ClientBean bean) {
    return ClientBeanCalls.get(
        context, bean, RestClientTestData.INVENTORY, ScriptedDownstream.STOCK);
  }

  /** Gets a path of the pricing service through one of its client's beans. */
  private String pricingGet(ClientBean bean, String path) {
    return ClientBeanCalls.get(context, bean, RestClientTestData.PRICING, path);
  }

  /** Returns the failed calls the inventory client's breaker counts now. */
  private int inventoryFailedCalls() {
    return CallChecks.failedCalls(resilience, RestClientTestData.INVENTORY);
  }
}
