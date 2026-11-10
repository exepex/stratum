package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.restclient.StratumHttpClients;
import com.exepex.stratum.restclient.testsupport.EchoDownstream;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.generated.PartnerOrdersApi;
import com.exepex.stratum.restclient.testsupport.headers.AuditInterceptor;
import com.exepex.stratum.restclient.testsupport.headers.CallLog;
import com.exepex.stratum.restclient.testsupport.headers.FirstInterceptor;
import com.exepex.stratum.restclient.testsupport.headers.HeadersApplication;
import com.exepex.stratum.restclient.testsupport.headers.HeadersTestData;
import com.exepex.stratum.restclient.testsupport.headers.LoneClient;
import com.exepex.stratum.restclient.testsupport.headers.OrderedClient;
import com.exepex.stratum.restclient.testsupport.headers.PlainClient;
import com.exepex.stratum.restclient.testsupport.headers.SignedClient;
import com.exepex.stratum.restclient.testsupport.headers.SigningInterceptor;
import com.exepex.stratum.restclient.testsupport.headers.StampInterceptor;
import java.util.Map;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The audit interceptor is global and also named by the signed and lone clients; the signing one is
 * the signed client's own. The signed client sends a bearer token, and the partner client is a
 * generated interface.
 */
@SpringBootTest(
    classes = HeadersApplication.class,
    properties = {
      HeadersTestData.SIGNED_SETTINGS + ".auth.bearer=" + RestClientTestData.SECRET,
      RestClientTestData.PARTNER_INTERFACE
          + "=com.exepex.stratum.restclient.testsupport.generated.PartnerOrdersApi"
    })
@DisplayName("Interceptors change each call of their clients, after the credential, in order")
class InterceptorIntegrationTest {

  /** The server every client calls. */
  @AutoClose private static final EchoDownstream DOWNSTREAM = EchoDownstream.start();

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

  @Nested
  @DisplayName("on each client")
  class Scope {

    @Test
    @DisplayName("the global interceptor runs first, then the client's own, after the credential")
    void globalThenOwnAfterCredential(@Autowired SignedClient signed) {
      signed.echo();

      assertThat(log.interceptors())
          .containsExactly(AuditInterceptor.NAME, SigningInterceptor.NAME);
      assertThat(log.runs())
          .allMatch(run -> ("Bearer " + RestClientTestData.SECRET).equals(run.authorization()));
      assertThat(DOWNSTREAM.last().values(HeadersTestData.SIGNATURE)).containsExactly("none");
    }

    @Test
    @DisplayName("globals run in @Order order, then the client's own in the order it names them")
    void orderOfGlobalsAndOwn(@Autowired OrderedClient ordered) {
      ordered.echo();

      assertThat(log.interceptors())
          .containsExactly(
              FirstInterceptor.NAME,
              AuditInterceptor.NAME,
              SigningInterceptor.NAME,
              StampInterceptor.NAME);
    }

    @Test
    @DisplayName("a client that names none gets the global one only, and no credential")
    void otherClientGlobalOnly(@Autowired PlainClient plain) {
      plain.echo();

      assertThat(log.interceptors()).containsExactly(AuditInterceptor.NAME);
      assertThat(log.runs().getFirst().authorization()).isEmpty();
      assertThat(DOWNSTREAM.last().values(HeadersTestData.SIGNATURE)).isEmpty();
    }

    @Test
    @DisplayName("they run on the client's ad-hoc calls too")
    void adHocCall(@Autowired StratumHttpClients clients) {
      clients
          .restClient(HeadersTestData.SIGNED)
          .get()
          .uri(EchoDownstream.ECHO)
          .retrieve()
          .toBodilessEntity();

      assertThat(log.interceptors())
          .containsExactly(AuditInterceptor.NAME, SigningInterceptor.NAME);
    }

    @Test
    @DisplayName("the global one runs on a generated interface, which names the client it is for")
    void generatedInterface(@Autowired PartnerOrdersApi partner) {
      partner.orders();

      assertThat(log.runs()).singleElement().matches(run -> "partner".equals(run.client()));
    }
  }

  @Nested
  @DisplayName("when a global interceptor is also named by a client")
  class Dedupe {

    @Test
    @DisplayName("it runs once on that client, with the client's own")
    void namedGlobalRunsOnce(@Autowired SignedClient signed) {
      signed.echo();

      assertThat(log.interceptors()).containsOnlyOnce(AuditInterceptor.NAME);
    }

    @Test
    @DisplayName("named as a client's only interceptor, it runs once there and still on the others")
    void namedAloneRunsOnceAndElsewhere(@Autowired LoneClient lone, @Autowired PlainClient plain) {
      lone.echo();
      plain.echo();

      assertThat(log.runs())
          .extracting(CallLog.Run::client)
          .containsExactly(HeadersTestData.LONE, HeadersTestData.PLAIN);
    }
  }

  @Nested
  @DisplayName("when an interceptor fails")
  class Failure {

    @Test
    @DisplayName("the call fails with its exception, and nothing is sent")
    void nothingSent(@Autowired PlainClient plain) {
      plain.echo();
      var before = DOWNSTREAM.requestCount();
      log.refuseNextCall();

      assertThatThrownBy(plain::echo).isInstanceOf(IllegalStateException.class);

      assertThat(DOWNSTREAM.requestCount()).isEqualTo(before);
    }
  }

  @Nested
  @DisplayName("what an interceptor sees")
  class Sight {

    @Test
    @DisplayName("setting a header the call already has replaces its value")
    void setHeaderReplaces(@Autowired OrderedClient ordered) {
      ordered.echo();

      assertThat(DOWNSTREAM.last().values(HeadersTestData.SIGNATURE))
          .containsExactly(StampInterceptor.NAME);
    }

    @Test
    @DisplayName("the call's text names the client, method and path, never a header or the query")
    void textHidesHeadersAndQuery(@Autowired StratumHttpClients clients) {
      clients
          .restClient(HeadersTestData.SIGNED)
          .get()
          .uri(EchoDownstream.ECHO + "?customer=priya")
          .retrieve()
          .toBodilessEntity();

      assertThat(log.runs().getFirst().text())
          .isEqualTo("DefaultOutgoingRequest(client=signed, method=GET, path=/echo)")
          .doesNotContain(RestClientTestData.SECRET)
          .doesNotContain("priya");
    }
  }

  @Nested
  @TestPropertySource(properties = HeadersTestData.PLAIN_SETTINGS + ".mock=true")
  @DisplayName("on a client set to mock: true")
  class Mocked {

    @Test
    @DisplayName("the global interceptor runs, and the answer comes from the client's mock file")
    void mockAnswersThroughSteps(@Autowired PlainClient plain) {
      var before = DOWNSTREAM.requestCount();

      assertThat(plain.echo()).isEqualToIgnoringWhitespace("\"mocked\"");

      assertThat(log.interceptors()).containsExactly(AuditInterceptor.NAME);
      assertThat(DOWNSTREAM.requestCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("a POST with a body answers from the mock file too, through the same steps")
    void mockAnswersPostWithBody(@Autowired PlainClient plain) {
      var before = DOWNSTREAM.requestCount();

      assertThat(plain.send(Map.of("id", "7"))).isEqualToIgnoringWhitespace("\"mocked order\"");

      assertThat(log.interceptors()).containsExactly(AuditInterceptor.NAME);
      assertThat(DOWNSTREAM.requestCount()).isEqualTo(before);
    }
  }
}
