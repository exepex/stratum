package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpMethod;

@DisplayName("A call through ClientResilience.call gets an interceptor's own failure, unwrapped")
class ClientResilienceUnwrapTest {

  /** What the interceptor threw. */
  private static final IllegalStateException KEY_SERVICE_DOWN =
      new IllegalStateException("the key service is down");

  /** The retries and breakers under test, with Stratum's defaults. */
  private final ClientResilience resilience =
      new ClientResilience(
          HttpClientProperties.builder().build(),
          new StaticListableBeanFactory().getBeanProvider(StratumClientCustomizer.class),
          Clock.systemUTC(),
          new StaticListableBeanFactory().getBeanProvider(ServiceTokenResend.class));

  @Test
  @DisplayName("the caller gets the original, after one attempt, with nothing recorded")
  void originalAfterOneAttempt() {
    var attempts = new AtomicInteger();

    assertThatThrownBy(
            () ->
                resilience.call(
                    RestClientTestData.INVENTORY,
                    HttpMethod.GET,
                    false,
                    false,
                    () -> failOnce(attempts)))
        .isSameAs(KEY_SERVICE_DOWN);

    assertThat(attempts).hasValue(1);
    var breaker = resilience.circuitBreaker(RestClientTestData.INVENTORY).orElseThrow();
    assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isZero();
    assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
  }

  /** Counts an attempt and fails it as a marked interceptor failure. */
  private Object failOnce(AtomicInteger attempts) {
    attempts.incrementAndGet();
    throw new InterceptorFailure(KEY_SERVICE_DOWN);
  }
}
