package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.httpclient.HttpClientErrorCode;
import com.exepex.stratum.restclient.ClientResilience;
import com.exepex.stratum.restclient.StratumHttpClients;
import com.exepex.stratum.restclient.testsupport.CallChecks;
import com.exepex.stratum.restclient.testsupport.EchoDownstream;
import com.exepex.stratum.restclient.testsupport.headers.AuditInterceptor;
import com.exepex.stratum.restclient.testsupport.headers.CallLog;
import com.exepex.stratum.restclient.testsupport.headers.HeadersApplication;
import com.exepex.stratum.restclient.testsupport.headers.HeadersTestData;
import com.exepex.stratum.restclient.testsupport.headers.SignedClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The signed client's interceptors read each body, with a limit of 1 KB. The signed client's
 * breaker decides after 2 calls and allows 1 call when half open.
 */
@SpringBootTest(
    classes = HeadersApplication.class,
    properties = {
      HeadersTestData.SIGNED_SETTINGS + ".interceptor-body-limit=1KB",
      HeadersTestData.SIGNED_SETTINGS + ".circuit-breaker.minimum-number-of-calls=2",
      HeadersTestData.SIGNED_SETTINGS + ".circuit-breaker.sliding-window-size=2",
      HeadersTestData.SIGNED_SETTINGS
          + ".circuit-breaker.permitted-number-of-calls-in-half-open-state=1"
    })
@DisplayName(
    "An interceptor reads at most interceptor-body-limit of a body; above it nothing is sent")
class InterceptorBodyLimitIntegrationTest {

  /** The test clients' limit, in bytes. */
  private static final int LIMIT = 1024;

  /** The size of a JSON order {@code {"id":"…"}} around its id. */
  private static final int JSON_AROUND_ID = 9;

  /** A body far above the limit: 2 MiB. */
  private static final int LARGE = 2 * 1024 * 1024;

  /** The text of the cause, which names the client and the limit as {@code DataSize} prints it. */
  private static final String CAUSE = "client signed, interceptor-body-limit 1024B";

  /** The over-limit calls the breaker test makes, one more than its window. */
  private static final int CALLS = 3;

  /** The server every client calls. */
  @AutoClose private static final EchoDownstream DOWNSTREAM = EchoDownstream.start();

  @Autowired private SignedClient signed;

  @Autowired private CallLog log;

  /**
   * Points the clients at the server.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    HeadersTestData.pointClientsAt(registry, DOWNSTREAM::url);
  }

  /** Forgets what earlier tests' calls recorded. */
  @BeforeEach
  void clearLog() {
    log.clear();
  }

  /** Returns an order whose JSON is the given number of bytes. */
  private Map<String, String> orderOf(int jsonBytes) {
    return Map.of("id", "x".repeat(jsonBytes - JSON_AROUND_ID));
  }

  /** Sends an order one byte over the limit and returns its failure, checked to be 0005. */
  private StratumException sendOverLimit() {
    return CallChecks.assertEndsWith(
            () -> signed.send(orderOf(LIMIT + 1)), HttpClientErrorCode.INTERCEPTOR_BODY_TOO_LARGE)
        .actual();
  }

  @Nested
  @DisplayName("at the limit")
  class AtLimit {

    @Test
    @DisplayName("a body of exactly the limit is read and sent whole")
    void atLimitSent() {
      signed.send(orderOf(LIMIT));

      assertThat(DOWNSTREAM.last().body()).hasSize(LIMIT);
      assertThat(DOWNSTREAM.last().values(HeadersTestData.SIGNATURE))
          .containsExactly(String.valueOf(LIMIT));
    }
  }

  @Nested
  @DisplayName("one byte over the limit")
  class OverLimit {

    @Test
    @DisplayName("a JSON body fails with SX-HTTPC-0005, and nothing is sent")
    void jsonRefused() {
      var before = DOWNSTREAM.requestCount();

      sendOverLimit();

      assertThat(DOWNSTREAM.requestCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("a text body fails with SX-HTTPC-0005, and nothing is sent")
    void textRefused() {
      var before = DOWNSTREAM.requestCount();

      CallChecks.assertEndsWith(
          () -> signed.sendText("x".repeat(LIMIT + 1)),
          HttpClientErrorCode.INTERCEPTOR_BODY_TOO_LARGE);

      assertThat(DOWNSTREAM.requestCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("the caller sees no detail and no body; the logged cause names client and limit")
    void errorShowsNoBody() {
      var failure = sendOverLimit();

      assertThat(failure.getErrorDetail()).isEmpty();
      assertThat(failure.getMessage()).doesNotContain("xxxx");
      assertThat(failure.getCause()).hasMessage(CAUSE);
    }
  }

  @Nested
  @TestPropertySource(properties = HeadersTestData.SIGNED_SETTINGS + ".mock=true")
  @DisplayName("on a client set to mock: true")
  class Mocked {

    @Test
    @DisplayName("a body over the limit fails with SX-HTTPC-0005, before the mock answers")
    void mockedRefused() {
      assertThat(sendOverLimit().getErrorDetail()).isEmpty();
    }
  }

  @Nested
  @DisplayName("for the retries and the circuit breaker")
  class NotRetriedNorCounted {

    @Autowired private ClientResilience resilience;

    /** Returns the signed client's breaker, reset by the test before it calls. */
    private CircuitBreaker breaker() {
      return resilience.circuitBreaker(HeadersTestData.SIGNED).orElseThrow();
    }

    /** Puts the shared breaker back to closed with no recorded call. */
    @BeforeEach
    void resetBreaker() {
      breaker().reset();
    }

    @Test
    @DisplayName("each call runs once, and the breaker records neither a success nor a failure")
    void notRetriedNorCounted() {
      IntStream.range(0, CALLS).forEach(call -> sendOverLimit());

      assertThat(Collections.frequency(log.starts(), AuditInterceptor.NAME)).isEqualTo(CALLS);
      assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
      assertThat(breaker().getMetrics().getNumberOfSuccessfulCalls()).isZero();
      assertThat(breaker().getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("from half open, the call leaves the breaker half open, with nothing recorded")
    void halfOpenUnchanged() {
      breaker().transitionToOpenState();
      breaker().transitionToHalfOpenState();

      sendOverLimit();

      assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
      assertThat(breaker().getMetrics().getNumberOfSuccessfulCalls()).isZero();
      assertThat(breaker().getMetrics().getNumberOfFailedCalls()).isZero();
    }
  }

  @Nested
  @TestPropertySource(properties = "stratum.backward-compatible=true")
  @DisplayName("with stratum.backward-compatible=true")
  class BackwardCompatible {

    @Test
    @DisplayName("the limit still holds")
    void limitHolds() {
      assertThat(sendOverLimit().getErrorDetail()).isEmpty();
    }
  }

  @Nested
  @DisplayName("when no interceptor reads the body")
  class Unaffected {

    @Test
    @DisplayName("a streamed upload of 2 MiB goes through: an interceptor never reads it")
    void streamedUploadSent() {
      signed.upload(new InputStreamResource(new ByteArrayInputStream(new byte[LARGE])));

      assertThat(DOWNSTREAM.last().body()).hasSize(LARGE);
    }

    @Test
    @DisplayName(
        "a JSON body of 2 MiB on an ad-hoc call, which no interceptor can read, goes through")
    void unreadBodySent(@Autowired StratumHttpClients clients) {
      clients
          .restClient(HeadersTestData.SIGNED)
          .post()
          .uri(EchoDownstream.ECHO)
          .body(orderOf(LARGE))
          .retrieve()
          .toBodilessEntity();

      assertThat(DOWNSTREAM.last().body()).hasSize(LARGE);
    }
  }
}
