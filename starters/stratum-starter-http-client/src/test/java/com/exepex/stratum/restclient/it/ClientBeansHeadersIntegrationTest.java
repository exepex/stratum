package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.testsupport.EchoDownstream;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.headers.AuditInterceptor;
import com.exepex.stratum.restclient.testsupport.headers.CallLog;
import com.exepex.stratum.restclient.testsupport.headers.HeadersApplication;
import com.exepex.stratum.restclient.testsupport.headers.HeadersTestData;
import com.exepex.stratum.restclient.testsupport.headers.SigningInterceptor;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The signed client's RestClient and RestTemplate beans: a fixed X-Partner-Id, the copied X-Tenant
 * and a bearer token, with the audit and signing interceptors.
 */
@SpringBootTest(
    classes = HeadersApplication.class,
    properties = {
      HttpClientConstants.DEFAULTS + ".copy-headers=" + HeadersTestData.TENANT,
      HeadersTestData.SIGNED_SETTINGS + ".headers.[X-Partner-Id]=orders",
      HeadersTestData.SIGNED_SETTINGS + ".auth.bearer=" + RestClientTestData.SECRET
    })
@DisplayName(
    "A client's RestClient and RestTemplate beans send its headers and run its interceptors")
class ClientBeansHeadersIntegrationTest {

  /** The server every client calls. */
  @AutoClose private static final EchoDownstream DOWNSTREAM = EchoDownstream.start();

  @Autowired
  @Qualifier(HeadersTestData.SIGNED)
  private RestClient restClient;

  @Autowired
  @Qualifier(HeadersTestData.SIGNED)
  private RestTemplate restTemplate;

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

  /** Answers a request with the tenant acme, and forgets earlier calls. */
  @BeforeEach
  void answerTenant() {
    log.clear();
    HeadersTestData.answering(HeadersTestData.TENANT, "acme");
  }

  /** Forgets the request the test bound. */
  @AfterEach
  void unbindRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  /** Checks that the last call carried the fixed and the copied header, once each. */
  private void assertHeadersSent() {
    assertThat(DOWNSTREAM.last().values(HeadersTestData.PARTNER_ID)).containsExactly("orders");
    assertThat(DOWNSTREAM.last().values(HeadersTestData.TENANT)).containsExactly("acme");
  }

  @Test
  @DisplayName("the RestClient bean sends the fixed and the copied header")
  void restClientSendsHeaders() {
    restClient.get().uri(EchoDownstream.ECHO).retrieve().toBodilessEntity();

    assertHeadersSent();
  }

  @Test
  @DisplayName("the RestTemplate bean sends the fixed and the copied header")
  void restTemplateSendsHeaders() {
    restTemplate.getForObject(EchoDownstream.ECHO, String.class);

    assertHeadersSent();
  }

  @Test
  @DisplayName("on a retried GET, the interceptors run on each attempt, after the credential")
  void interceptorsOnEachAttempt() {
    restClient.get().uri(EchoDownstream.FLAKY).retrieve().toBodilessEntity();

    assertThat(log.interceptors())
        .containsExactly(
            AuditInterceptor.NAME,
            SigningInterceptor.NAME,
            AuditInterceptor.NAME,
            SigningInterceptor.NAME);
    assertThat(log.runs())
        .allMatch(run -> ("Bearer " + RestClientTestData.SECRET).equals(run.authorization()));
  }

  @Test
  @DisplayName("an interceptor's failure reaches the bean's caller as it was thrown")
  void interceptorFailureAsThrown() {
    var failure =
        HttpServerErrorException.create(
            HttpStatus.SERVICE_UNAVAILABLE,
            "",
            new HttpHeaders(),
            new byte[0],
            StandardCharsets.UTF_8);
    log.refuseNextCallWith(failure);

    assertThatThrownBy(() -> restTemplate.getForObject(EchoDownstream.ECHO, String.class))
        .isSameAs(failure);
  }
}
