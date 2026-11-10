package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.ClientResilience;
import com.exepex.stratum.restclient.DownstreamException;
import com.exepex.stratum.restclient.testsupport.ClientBeanCalls;
import com.exepex.stratum.restclient.testsupport.ClientBeanCalls.ClientBean;
import com.exepex.stratum.restclient.testsupport.QueuedDownstream;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import com.exepex.stratum.restclient.testsupport.beans.GuardRules;
import com.exepex.stratum.restclient.testsupport.clients.ClientsApplication;
import com.exepex.stratum.restclient.testsupport.clients.FlakyInventory;
import com.exepex.stratum.restclient.testsupport.errors.ErrorDownstream;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientResponseException;

/**
 * The inventory client calls a server whose answer changes per attempt, pricing and ledger a
 * scripted one through a pool of one connection, and orders a server of large error bodies.
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
      HttpClientConstants.CLIENTS + "." + GuardRules.LEDGER + ".max-wait=1s",
      HttpClientConstants.CLIENTS + "." + RestClientTestData.ORDERS + ".max-connections=1"
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

  /** A server error status Spring has no name for. */
  private static final int UNKNOWN_SERVER_ERROR = 599;

  /** A client error status Spring has no name for. */
  private static final int UNKNOWN_CLIENT_ERROR = 499;

  /** The inventory service, whose answer changes per attempt. */
  @AutoClose private static final QueuedDownstream QUEUED = QueuedDownstream.start();

  /** The pricing and ledger service. */
  @AutoClose private static final ScriptedDownstream SCRIPTED = ScriptedDownstream.start();

  /** The orders service, which answers with large error bodies. */
  @AutoClose private static final ErrorDownstream ERRORS = ErrorDownstream.start();

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
    registry.add(RestClientTestData.ORDERS_URL, ERRORS::url);
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

    @ParameterizedTest
    @FieldSource(ClientBeanCalls.BOTH_BEANS)
    @DisplayName("a GET answered 503, then 200, returns the 200")
    void getRetried(ClientBean bean) {
      var requestsBefore = QUEUED.requests().size();
      QUEUED.answerNext(UNAVAILABLE);

      assertThat(inventoryGet(bean)).isEqualTo(ScriptedDownstream.BODY);
      assertThat(QUEUED.requests()).hasSize(requestsBefore + 2);
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
      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(
              () ->
                  ClientBeanCalls.get(
                      context, bean, GuardRules.LEDGER, ScriptedDownstream.UNAVAILABLE))
          .withMessageStartingWith("SX-HTTPC-0003");
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
      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(() -> inventoryGet(bean))
          .withMessageStartingWith("SX-HTTPC-0003");
    }

    /** Returns the given status, once for each call the inventory client's breaker judges. */
    private int[] failures(int status) {
      return IntStream.range(0, GuardRules.INVENTORY_WINDOW).map(_ -> status).toArray();
    }
  }

  @Nested
  @DisplayName("error answers")
  class ErrorAnswers {

    @ParameterizedTest
    @FieldSource(ClientBeanCalls.BOTH_BEANS)
    @DisplayName("the service's own handling gets the last 503, with its headers and body")
    void lastAnswerHandedOver(ClientBean bean) {
      var lastNumber = String.valueOf(QUEUED.requests().size() + RETRIES + 1);
      QUEUED.answerNext(UNAVAILABLE, UNAVAILABLE, UNAVAILABLE);

      var seen = lastErrorAnswer(bean);

      assertThat(seen.getStatusCode().value()).isEqualTo(UNAVAILABLE);
      assertThat(seen.getResponseHeaders().getFirst(QueuedDownstream.ANSWER_NUMBER))
          .isEqualTo(lastNumber);
      assertThat(seen.getResponseBodyAsString())
          .isEqualTo(QueuedDownstream.ERROR_BODY + lastNumber);
    }

    @ParameterizedTest
    @FieldSource(ClientBeanCalls.BOTH_BEANS)
    @DisplayName("a 1 MB error body: the service reads 64 KB, and the connection is used again")
    void largeErrorBodyCapped(ClientBean bean) {
      var connectionsBefore = ERRORS.connections();

      Stream.of(1, 2)
          .forEach(
              _ ->
                  assertThatExceptionOfType(HttpServerErrorException.class)
                      .isThrownBy(
                          () ->
                              ClientBeanCalls.get(
                                  context,
                                  bean,
                                  RestClientTestData.ORDERS,
                                  ErrorDownstream.LARGE_ERROR))
                      .satisfies(
                          failure ->
                              assertThat(failure.getResponseBodyAsByteArray())
                                  .hasSize(HttpClientConstants.ERROR_BODY_LIMIT)));
      assertThat(ERRORS.connections() - connectionsBefore).isLessThanOrEqualTo(1);
    }

    @ParameterizedTest
    @FieldSource(ClientBeanCalls.BOTH_BEANS)
    @DisplayName("an unknown 599 is counted by the breaker, an unknown 499 is not")
    void unknownStatusesCarried(ClientBean bean) {
      QUEUED.answerNext(UNKNOWN_SERVER_ERROR);
      assertThatExceptionOfType(HttpServerErrorException.class)
          .isThrownBy(() -> inventoryGet(bean));
      assertThat(inventoryFailedCalls()).isEqualTo(1);

      QUEUED.answerNext(UNKNOWN_CLIENT_ERROR);
      assertThatExceptionOfType(HttpClientErrorException.class)
          .isThrownBy(() -> inventoryGet(bean));
      assertThat(inventoryFailedCalls()).isEqualTo(1);
    }

    /**
     * Returns the error answer the service's own code sees: the RestClient's {@code exchange} reads
     * the answer itself; the RestTemplate's error handler, Spring's default, turns it into an
     * exception.
     */
    private RestClientResponseException lastErrorAnswer(ClientBean bean) {
      return switch (bean) {
        case REST_CLIENT ->
            ClientBeanCalls.restClient(context, RestClientTestData.INVENTORY)
                .get()
                .uri(ScriptedDownstream.STOCK)
                .exchange(
                    (request, response) ->
                        HttpServerErrorException.create(
                            response.getStatusCode(),
                            response.getStatusText(),
                            response.getHeaders(),
                            response.getBody().readAllBytes(),
                            StandardCharsets.UTF_8));
        case REST_TEMPLATE ->
            catchThrowableOfType(
                HttpServerErrorException.class,
                () ->
                    ClientBeanCalls.restTemplate(context, RestClientTestData.INVENTORY)
                        .execute(ScriptedDownstream.STOCK, HttpMethod.GET, null, null));
      };
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
    return resilience
        .circuitBreaker(RestClientTestData.INVENTORY)
        .orElseThrow()
        .getMetrics()
        .getNumberOfFailedCalls();
  }
}
