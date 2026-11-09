package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.ClientResilience;
import com.exepex.stratum.restclient.StratumClientCustomizer;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import com.exepex.stratum.restclient.testsupport.clients.ClientsApplication;
import com.exepex.stratum.restclient.testsupport.clients.FlakyInventory;
import com.exepex.stratum.test.LogLineCapture;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@SpringBootTest(
    classes = {ClientsApplication.class, CircuitBreakerIntegrationTest.SmallBreakerWindow.class},
    properties = {
      HttpClientConstants.DEFAULTS + ".retry-delay=10ms",
      RestClientTestData.PRICING_SETTINGS + ".circuit-breaker=false"
    })
@Timeout(30)
@DisplayName("A client's breaker opens on failures that show the downstream is down, not on 4xx")
class CircuitBreakerIntegrationTest {

  /** The calls the breaker sees before it decides, set small by the test's customizer. */
  private static final int WINDOW = 2;

  /** A client with the shared settings only, whose breaker is made on first use. */
  private static final String OTHER_CLIENT = "audit";

  /** The inventory service. */
  @AutoClose private static final ScriptedDownstream INVENTORY = ScriptedDownstream.start();

  /** The breaker's log lines. */
  private final LogLineCapture breakerLog = new LogLineCapture(ClientResilience.class.getName());

  @Autowired private FlakyInventory inventory;

  @Autowired private ClientResilience resilience;

  /** The number of requests the downstream had when the test started. */
  private int requestsBefore;

  /**
   * Points both clients at the server.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    RestClientTestData.pointClientsAt(registry, INVENTORY);
  }

  /** Closes the breaker and starts the counts and the log from zero. */
  @BeforeEach
  void closeBreaker() {
    breaker().reset();
    requestsBefore = INVENTORY.paths().size();
    breakerLog.start();
  }

  /** Stops reading the log. */
  @AfterEach
  void stopLog() {
    breakerLog.stop();
  }

  @Nested
  @DisplayName("what opens it")
  class Opening {

    @Test
    @DisplayName("5xx answers open it; the next call fails at once and sends nothing")
    void opensOnServerErrors() {
      failTwice(HttpStatus.INTERNAL_SERVER_ERROR);

      assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
      assertThatExceptionOfType(CallNotPermittedException.class)
          .isThrownBy(() -> inventory.get(HttpStatus.OK.value()));
      assertThat(requests()).hasSize(WINDOW);
    }

    @Test
    @DisplayName("dropped connections open it")
    void opensOnNoAnswer() {
      assertThatExceptionOfType(ResourceAccessException.class).isThrownBy(inventory::postDropped);
      assertThatExceptionOfType(ResourceAccessException.class).isThrownBy(inventory::postDropped);

      assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("4xx answers other than 429 never open it: the downstream is up")
    void clientErrorsNotCounted() {
      failTwice(HttpStatus.NOT_FOUND);
      failTwice(HttpStatus.BAD_REQUEST);

      assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
  }

  @Nested
  @DisplayName("while it is open")
  class WhileOpen {

    @Test
    @DisplayName("an open breaker stops the retries of a 429: the third attempt is never sent")
    void openBreakerStopsRetries() {
      assertThatExceptionOfType(CallNotPermittedException.class)
          .isThrownBy(() -> inventory.get(HttpStatus.TOO_MANY_REQUESTS.value()));
      assertThat(requests()).hasSize(WINDOW);
    }

    @Test
    @DisplayName("each change of state is logged once, with the client's name")
    void stateChangeLoggedOnce() {
      failTwice(HttpStatus.INTERNAL_SERVER_ERROR);
      assertThatExceptionOfType(CallNotPermittedException.class)
          .isThrownBy(() -> inventory.get(HttpStatus.OK.value()));

      assertThat(breakerLog.onlyLine().getFormattedMessage())
          .isEqualTo("The circuit breaker of HTTP client inventory went from CLOSED to OPEN");
    }
  }

  @Nested
  @DisplayName("which clients have one")
  class PerClient {

    @Test
    @DisplayName("each client has its own breaker: an open one leaves another client's closed")
    void oneBreakerPerClient() {
      failTwice(HttpStatus.INTERNAL_SERVER_ERROR);

      var otherBreaker = resilience.circuitBreaker(OTHER_CLIENT).orElseThrow();
      assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
      assertThat(otherBreaker.getName()).isEqualTo(OTHER_CLIENT);
      assertThat(otherBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("a client with circuit-breaker false has no breaker")
    void switchedOff() {
      assertThat(resilience.circuitBreaker(RestClientTestData.PRICING)).isEmpty();
    }
  }

  /** Fails two calls with the status, which is never retried. */
  private void failTwice(HttpStatus status) {
    for (var call = 0; call < WINDOW; call++) {
      assertThatExceptionOfType(RestClientResponseException.class)
          .isThrownBy(() -> inventory.post(status.value()));
    }
  }

  /** Returns the inventory client's breaker. */
  private CircuitBreaker breaker() {
    return resilience.circuitBreaker(RestClientTestData.INVENTORY).orElseThrow();
  }

  /** Returns the paths the downstream got since the test started. */
  private List<String> requests() {
    var paths = INVENTORY.paths();
    return paths.subList(requestsBefore, paths.size());
  }

  /** The service's customizer: a breaker that decides after two calls. */
  @TestConfiguration(proxyBeanMethods = false)
  static class SmallBreakerWindow {

    /**
     * Makes the breaker decide after {@link #WINDOW} calls.
     *
     * @return the customizer
     */
    @Bean
    StratumClientCustomizer smallBreakerWindow() {
      return new StratumClientCustomizer() {
        @Override
        public void customize(String client, RestClient.Builder builder) {
          // The client stays as Stratum sets it.
        }

        @Override
        public void customizeCircuitBreaker(String client, CircuitBreakerConfig.Builder breaker) {
          breaker.slidingWindowSize(WINDOW).minimumNumberOfCalls(WINDOW);
        }
      };
    }
  }
}
