package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.ClientExchangeDecorator;
import com.exepex.stratum.restclient.StratumClientCustomizer;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import com.exepex.stratum.restclient.testsupport.clients.ClientsApplication;
import com.exepex.stratum.restclient.testsupport.clients.FlakyInventory;
import com.exepex.stratum.restclient.testsupport.clients.PricingOutage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.service.invoker.HttpExchangeAdapter;
import org.springframework.web.service.invoker.HttpExchangeAdapterDecorator;
import org.springframework.web.service.invoker.HttpRequestValues;

@SpringBootTest(
    classes = {ClientsApplication.class, RetryIntegrationTest.AttemptCounters.class},
    properties = {
      HttpClientConstants.DEFAULTS + ".retries=" + RetryIntegrationTest.RETRIES,
      HttpClientConstants.DEFAULTS + ".retry-delay=10ms",
      RestClientTestData.INVENTORY_SETTINGS + ".max-connections=1",
      RestClientTestData.INVENTORY_SETTINGS + ".max-wait=300ms"
    })
@Timeout(30)
@DisplayName("A failed call is sent again only when that is safe, within its total-timeout")
class RetryIntegrationTest {

  /** The retries of every client in this test. */
  static final int RETRIES = 2;

  /** The requests of a call sent again after each failure. */
  private static final int ALL_ATTEMPTS = RETRIES + 1;

  /** The inventory service. */
  @AutoClose private static final ScriptedDownstream INVENTORY = ScriptedDownstream.start();

  /** The attempts of the pricing client, which points at a port that refuses connections. */
  private static final AtomicInteger PRICING_ATTEMPTS = new AtomicInteger();

  /** The attempts that reached a decorator inside the retries. */
  private static final AtomicInteger DECORATED_ATTEMPTS = new AtomicInteger();

  @Autowired private FlakyInventory inventory;

  @Autowired private PricingOutage pricing;

  /** The number of requests the downstream had when the test started. */
  private int requestsBefore;

  /**
   * Points the inventory client at its server and pricing at a port that refuses connections.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    registry.add(RestClientTestData.INVENTORY_URL, INVENTORY::url);
    registry.add(RestClientTestData.PRICING_URL, () -> RestClientTestData.UNUSED_URL);
  }

  /** Starts each test's counts from zero. */
  @BeforeEach
  void resetCounts() {
    requestsBefore = INVENTORY.paths().size();
    PRICING_ATTEMPTS.set(0);
    DECORATED_ATTEMPTS.set(0);
  }

  @Nested
  @DisplayName("after an error answer")
  class ErrorAnswers {

    /** The answers after which a GET is sent again. */
    private static final List<Integer> RETRIED = List.of(502, 503, 504, 429);

    /** Error answers after which a GET is sent once. */
    private static final List<Integer> SENT_ONCE = List.of(500, 404);

    @ParameterizedTest
    @FieldSource("RETRIED")
    @DisplayName("a GET is sent again after 502, 503, 504 or 429, then fails with the last answer")
    void retriedStatuses(int status) {
      assertThatExceptionOfType(RestClientResponseException.class)
          .isThrownBy(() -> inventory.get(status))
          .satisfies(answer -> assertThat(answer.getStatusCode().value()).isEqualTo(status));
      assertThat(requests()).hasSize(ALL_ATTEMPTS);
    }

    @ParameterizedTest
    @FieldSource("SENT_ONCE")
    @DisplayName("a GET is sent once after any other error answer")
    void otherStatusesSentOnce(int status) {
      assertThatExceptionOfType(RestClientResponseException.class)
          .isThrownBy(() -> inventory.get(status));
      assertThat(requests()).hasSize(1);
    }

    @Test
    @DisplayName("a DELETE is sent again after a 503; a POST and a PATCH are sent once")
    void methods() {
      var unavailable = HttpStatus.SERVICE_UNAVAILABLE.value();

      assertThatExceptionOfType(RestClientResponseException.class)
          .isThrownBy(() -> inventory.delete(unavailable));
      assertThatExceptionOfType(RestClientResponseException.class)
          .isThrownBy(() -> inventory.post(unavailable));
      assertThatExceptionOfType(RestClientResponseException.class)
          .isThrownBy(() -> inventory.patch(unavailable));
      assertThat(requests()).hasSize(ALL_ATTEMPTS + 2);
    }

    @Test
    @DisplayName(
        "each failed answer gives its connection back, so a pool of one serves every retry")
    void retriedAnswerReleasesConnection() {
      var code = HttpStatus.BAD_GATEWAY.value();
      assertThatExceptionOfType(RestClientResponseException.class)
          .isThrownBy(() -> inventory.get(code));
      assertThat(requests()).hasSize(ALL_ATTEMPTS);
      assertThat(inventory.get(HttpStatus.OK.value()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
  }

  @Nested
  @DisplayName("after no answer")
  class NoAnswer {

    @Test
    @DisplayName("a GET is sent again after a dropped connection; a POST is sent once")
    void droppedConnection() {
      assertThatExceptionOfType(ResourceAccessException.class).isThrownBy(inventory::getDropped);
      assertThat(requests()).hasSize(ALL_ATTEMPTS);

      requestsBefore = INVENTORY.paths().size();
      assertThatExceptionOfType(ResourceAccessException.class).isThrownBy(inventory::postDropped);
      assertThat(requests()).hasSize(1);
    }

    @Test
    @DisplayName("a POST is sent again when the connection is refused, as nothing was sent")
    void refusedConnectionRetriedForPost() {
      assertThatExceptionOfType(ResourceAccessException.class).isThrownBy(pricing::order);
      assertThat(PRICING_ATTEMPTS).hasValue(ALL_ATTEMPTS);
    }
  }

  @Nested
  @DisplayName("Retry-After")
  class RetryAfter {

    @Test
    @DisplayName("a 503's Retry-After of 1 second is waited before each retry")
    void retryAfterHonoured() {
      var startedAt = System.nanoTime();

      assertThatExceptionOfType(RestClientResponseException.class)
          .isThrownBy(() -> inventory.getRetryingAfter("1"));
      assertThat(requests()).hasSize(ALL_ATTEMPTS);
      assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
          .isGreaterThanOrEqualTo(Duration.ofSeconds(RETRIES));
    }

    @Test
    @DisplayName("a Retry-After past total-timeout returns the 503 at once, without waiting")
    void retryAfterTooLongReturnsTheAnswer() {
      var startedAt = System.nanoTime();

      assertThatExceptionOfType(RestClientResponseException.class)
          .isThrownBy(() -> inventory.getRetryingAfter("30"));
      assertThat(requests()).hasSize(1);
      assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
          .isLessThan(RestClientTestData.FAILS_WITHIN);
    }
  }

  @Nested
  @DisplayName("what runs on each attempt")
  class EachAttempt {

    @Test
    @DisplayName("a streamed upload is sent once, as it cannot be sent again")
    void streamedUploadSentOnce() {
      var upload = new ByteArrayResource("order".getBytes(StandardCharsets.US_ASCII));

      assertThatExceptionOfType(RestClientResponseException.class)
          .isThrownBy(() -> inventory.upload(upload));
      assertThat(requests()).hasSize(1);
    }

    @Test
    @DisplayName("a decorator inside the retries sees every attempt")
    void innerDecoratorRunsOnEveryAttempt() {
      var code = HttpStatus.SERVICE_UNAVAILABLE.value();
      assertThatExceptionOfType(RestClientResponseException.class)
          .isThrownBy(() -> inventory.get(code));
      assertThat(DECORATED_ATTEMPTS).hasValue(ALL_ATTEMPTS);
    }
  }

  /** Returns the paths the downstream got since the test started. */
  private List<String> requests() {
    var paths = INVENTORY.paths();
    return paths.subList(requestsBefore, paths.size());
  }

  /** Counts the pricing client's attempts and the attempts inside the retries. */
  @TestConfiguration(proxyBeanMethods = false)
  static class AttemptCounters {

    /**
     * Counts each attempt of the pricing client with a request interceptor.
     *
     * @return the customizer
     */
    @Bean
    StratumClientCustomizer countPricingAttempts() {
      return this::addPricingCounter;
    }

    /**
     * Counts each attempt that reaches a decorator ordered after the retries.
     *
     * @return the decorator
     */
    @Bean
    ClientExchangeDecorator attemptCountingDecorator() {
      return new ClientExchangeDecorator() {
        @Override
        public HttpExchangeAdapter decorate(String client, HttpExchangeAdapter exchange) {
          return new CountingExchangeAdapter(exchange);
        }

        @Override
        public int getOrder() {
          return HttpClientConstants.RESILIENCE_ORDER + 1;
        }
      };
    }

    /** Adds the counting interceptor to the pricing client only. */
    private void addPricingCounter(String client, RestClient.Builder builder) {
      if (RestClientTestData.PRICING.equals(client)) {
        builder.requestInterceptor(this::countAttempt);
      }
    }

    /** Counts one attempt of the pricing client and sends it on. */
    private ClientHttpResponse countAttempt(
        HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
      PRICING_ATTEMPTS.incrementAndGet();
      return execution.execute(request, body);
    }
  }

  /** Counts each call that returns an answer without a body. */
  static class CountingExchangeAdapter extends HttpExchangeAdapterDecorator {

    /**
     * Makes the adapter.
     *
     * @param exchange the calls
     */
    CountingExchangeAdapter(HttpExchangeAdapter exchange) {
      super(exchange);
    }

    /**
     * Counts the attempt and sends it.
     *
     * @param values the call
     * @return the answer
     */
    @Override
    public ResponseEntity<Void> exchangeForBodilessEntity(HttpRequestValues values) {
      DECORATED_ATTEMPTS.incrementAndGet();
      return super.exchangeForBodilessEntity(values);
    }
  }
}
