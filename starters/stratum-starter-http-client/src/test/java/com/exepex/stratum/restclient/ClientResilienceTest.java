package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.httpclient.BackoffPolicy;
import com.exepex.stratum.httpclient.ClientCircuitBreaker;
import com.exepex.stratum.httpclient.ClientRetry;
import com.exepex.stratum.httpclient.ClientSettings;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.restclient.testsupport.BackOffChecks;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.functions.Either;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.IntToLongFunction;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@DisplayName("Each client's retry and breaker are built from its settings, then its customizers")
class ClientResilienceTest {

  /**
   * The values of a breaker's rules that its settings set, and the slow-call rule Stratum keeps.
   */
  private static final List<Function<CircuitBreakerConfig, Object>> BREAKER_RULES =
      List.of(
          CircuitBreakerConfig::getFailureRateThreshold,
          CircuitBreakerConfig::getSlidingWindowType,
          CircuitBreakerConfig::getSlidingWindowSize,
          CircuitBreakerConfig::getMinimumNumberOfCalls,
          config -> config.getWaitIntervalFunctionInOpenState().apply(1),
          CircuitBreakerConfig::getPermittedNumberOfCallsInHalfOpenState,
          CircuitBreakerConfig::getSlowCallDurationThreshold,
          CircuitBreakerConfig::getSlowCallRateThreshold);

  /** A failure rate, in percent, at which a critical client opens early. */
  private static final float STRICT_FAILURE_RATE = 10;

  /** A window of attempts for a low-traffic client. */
  private static final int STRICT_WINDOW = 20;

  /** The least number of attempts before a low-traffic client's breaker may open. */
  private static final int STRICT_MINIMUM_CALLS = 10;

  /** A short open state, for a downstream that recovers in seconds. */
  private static final Duration SHORT_OPEN_STATE = Duration.ofSeconds(30);

  /** A few trial calls, for a fragile downstream. */
  private static final int FEW_TRIAL_CALLS = 3;

  /** A slow-call duration for a downstream that slows down before it fails. */
  private static final Duration SLOW_CALL = Duration.ofSeconds(2);

  /** A slow-call rate, in percent, below Resilience4j's 100. */
  private static final float SLOW_CALL_RATE = 50;

  /** Every breaker key set to a value other than Stratum's, as a critical client would. */
  private static final ClientCircuitBreaker STRICT_BREAKER =
      ClientCircuitBreaker.builder()
          .failureRateThreshold(STRICT_FAILURE_RATE)
          .slidingWindowSize(STRICT_WINDOW)
          .minimumNumberOfCalls(STRICT_MINIMUM_CALLS)
          .waitDurationInOpenState(SHORT_OPEN_STATE)
          .permittedNumberOfCallsInHalfOpenState(FEW_TRIAL_CALLS)
          .slowCallDurationThreshold(SLOW_CALL)
          .slowCallRateThreshold(SLOW_CALL_RATE)
          .build();

  /** Today's first wait before a retry, written out so that a changed default fails. */
  private static final Duration TODAYS_DELAY = Duration.ofMillis(200);

  /** Today's growth of each wait: it doubles. */
  private static final double TODAYS_MULTIPLIER = 2.0;

  /** Today's longest wait. */
  private static final Duration TODAYS_LONGEST_WAIT = Duration.ofSeconds(2);

  /** Today's attempts of a call: the first and 3 retries. */
  private static final int TODAYS_ATTEMPTS = 4;

  /** A client's own first wait, longer than Stratum's 200ms. */
  private static final Duration CLIENT_DELAY = Duration.ofMillis(500);

  /** A client's own growth of each wait, faster than Stratum's doubling. */
  private static final double CLIENT_MULTIPLIER = 3.0;

  /** A client's own longest wait, shorter than Stratum's 2s. */
  private static final Duration CLIENT_MAX_DELAY = Duration.ofSeconds(1);

  /** A window of two attempts, below the least number of calls Stratum asks for. */
  private static final int SMALL_WINDOW = 2;

  /** A window a customizer chooses over the client's keys. */
  private static final int CUSTOMIZED_WINDOW = 5;

  /** A number of attempts a customizer chooses over the client's keys. */
  private static final int CUSTOMIZED_ATTEMPTS = 7;

  @Nested
  @DisplayName("with nothing set")
  class Defaults {

    @Test
    @DisplayName(
        "the breaker is Resilience4j's default one, and the back-off and retries as before")
    void defaultsAreTodays() {
      var resilience = resilienceFor(HttpClientProperties.builder().build());

      assertBreakerRules(
          breakerOf(resilience).getCircuitBreakerConfig(), CircuitBreakerConfig.ofDefaults());
      assertThat(retryConfigOf(resilience).getMaxAttempts()).isEqualTo(TODAYS_ATTEMPTS);
      BackOffChecks.assertExponentialWaits(
          backOffOf(resilience), TODAYS_DELAY, TODAYS_MULTIPLIER, TODAYS_LONGEST_WAIT);
    }
  }

  @Nested
  @DisplayName("from the client's keys")
  class FromKeys {

    @Test
    @DisplayName("each circuit-breaker key reaches the breaker, the slow-call pair included")
    void everyBreakerKeyApplies() {
      var resilience = resilienceFor(inventoryWith(STRICT_BREAKER, null));

      var rules = breakerOf(resilience).getCircuitBreakerConfig();
      assertBreakerRules(
          rules,
          CircuitBreakerConfig.custom()
              .failureRateThreshold(STRICT_FAILURE_RATE)
              .slidingWindowSize(STRICT_WINDOW)
              .minimumNumberOfCalls(STRICT_MINIMUM_CALLS)
              .waitDurationInOpenState(SHORT_OPEN_STATE)
              .permittedNumberOfCallsInHalfOpenState(FEW_TRIAL_CALLS)
              .slowCallDurationThreshold(SLOW_CALL)
              .slowCallRateThreshold(SLOW_CALL_RATE)
              .build());
    }

    @Test
    @DisplayName("max-retries sets the attempts: 0 sends each call once")
    void maxRetriesApplies() {
      var resilience =
          resilienceFor(inventoryWith(null, ClientRetry.builder().maxRetries(0).build()));

      assertThat(retryConfigOf(resilience).getMaxAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("the client's own delay, multiplier and max-delay set its waits, not the defaults")
    void ownExponentialBackOff() {
      var resilience =
          resilienceFor(
              inventoryWith(
                  null,
                  ClientRetry.builder()
                      .delay(CLIENT_DELAY)
                      .multiplier(CLIENT_MULTIPLIER)
                      .maxDelay(CLIENT_MAX_DELAY)
                      .build()));

      BackOffChecks.assertExponentialWaits(
          backOffOf(resilience), CLIENT_DELAY, CLIENT_MULTIPLIER, CLIENT_MAX_DELAY);
    }

    @Test
    @DisplayName("the client's own fixed backoff-policy and delay set its waits, not the defaults")
    void ownFixedBackOff() {
      var resilience =
          resilienceFor(
              inventoryWith(
                  null,
                  ClientRetry.builder()
                      .backoffPolicy(BackoffPolicy.FIXED)
                      .delay(CLIENT_DELAY)
                      .multiplier(CLIENT_MULTIPLIER)
                      .maxDelay(CLIENT_MAX_DELAY)
                      .build()));

      BackOffChecks.assertFixedWaits(backOffOf(resilience), CLIENT_DELAY);
    }

    @Test
    @DisplayName("enabled false leaves the client without a breaker")
    void disabledBreaker() {
      var resilience =
          resilienceFor(inventoryWith(ClientCircuitBreaker.builder().enabled(false).build(), null));

      assertThat(resilience.circuitBreaker(RestClientTestData.INVENTORY)).isEmpty();
    }

    @Test
    @DisplayName("minimum-number-of-calls above the window opens once the window is full")
    void minimumCappedAtWindow() {
      var resilience =
          resilienceFor(
              inventoryWith(
                  ClientCircuitBreaker.builder()
                      .slidingWindowSize(SMALL_WINDOW)
                      .minimumNumberOfCalls(HttpClientConstants.DEFAULT_MINIMUM_NUMBER_OF_CALLS)
                      .build(),
                  null));
      var breaker = breakerOf(resilience);

      IntStream.range(0, SMALL_WINDOW)
          .forEach(
              attempt ->
                  breaker.onError(
                      1, TimeUnit.MILLISECONDS, new ResourceAccessException("refused")));

      assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
  }

  @Nested
  @DisplayName("with a customizer")
  class WithCustomizer {

    @Test
    @DisplayName("a StratumClientCustomizer runs after the keys and wins over them")
    void customizerWins() {
      var resilience =
          resilienceFor(
              inventoryWith(STRICT_BREAKER, ClientRetry.builder().maxRetries(1).build()),
              new WiderRules());

      assertThat(breakerOf(resilience).getCircuitBreakerConfig().getSlidingWindowSize())
          .isEqualTo(CUSTOMIZED_WINDOW);
      assertThat(retryConfigOf(resilience).getMaxAttempts()).isEqualTo(CUSTOMIZED_ATTEMPTS);
    }
  }

  /** Returns the retries and breakers of the settings, with the customizers given. */
  private ClientResilience resilienceFor(
      HttpClientProperties properties, StratumClientCustomizer... customizers) {
    var beans = new StaticListableBeanFactory();
    IntStream.range(0, customizers.length)
        .forEach(index -> beans.addBean("customizer" + index, customizers[index]));
    return new ClientResilience(
        properties,
        beans.getBeanProvider(StratumClientCustomizer.class),
        Clock.systemUTC(),
        beans.getBeanProvider(ServiceTokenResend.class));
  }

  /** Returns settings in which the inventory client sets only the given groups. */
  private HttpClientProperties inventoryWith(
      @Nullable ClientCircuitBreaker breaker, @Nullable ClientRetry retry) {
    return HttpClientProperties.builder()
        .clients(
            Map.of(
                RestClientTestData.INVENTORY,
                ClientSettings.builder().circuitBreaker(breaker).retry(retry).build()))
        .build();
  }

  /** Returns the inventory client's breaker. */
  private CircuitBreaker breakerOf(ClientResilience resilience) {
    return resilience.circuitBreaker(RestClientTestData.INVENTORY).orElseThrow();
  }

  /** Returns the inventory client's retry rules. */
  private RetryConfig retryConfigOf(ClientResilience resilience) {
    return resilience.retry(RestClientTestData.INVENTORY).getRetryConfig();
  }

  /** Returns the inventory client's wait before each attempt that follows a failure. */
  private IntToLongFunction backOffOf(ClientResilience resilience) {
    var waits = retryConfigOf(resilience).getIntervalBiFunction();
    return attempt -> waits.apply(attempt, Either.left(new IllegalStateException()));
  }

  /** Checks that the breaker's rules set by settings are those expected. */
  private void assertBreakerRules(CircuitBreakerConfig actual, CircuitBreakerConfig expected) {
    var expectedValues = BREAKER_RULES.stream().map(rule -> rule.apply(expected)).toList();

    assertThat(BREAKER_RULES.stream().map(rule -> rule.apply(actual)).toList())
        .isEqualTo(expectedValues);
  }

  /** A service's customizer that widens the window and the attempts of every client. */
  private static final class WiderRules implements StratumClientCustomizer {

    /**
     * Leaves the client as Stratum sets it.
     *
     * @param client the client's name
     * @param builder the client's builder
     */
    @Override
    public void customize(String client, RestClient.Builder builder) {
      // The client stays as Stratum sets it.
    }

    /**
     * Sets the attempts.
     *
     * @param client the client's name
     * @param retry the client's retry rules
     */
    @Override
    public void customizeRetry(String client, RetryConfig.Builder<ClientAttempt> retry) {
      retry.maxAttempts(CUSTOMIZED_ATTEMPTS);
    }

    /**
     * Sets the window.
     *
     * @param client the client's name
     * @param breaker the client's breaker rules
     */
    @Override
    public void customizeCircuitBreaker(String client, CircuitBreakerConfig.Builder breaker) {
      breaker.slidingWindowSize(CUSTOMIZED_WINDOW);
    }
  }
}
