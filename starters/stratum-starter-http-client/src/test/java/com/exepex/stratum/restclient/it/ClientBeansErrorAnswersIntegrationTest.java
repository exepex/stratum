package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.ClientResilience;
import com.exepex.stratum.restclient.testsupport.CallChecks;
import com.exepex.stratum.restclient.testsupport.ClientBeanCalls;
import com.exepex.stratum.restclient.testsupport.ClientBeanCalls.ClientBean;
import com.exepex.stratum.restclient.testsupport.QueuedDownstream;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import com.exepex.stratum.restclient.testsupport.beans.GuardRules;
import com.exepex.stratum.restclient.testsupport.clients.ClientsApplication;
import com.exepex.stratum.restclient.testsupport.errors.ErrorDownstream;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * The inventory client calls a server whose answer changes per attempt, and orders a server of
 * large error bodies through a pool of one connection.
 */
@SpringBootTest(
    classes = {ClientsApplication.class, GuardRules.class},
    properties = {
      HttpClientConstants.DEFAULTS
          + ".retry.max-retries="
          + ClientBeansErrorAnswersIntegrationTest.RETRIES,
      HttpClientConstants.DEFAULTS + ".retry.delay=10ms",
      HttpClientConstants.CLIENTS + "." + RestClientTestData.ORDERS + ".max-connections=1"
    })
@Timeout(60)
@DisplayName("A client's beans hand the service its error answers as the other service sent them")
class ClientBeansErrorAnswersIntegrationTest {

  /** The retries of every client in this test. */
  static final int RETRIES = 2;

  /** A 503, retried for a call without a body. */
  private static final int UNAVAILABLE = HttpStatus.SERVICE_UNAVAILABLE.value();

  /** A server error status Spring has no name for. */
  private static final int UNKNOWN_SERVER_ERROR = 599;

  /** A client error status Spring has no name for. */
  private static final int UNKNOWN_CLIENT_ERROR = 499;

  /** The inventory service, whose answer changes per attempt. */
  @AutoClose private static final QueuedDownstream QUEUED = QueuedDownstream.start();

  /** The orders service, which answers with large error bodies. */
  @AutoClose private static final ErrorDownstream ERRORS = ErrorDownstream.start();

  @Autowired private ApplicationContext context;

  @Autowired private ClientResilience resilience;

  /**
   * Points each client at its server, and the pricing and ledger clients, which no test here calls,
   * at an unused address.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    registry.add(RestClientTestData.INVENTORY_URL, QUEUED::url);
    registry.add(RestClientTestData.ORDERS_URL, ERRORS::url);
    registry.add(RestClientTestData.PRICING_URL, () -> RestClientTestData.UNUSED_URL);
    registry.add(
        HttpClientConstants.CLIENTS + "." + GuardRules.LEDGER + ".url",
        () -> RestClientTestData.UNUSED_URL);
  }

  /** Closes the inventory breaker and empties the queued answers, so each test starts afresh. */
  @BeforeEach
  void startAfresh() {
    QUEUED.answerNext();
    resilience.circuitBreaker(RestClientTestData.INVENTORY).ifPresent(CircuitBreaker::reset);
  }

  @ParameterizedTest
  @FieldSource(ClientBeanCalls.BOTH_BEANS)
  @DisplayName("the service's own handling gets the last 503, its status text, headers and body")
  void lastAnswerHandedOver(ClientBean bean) {
    QUEUED.answerNext(UNAVAILABLE, UNAVAILABLE, UNAVAILABLE);
    var lastNumber = String.valueOf(QUEUED.requests().size() + RETRIES + 1);

    var seen = lastErrorAnswer(bean);

    assertThat(seen.getStatusCode().value()).isEqualTo(UNAVAILABLE);
    assertThat(seen.getStatusText()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase());
    assertThat(seen.getResponseHeaders().getFirst(QueuedDownstream.ANSWER_NUMBER))
        .isEqualTo(lastNumber);
    assertThat(seen.getResponseBodyAsString()).isEqualTo(QueuedDownstream.ERROR_BODY + lastNumber);
  }

  @ParameterizedTest
  @FieldSource(ClientBeanCalls.BOTH_BEANS)
  @DisplayName("a 1 MB error body: the service reads 64 KB, and its connection is dropped")
  void largeErrorBodyCapped(ClientBean bean) {
    assertLargeErrorBodyCapped(bean);
    var connectionsBefore = ERRORS.connections();

    assertLargeErrorBodyCapped(bean);

    assertThat(ERRORS.connections() - connectionsBefore).isOne();
  }

  /** Calls the 1 MB error body through a bean and checks that 64 KB of it was read. */
  private void assertLargeErrorBodyCapped(ClientBean bean) {
    assertThatExceptionOfType(HttpServerErrorException.class)
        .isThrownBy(
            () ->
                ClientBeanCalls.get(
                    context, bean, RestClientTestData.ORDERS, ErrorDownstream.LARGE_ERROR))
        .satisfies(
            failure ->
                assertThat(failure.getResponseBodyAsByteArray())
                    .hasSize(HttpClientConstants.ERROR_BODY_LIMIT));
  }

  @ParameterizedTest
  @FieldSource(ClientBeanCalls.BOTH_BEANS)
  @DisplayName("an unknown 599 is counted by the breaker, an unknown 499 is not")
  void unknownStatusesCarried(ClientBean bean) {
    QUEUED.answerNext(UNKNOWN_SERVER_ERROR);
    assertThatExceptionOfType(HttpServerErrorException.class).isThrownBy(() -> inventoryGet(bean));
    assertThat(inventoryFailedCalls()).isEqualTo(1);

    QUEUED.answerNext(UNKNOWN_CLIENT_ERROR);
    assertThatExceptionOfType(HttpClientErrorException.class).isThrownBy(() -> inventoryGet(bean));
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

  /** Gets the inventory service's stock through one of its client's beans. */
  private String inventoryGet(ClientBean bean) {
    return ClientBeanCalls.get(
        context, bean, RestClientTestData.INVENTORY, ScriptedDownstream.STOCK);
  }

  /** Returns the failed calls the inventory client's breaker counts now. */
  private int inventoryFailedCalls() {
    return CallChecks.failedCalls(resilience, RestClientTestData.INVENTORY);
  }
}
