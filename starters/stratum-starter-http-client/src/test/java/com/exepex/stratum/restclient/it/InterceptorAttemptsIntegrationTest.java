package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.restclient.testsupport.EchoDownstream;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.TokenDownstream;
import com.exepex.stratum.restclient.testsupport.headers.AuditInterceptor;
import com.exepex.stratum.restclient.testsupport.headers.CallLog;
import com.exepex.stratum.restclient.testsupport.headers.HeadersApplication;
import com.exepex.stratum.restclient.testsupport.headers.HeadersTestData;
import com.exepex.stratum.restclient.testsupport.headers.SignedClient;
import com.exepex.stratum.restclient.testsupport.headers.SigningInterceptor;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Each attempt of a call is a new request, so the interceptors run again on each. */
@SpringBootTest(classes = HeadersApplication.class)
@DisplayName("Interceptors run on every attempt of a call, after that attempt's credential")
class InterceptorAttemptsIntegrationTest {

  @Autowired private CallLog log;

  @Autowired private SignedClient signed;

  /** Forgets what earlier tests' calls recorded. */
  @BeforeEach
  void clearLog() {
    log.clear();
  }

  @Nested
  @DisplayName("on a retried GET")
  class Retried {

    /** The server whose flaky path answers 503 first. */
    @AutoClose private static final EchoDownstream DOWNSTREAM = EchoDownstream.start();

    /**
     * Points the clients at the server, the signed one with a bearer token.
     *
     * @param registry the test's properties
     */
    @DynamicPropertySource
    static void addresses(DynamicPropertyRegistry registry) {
      HeadersTestData.pointClientsAt(registry, DOWNSTREAM::url);
      registry.add(
          HeadersTestData.SIGNED_SETTINGS + ".auth.bearer", () -> RestClientTestData.SECRET);
    }

    @Test
    @DisplayName("they run on both attempts, each after the credential")
    void bothAttempts() {
      assertThat(signed.flaky()).isEqualTo(EchoDownstream.BODY);

      assertThat(log.interceptors())
          .containsExactly(
              AuditInterceptor.NAME,
              SigningInterceptor.NAME,
              AuditInterceptor.NAME,
              SigningInterceptor.NAME);
      assertThat(log.runs())
          .allMatch(run -> ("Bearer " + RestClientTestData.SECRET).equals(run.authorization()));
    }
  }

  @Nested
  @DisplayName("on a call sent again with a new service token after a 401")
  class Resent {

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
    @DisplayName("they run on both sends, each seeing that send's token, which is kept")
    void bothSendsWithTheirTokens() {
      signed.stock();
      SERVER.refuseIssuedTokens();
      log.clear();

      signed.stock();

      assertThat(log.runs())
          .extracting(CallLog.Run::authorization)
          .containsExactly(
              "Bearer service-1", "Bearer service-1", "Bearer service-2", "Bearer service-2");
      assertThat(SERVER.authorizations().getLast()).isEqualTo("Bearer service-2");
    }
  }
}
