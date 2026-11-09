package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.ClientAttempt;
import com.exepex.stratum.restclient.StratumClientCustomizer;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import com.exepex.stratum.restclient.testsupport.clients.ClientsApplication;
import com.exepex.stratum.restclient.testsupport.clients.FlakyInventory;
import com.exepex.stratum.restclient.testsupport.clients.PricingOutage;
import io.github.resilience4j.retry.RetryConfig;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@SpringBootTest(
    classes = {ClientsApplication.class, TotalTimeoutIntegrationTest.LongPricingWait.class},
    properties = {
      HttpClientConstants.DEFAULTS + ".retry-delay=10ms",
      RestClientTestData.INVENTORY_SETTINGS + ".timeout=1s",
      RestClientTestData.INVENTORY_SETTINGS + ".total-timeout=1500ms",
      RestClientTestData.PRICING_SETTINGS + ".timeout=2s",
      RestClientTestData.PRICING_SETTINGS + ".total-timeout=500ms"
    })
@Timeout(30)
@DisplayName(
    "total-timeout bounds a whole call, retries and waits included, except a streamed download")
class TotalTimeoutIntegrationTest {

  /** The inventory client's total-timeout. */
  private static final Duration INVENTORY_TOTAL = Duration.ofMillis(1500);

  /** When the downstream lets a held pricing answer go: after pricing's total-timeout. */
  private static final Duration PRICING_ANSWERS_AFTER = Duration.ofMillis(800);

  /** The wait the service's customizer sets between pricing's attempts. */
  private static final Duration PRICING_WAIT = Duration.ofSeconds(30);

  /** How far past its total-timeout a call may end: the last attempt's own ending. */
  private static final Duration SLACK = Duration.ofMillis(400);

  /** The server both clients call. */
  @AutoClose private static final ScriptedDownstream DOWNSTREAM = ScriptedDownstream.start();

  @Autowired private FlakyInventory inventory;

  @Autowired private PricingOutage pricing;

  /**
   * Points both clients at the server.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    RestClientTestData.pointClientsAt(registry, DOWNSTREAM);
  }

  /** The late release a test started, which must end before the next test starts. */
  private CompletableFuture<Void> lateRelease = CompletableFuture.completedFuture(null);

  /** Lets every held answer go, so that the next test finds the connections free. */
  @AfterEach
  void releaseHeldAnswers() {
    lateRelease.join();
    DOWNSTREAM.release();
  }

  @Nested
  @DisplayName("the time left for a call")
  class TimeLeft {

    @Test
    @DisplayName("a retry gets only the time left: two attempts of a 1s timeout end by 1.5s")
    void retryEndsByTotalTimeout() {
      var heldBefore = DOWNSTREAM.heldRequests();
      var startedAt = System.nanoTime();

      assertThatExceptionOfType(ResourceAccessException.class)
          .isThrownBy(inventory::getHeld)
          .withRootCauseInstanceOf(SocketTimeoutException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
          .isGreaterThanOrEqualTo(INVENTORY_TOTAL.minus(SLACK))
          .isLessThan(INVENTORY_TOTAL.plus(SLACK));
      assertThat(DOWNSTREAM.heldRequests() - heldBefore).isEqualTo(2);
    }

    @Test
    @DisplayName(
        "an answer that comes after total-timeout fails the call, though timeout is longer")
    void answerAfterTotalTimeoutFails() {
      releaseLater();

      assertThatExceptionOfType(ResourceAccessException.class)
          .isThrownBy(pricing::held)
          .withRootCauseInstanceOf(SocketTimeoutException.class);
    }

    @Test
    @DisplayName("a streamed download keeps its full timeout, so its late answer is read")
    void streamedDownloadKeepsItsTimeout() throws IOException {
      releaseLater();

      try (var download = pricing.downloadHeld()) {
        assertThat(new String(download.readAllBytes(), StandardCharsets.UTF_8))
            .isEqualTo(ScriptedDownstream.BODY);
      }
    }
  }

  @Nested
  @DisplayName("a wait set by the service")
  class ServiceWait {

    @Test
    @DisplayName("a 30s wait from the customizer counts against total-timeout: a 503 is sent once")
    void customWaitCheckedAgainstTotalTimeout() {
      var requestsBefore = DOWNSTREAM.paths().size();
      var startedAt = System.nanoTime();

      assertThatExceptionOfType(RestClientResponseException.class).isThrownBy(pricing::unavailable);
      assertThat(DOWNSTREAM.paths()).hasSize(requestsBefore + 1);
      assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
          .isLessThan(RestClientTestData.FAILS_WITHIN);
    }
  }

  @Nested
  @DisplayName("a write whose answer timed out")
  class TimedOutWrite {

    @Test
    @DisplayName("a POST whose answer timed out is sent once: the service may have done its work")
    void postNotResentAfterTimeout() {
      var heldBefore = DOWNSTREAM.heldRequests();

      assertThatExceptionOfType(ResourceAccessException.class)
          .isThrownBy(inventory::postHeld)
          .withRootCauseInstanceOf(SocketTimeoutException.class);
      assertThat(DOWNSTREAM.heldRequests() - heldBefore).isEqualTo(1);
    }
  }

  /** The service's customizer: a wait between pricing's attempts longer than its total-timeout. */
  @TestConfiguration(proxyBeanMethods = false)
  static class LongPricingWait {

    /**
     * Sets pricing's wait between attempts to {@link #PRICING_WAIT}.
     *
     * @return the customizer
     */
    @Bean
    StratumClientCustomizer longPricingWait() {
      return new StratumClientCustomizer() {
        @Override
        public void customize(String client, RestClient.Builder builder) {
          // The client stays as Stratum sets it.
        }

        @Override
        public void customizeRetry(String client, RetryConfig.Builder<ClientAttempt> retry) {
          if (RestClientTestData.PRICING.equals(client)) {
            retry.waitDuration(PRICING_WAIT);
          }
        }
      };
    }
  }

  /** Lets the held pricing answer go after pricing's total-timeout has passed. */
  private void releaseLater() {
    lateRelease =
        CompletableFuture.runAsync(
            DOWNSTREAM::release,
            CompletableFuture.delayedExecutor(
                PRICING_ANSWERS_AFTER.toMillis(), TimeUnit.MILLISECONDS));
  }
}
