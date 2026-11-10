package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.httpclient.ClientCircuitBreaker;
import com.exepex.stratum.httpclient.ClientRetry;
import com.exepex.stratum.httpclient.ClientSettings;
import com.exepex.stratum.httpclient.HttpClientErrorCode;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.restclient.testsupport.RecordedMeters;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

@DisplayName("Each call that ends is reported once, with how it ended")
class ClientResilienceCallTest {

  /** A GET of the inventory client. */
  private static final ClientCall CALL =
      ClientCall.builder()
          .client(RestClientTestData.INVENTORY)
          .method(HttpMethod.GET)
          .uriTemplate("/stock/{sku}")
          .build();

  /** The retries of the inventory client, more than its breaker lets through once it fails. */
  private static final int RETRIES = 3;

  /** The meters the calls are reported to. */
  private final RecordedMeters meters = new RecordedMeters();

  /**
   * The retries and breaker of an inventory client that retries 3 times, its breaker opening after
   * two failed attempts.
   */
  private final ClientResilience resilience =
      new ClientResilience(
          HttpClientProperties.builder()
              .clients(
                  Map.of(
                      RestClientTestData.INVENTORY,
                      ClientSettings.builder()
                          .retry(
                              ClientRetry.builder()
                                  .maxRetries(RETRIES)
                                  .delay(Duration.ofMillis(1))
                                  .build())
                          .circuitBreaker(
                              ClientCircuitBreaker.builder()
                                  .minimumNumberOfCalls(2)
                                  .slidingWindowSize(2)
                                  .build())
                          .build()))
              .build(),
          new StaticListableBeanFactory().getBeanProvider(StratumClientCustomizer.class),
          Clock.systemUTC(),
          new StaticListableBeanFactory().getBeanProvider(ServiceTokenResend.class),
          new ClientCallLog(HttpClientProperties.builder().build(), null),
          meters.provider());

  /** Runs the call with the attempt given, and returns the one outcome it reported. */
  private ClientCallOutcome reported(
      Supplier<Object> attempt, Class<? extends RuntimeException> thrown) {
    assertThatExceptionOfType(thrown).isThrownBy(() -> resilience.call(CALL, attempt));
    assertThat(meters.getCalls()).hasSize(1);
    return meters.getCalls().getFirst();
  }

  /** Fails an attempt with the failure given. */
  private Object failWith(RuntimeException failure) {
    throw failure;
  }

  @Nested
  @DisplayName("a failure that stops an attempt before it is sent")
  class NotSent {

    @Test
    @DisplayName("a service token that cannot be fetched is reported as unavailable, and thrown")
    void tokenFetchFailure() {
      var outcome =
          reported(
              () -> failWith(new StratumException(HttpClientErrorCode.DOWNSTREAM_UNAVAILABLE, "")),
              StratumException.class);

      assertThat(outcome.outcome()).isEqualTo(CallOutcome.UNAVAILABLE);
      assertThat(outcome.status()).isNull();
    }

    @Test
    @DisplayName("any other code, such as a missing caller token, is reported as an error")
    void otherCode() {
      var outcome =
          reported(
              () -> failWith(new StratumException(HttpClientErrorCode.CALLER_TOKEN_MISSING, "")),
              StratumException.class);

      assertThat(outcome.outcome()).isEqualTo(CallOutcome.ERROR);
    }

    @Test
    @DisplayName("any other exception, such as a bug, is reported as an error, and thrown")
    void unexpectedFailure() {
      var outcome =
          reported(() -> failWith(new IllegalStateException("a bug")), IllegalStateException.class);

      assertThat(outcome.outcome()).isEqualTo(CallOutcome.ERROR);
    }
  }

  @Nested
  @DisplayName("the open breaker")
  class Breaker {

    @Test
    @DisplayName("refusing every attempt, is reported as refused, with no attempt")
    void refused() {
      resilience.circuitBreaker(RestClientTestData.INVENTORY).orElseThrow().transitionToOpenState();

      var outcome = reported(() -> "never sent", StratumException.class);

      assertThat(outcome.outcome()).isEqualTo(CallOutcome.REFUSED);
      assertThat(outcome.attempts()).isZero();
      assertThat(meters.getBreakers()).containsExactly(RestClientTestData.INVENTORY);
    }

    @Test
    @DisplayName("opening mid-call, leaves the outcome of the last attempt sent")
    void refusedMidRetry() {
      var outcome =
          reported(
              () ->
                  failWith(
                      HttpServerErrorException.create(
                          HttpStatus.SERVICE_UNAVAILABLE, "", null, null, null)),
              StratumException.class);

      assertThat(outcome.outcome()).isEqualTo(CallOutcome.SERVER_ERROR);
      assertThat(outcome.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
      assertThat(outcome.attempts()).isEqualTo(2);
    }
  }

  @Test
  @DisplayName("an attempt a full pool refused is counted, once")
  void poolFullCounted() {
    var outcome =
        reported(
            () ->
                failWith(
                    new ResourceAccessException(
                        "full", new ConnectionRequestTimeoutException("no connection"))),
            ResourceAccessException.class);

    assertThat(outcome.outcome()).isEqualTo(CallOutcome.UNAVAILABLE);
    assertThat(meters.getPoolRefusals()).containsExactly(RestClientTestData.INVENTORY);
  }
}
