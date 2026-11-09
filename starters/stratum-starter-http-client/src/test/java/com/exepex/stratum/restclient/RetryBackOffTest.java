package com.exepex.stratum.restclient;

import com.exepex.stratum.httpclient.BackoffPolicy;
import com.exepex.stratum.httpclient.ClientRetry;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.restclient.testsupport.BackOffChecks;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("A client's back-off follows its retry settings, spread at random by up to half")
class RetryBackOffTest {

  /** Stratum's retry settings, as a client with nothing set gets them. */
  private static final ClientRetry STRATUM_RETRY =
      HttpClientProperties.builder().build().defaults().retry();

  /** A first wait longer than Stratum's longest, 2s. */
  private static final Duration LONG_DELAY = HttpClientConstants.MAX_RETRY_DELAY.multipliedBy(2);

  /** A faster growth than Stratum's doubling. */
  private static final double TRIPLING = 3.0;

  /** A longest wait shorter than Stratum's. */
  private static final Duration ONE_SECOND = Duration.ofSeconds(1);

  @Nested
  @DisplayName("exponential")
  class Exponential {

    @Test
    @DisplayName("with nothing set, it starts at 200ms, doubles, and stays at most 2s, as before")
    void stratumBackOff() {
      BackOffChecks.assertExponentialWaits(
          RetryRules.backOff(STRATUM_RETRY),
          HttpClientConstants.DEFAULT_RETRY_DELAY,
          HttpClientConstants.DEFAULT_MULTIPLIER,
          HttpClientConstants.MAX_RETRY_DELAY);
    }

    @Test
    @DisplayName("multiplier and max-delay set how fast it grows and where it stops")
    void multiplierAndMaxDelay() {
      var retry = STRATUM_RETRY.toBuilder().multiplier(TRIPLING).maxDelay(ONE_SECOND).build();

      BackOffChecks.assertExponentialWaits(
          RetryRules.backOff(retry), HttpClientConstants.DEFAULT_RETRY_DELAY, TRIPLING, ONE_SECOND);
    }

    @Test
    @DisplayName("without max-delay, a delay above 2s is used as it is, as the longest wait")
    void longDelayKept() {
      var retry = STRATUM_RETRY.toBuilder().delay(LONG_DELAY).build();

      BackOffChecks.assertExponentialWaits(
          RetryRules.backOff(retry),
          LONG_DELAY,
          HttpClientConstants.DEFAULT_MULTIPLIER,
          LONG_DELAY);
    }
  }

  @Nested
  @DisplayName("fixed")
  class Fixed {

    @Test
    @DisplayName("every wait is delay, and multiplier and max-delay are ignored")
    void fixedWaits() {
      var retry =
          STRATUM_RETRY.toBuilder()
              .backoffPolicy(BackoffPolicy.FIXED)
              .multiplier(TRIPLING)
              .maxDelay(ONE_SECOND)
              .delay(LONG_DELAY)
              .build();

      BackOffChecks.assertFixedWaits(RetryRules.backOff(retry), LONG_DELAY);
    }
  }
}
