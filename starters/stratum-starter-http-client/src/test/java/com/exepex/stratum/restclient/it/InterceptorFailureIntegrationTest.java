package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientErrorCode;
import com.exepex.stratum.restclient.ClientResilience;
import com.exepex.stratum.restclient.StratumHttpClients;
import com.exepex.stratum.restclient.testsupport.CallChecks;
import com.exepex.stratum.restclient.testsupport.EchoDownstream;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.TokenDownstream;
import com.exepex.stratum.restclient.testsupport.headers.AuditInterceptor;
import com.exepex.stratum.restclient.testsupport.headers.CallLog;
import com.exepex.stratum.restclient.testsupport.headers.HeadersApplication;
import com.exepex.stratum.restclient.testsupport.headers.HeadersTestData;
import com.exepex.stratum.restclient.testsupport.headers.NestingInterceptorBeans;
import com.exepex.stratum.restclient.testsupport.headers.PlainClient;
import com.exepex.stratum.restclient.testsupport.headers.SignedClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

/**
 * The audit interceptor refuses one call with a given failure, before anything is sent. The plain
 * client's breaker decides after 2 calls and allows 1 call when half open.
 */
@SpringBootTest(
    classes = HeadersApplication.class,
    properties = {
      HeadersTestData.PLAIN_SETTINGS + ".circuit-breaker.minimum-number-of-calls=2",
      HeadersTestData.PLAIN_SETTINGS + ".circuit-breaker.sliding-window-size=2",
      HeadersTestData.PLAIN_SETTINGS
          + ".circuit-breaker.permitted-number-of-calls-in-half-open-state=1"
    })
@DisplayName(
    "An interceptor's failure fails the call once, uncounted, and reaches the caller as is")
class InterceptorFailureIntegrationTest {

  /** The server every client calls. */
  @AutoClose private static final EchoDownstream DOWNSTREAM = EchoDownstream.start();

  @Autowired private CallLog log;

  @Autowired private ClientResilience resilience;

  /**
   * Points the clients at the server.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    HeadersTestData.pointClientsAt(registry, DOWNSTREAM::url);
  }

  /** Forgets earlier calls and puts the plain client's breaker back to closed. */
  @BeforeEach
  void reset() {
    log.clear();
    breaker(HeadersTestData.PLAIN).reset();
    breaker(HeadersTestData.SIGNED).reset();
  }

  /** Returns a client's breaker. */
  private CircuitBreaker breaker(String client) {
    return resilience.circuitBreaker(client).orElseThrow();
  }

  /** Returns a 503 as another service's client would throw it. */
  private HttpServerErrorException unavailable() {
    return HttpServerErrorException.create(
        HttpStatus.SERVICE_UNAVAILABLE, "", new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
  }

  /** Returns how many attempts the audit interceptor started. */
  private int attempts() {
    return Collections.frequency(log.starts(), AuditInterceptor.NAME);
  }

  @Nested
  @DisplayName("on a call of an interface")
  class OnInterface {

    @Test
    @DisplayName("a 503 from the interceptor: one attempt, nothing sent or recorded, same failure")
    void unavailableNotRetried(@Autowired PlainClient plain) {
      var failure = unavailable();
      final var before = DOWNSTREAM.requestCount();
      log.refuseNextCallWith(failure);

      assertThatThrownBy(plain::echo).isSameAs(failure);

      assertThat(attempts()).isEqualTo(1);
      assertThat(DOWNSTREAM.requestCount()).isEqualTo(before);
      CallChecks.assertNothingRecorded(breaker(HeadersTestData.PLAIN), CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("an IllegalStateException: one attempt, nothing recorded, same failure")
    void otherFailureNotRecorded(@Autowired PlainClient plain) {
      var failure = new IllegalStateException("no signing key");
      log.refuseNextCallWith(failure);

      assertThatThrownBy(plain::echo).isSameAs(failure);

      assertThat(attempts()).isEqualTo(1);
      CallChecks.assertNothingRecorded(breaker(HeadersTestData.PLAIN), CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("from half open, it records nothing and releases the one permitted call")
    void halfOpenPermitReleased(@Autowired PlainClient plain) {
      breaker(HeadersTestData.PLAIN).transitionToOpenState();
      breaker(HeadersTestData.PLAIN).transitionToHalfOpenState();
      var failure = unavailable();
      log.refuseNextCallWith(failure);

      assertThatThrownBy(plain::echo).isSameAs(failure);

      CallChecks.assertNothingRecorded(
          breaker(HeadersTestData.PLAIN), CircuitBreaker.State.HALF_OPEN);
      assertThat(plain.echo()).isEqualTo(EchoDownstream.BODY);
    }

    @Test
    @DisplayName("a 404 from the interceptor on an Optional method is thrown, not read as empty")
    void notFoundThrown(@Autowired SignedClient signed) {
      var failure =
          HttpClientErrorException.create(
              HttpStatus.NOT_FOUND, "", new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
      log.refuseNextCallWith(failure);

      assertThatThrownBy(signed::find).isSameAs(failure);
    }

    @Test
    @DisplayName(
        "a real 503 from the server, after the interceptors ran, is still retried and counted")
    void realUnavailableRetried(@Autowired SignedClient signed) {
      assertThat(signed.flaky()).isEqualTo(EchoDownstream.BODY);

      assertThat(attempts()).isEqualTo(2);
      assertThat(CallChecks.failedCalls(resilience, HeadersTestData.SIGNED)).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("on an ad-hoc call through restClient(name)")
  class AdHoc {

    @Test
    @DisplayName("the interceptor's failure reaches the caller as it is")
    void failureAsThrown(@Autowired StratumHttpClients clients) {
      var failure = unavailable();
      log.refuseNextCallWith(failure);

      assertThatThrownBy(
              () ->
                  clients
                      .restClient(HeadersTestData.PLAIN)
                      .get()
                      .uri(EchoDownstream.ECHO)
                      .retrieve()
                      .toBodilessEntity())
          .isSameAs(failure);
    }
  }

  @Nested
  @ContextConfiguration(classes = NestingInterceptorBeans.class)
  @DisplayName("when the interceptor calls another Stratum client that fails")
  class NestedCall {

    @Test
    @DisplayName("the caller gets the innermost failure, and neither client retried or counted")
    void innermostFailure(@Autowired SignedClient signed) {
      var failure = unavailable();
      log.refuseNextCallWith(failure);

      assertThatThrownBy(signed::echo).isSameAs(failure);

      CallChecks.assertNothingRecorded(
          breaker(HeadersTestData.SIGNED), CircuitBreaker.State.CLOSED);
      assertThat(attempts()).isEqualTo(1);
    }
  }

  @Nested
  @TestPropertySource(properties = HttpClientConstants.DEFAULTS + ".retry.delay=10ms")
  @DisplayName("when the other service cannot be reached")
  class Unreachable {

    /**
     * Points the clients at a port that refuses connections.
     *
     * @param registry the test's properties
     */
    @DynamicPropertySource
    static void addresses(DynamicPropertyRegistry registry) {
      HeadersTestData.pointClientsAt(registry, () -> RestClientTestData.UNUSED_URL);
    }

    @Test
    @DisplayName("the failed send, after the interceptors ran, is retried, counted, SX-HTTPC-0002")
    void sendFailureRetried(@Autowired SignedClient signed) {
      CallChecks.assertEndsWith(signed::echo, HttpClientErrorCode.DOWNSTREAM_UNAVAILABLE);

      assertThat(attempts()).isEqualTo(HttpClientConstants.DEFAULT_RETRIES + 1);
      assertThat(CallChecks.failedCalls(resilience, HeadersTestData.SIGNED))
          .isEqualTo(HttpClientConstants.DEFAULT_RETRIES + 1);
    }
  }

  @Nested
  @DisplayName("on a client with a service token")
  class ServiceToken {

    /** The identity provider and the protected service. */
    @AutoClose private static final TokenDownstream SERVER = new TokenDownstream();

    /**
     * Points the clients at the protected service, the signed one with the service's token.
     *
     * @param registry the test's properties
     */
    @DynamicPropertySource
    static void addresses(DynamicPropertyRegistry registry) {
      HeadersTestData.pointClientsAt(registry, SERVER::url);
      registry.add(
          HeadersTestData.SIGNED_SETTINGS + ".auth.client-credentials",
          () -> RestClientTestData.REGISTRATION);
      RestClientTestData.pointRegistrationAtTokenServer(registry, SERVER);
    }

    @Test
    @DisplayName("a 401 from the interceptor fetches no new token and is thrown as is")
    void unauthorizedNotResent(@Autowired SignedClient signed) {
      signed.stock();
      var tokensBefore = SERVER.tokenRequests();
      final var sentBefore = SERVER.authorizations().size();
      var failure =
          HttpClientErrorException.create(
              HttpStatus.UNAUTHORIZED, "", new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
      log.refuseNextCallWith(failure);

      assertThatThrownBy(signed::stock).isSameAs(failure);

      assertThat(SERVER.tokenRequests()).isEqualTo(tokensBefore);
      assertThat(SERVER.authorizations()).hasSize(sentBefore);
    }
  }
}
