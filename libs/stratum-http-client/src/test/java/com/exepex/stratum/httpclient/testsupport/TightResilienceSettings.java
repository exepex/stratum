package com.exepex.stratum.httpclient.testsupport;

import com.exepex.stratum.httpclient.BackoffPolicy;
import com.exepex.stratum.httpclient.ClientCircuitBreaker;
import com.exepex.stratum.httpclient.ClientRetry;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer;
import java.time.Duration;
import java.util.HashMap;

/**
 * A service's Java configuration of retries and breakers, as the module page shows it: every client
 * retries twice after 100ms, and the payments client backs off faster and opens early.
 */
public class TightResilienceSettings implements HttpClientPropertiesCustomizer {

  /** The critical client this configuration tightens. */
  public static final String PAYMENTS = "payments";

  /** The prefix of the payments client's own settings. */
  public static final String PAYMENTS_SETTINGS = HttpClientConstants.CLIENTS + "." + PAYMENTS;

  /** The retries every client gets from this configuration's defaults. */
  public static final int SHARED_MAX_RETRIES = 2;

  /** The first wait every client gets from this configuration's defaults. */
  public static final Duration SHARED_DELAY = Duration.ofMillis(100);

  /** The shared failure rate, in percent, as Stratum's. */
  public static final float SHARED_FAILURE_RATE = 50;

  /** The shared time an open breaker stays open. */
  public static final Duration SHARED_WAIT_IN_OPEN_STATE = Duration.ofSeconds(30);

  /** The payments client's own first wait. */
  public static final Duration PAYMENTS_DELAY = Duration.ofMillis(200);

  /** The payments client's own growth of each wait. */
  public static final double PAYMENTS_MULTIPLIER = 3.0;

  /** The payments client's own longest wait. */
  public static final Duration PAYMENTS_MAX_DELAY = Duration.ofSeconds(1);

  /** The payments client's own failure rate, in percent. */
  public static final float PAYMENTS_FAILURE_RATE = 10;

  /** The payments client's own window, in attempts. */
  public static final int PAYMENTS_WINDOW = 20;

  /** The payments client's own least number of attempts before it may open. */
  public static final int PAYMENTS_MINIMUM_CALLS = 10;

  /** The payments client's own number of trial calls. */
  public static final int PAYMENTS_TRIAL_CALLS = 3;

  /** The payments client's own slow-call duration. */
  public static final Duration PAYMENTS_SLOW_CALL = Duration.ofSeconds(2);

  /** The payments client's own slow-call rate, in percent. */
  public static final float PAYMENTS_SLOW_CALL_RATE = 50;

  /** The payments client's retry settings, its own merged over the new defaults. */
  public static final ClientRetry PAYMENTS_RETRY =
      ClientRetry.builder()
          .maxRetries(SHARED_MAX_RETRIES)
          .backoffPolicy(BackoffPolicy.EXPONENTIAL)
          .delay(PAYMENTS_DELAY)
          .multiplier(PAYMENTS_MULTIPLIER)
          .maxDelay(PAYMENTS_MAX_DELAY)
          .build();

  /** The payments client's circuit breaker settings, its own merged over the new defaults. */
  public static final ClientCircuitBreaker PAYMENTS_BREAKER =
      ClientCircuitBreaker.builder()
          .enabled(true)
          .failureRateThreshold(PAYMENTS_FAILURE_RATE)
          .slidingWindowSize(PAYMENTS_WINDOW)
          .minimumNumberOfCalls(PAYMENTS_MINIMUM_CALLS)
          .waitDurationInOpenState(SHARED_WAIT_IN_OPEN_STATE)
          .permittedNumberOfCallsInHalfOpenState(PAYMENTS_TRIAL_CALLS)
          .slowCallDurationThreshold(PAYMENTS_SLOW_CALL)
          .slowCallRateThreshold(PAYMENTS_SLOW_CALL_RATE)
          .build();

  @Override
  public HttpClientProperties customize(HttpClientProperties properties) {
    var shared = properties.defaults();
    var defaults =
        shared.toBuilder()
            .retry(
                shared.retry().toBuilder()
                    .maxRetries(SHARED_MAX_RETRIES)
                    .delay(SHARED_DELAY)
                    .build())
            .circuitBreaker(
                shared.circuitBreaker().toBuilder()
                    .failureRateThreshold(SHARED_FAILURE_RATE)
                    .waitDurationInOpenState(SHARED_WAIT_IN_OPEN_STATE)
                    .build())
            .build();
    var withDefaults = properties.toBuilder().defaults(defaults).build();
    var payments = withDefaults.clientSettings(PAYMENTS);
    var paymentsSettings =
        payments.toBuilder()
            .retry(
                payments.retry().toBuilder()
                    .backoffPolicy(BackoffPolicy.EXPONENTIAL)
                    .delay(PAYMENTS_DELAY)
                    .multiplier(PAYMENTS_MULTIPLIER)
                    .maxDelay(PAYMENTS_MAX_DELAY)
                    .build())
            .circuitBreaker(
                payments.circuitBreaker().toBuilder()
                    .failureRateThreshold(PAYMENTS_FAILURE_RATE)
                    .slidingWindowSize(PAYMENTS_WINDOW)
                    .minimumNumberOfCalls(PAYMENTS_MINIMUM_CALLS)
                    .permittedNumberOfCallsInHalfOpenState(PAYMENTS_TRIAL_CALLS)
                    .slowCallDurationThreshold(PAYMENTS_SLOW_CALL)
                    .slowCallRateThreshold(PAYMENTS_SLOW_CALL_RATE)
                    .build())
            .build();
    var clients = new HashMap<>(withDefaults.clients());
    clients.put(PAYMENTS, paymentsSettings);
    return withDefaults.toBuilder().clients(clients).build();
  }
}
