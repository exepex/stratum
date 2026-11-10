package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.StratumHttpClients;
import com.exepex.stratum.restclient.testsupport.EchoDownstream;
import com.exepex.stratum.restclient.testsupport.headers.HeadersApplication;
import com.exepex.stratum.restclient.testsupport.headers.HeadersTestData;
import com.exepex.stratum.restclient.testsupport.headers.LoneClient;
import com.exepex.stratum.restclient.testsupport.headers.PlainClient;
import com.exepex.stratum.restclient.testsupport.headers.SignedClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The defaults send {@code X-Origin: shop} and copy {@code X-Tenant}; the signed client adds {@code
 * X-Partner-Id: orders} and its own {@code x-origin}; the lone client sends a fixed {@code
 * X-Tenant} and drops {@code X-Origin} with an empty value.
 */
@SpringBootTest(
    classes = HeadersApplication.class,
    properties = {
      HttpClientConstants.DEFAULTS + ".headers.[X-Origin]=shop",
      HttpClientConstants.DEFAULTS + ".copy-headers=" + HeadersTestData.TENANT,
      HeadersTestData.SIGNED_SETTINGS + ".headers.[X-Partner-Id]=orders",
      HeadersTestData.SIGNED_SETTINGS + ".headers.[x-origin]=signed",
      HttpClientConstants.CLIENTS + ".lone.headers.[X-Tenant]=configured",
      HttpClientConstants.CLIENTS + ".lone.headers.[X-Origin]="
    })
@DisplayName("A client's fixed headers go with each of its calls, one value per name")
class HeadersIntegrationTest {

  /** The header the defaults send, which the signed client overrides. */
  private static final String ORIGIN = "X-Origin";

  /** The copied header's value in the request the service answers. */
  private static final String CALLER = "caller";

  /** The copied header's value a method sets. */
  private static final String METHOD_VALUE = "method";

  /** The server every client calls. */
  @AutoClose private static final EchoDownstream DOWNSTREAM = EchoDownstream.start();

  /**
   * Points the clients at the server.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    HeadersTestData.pointClientsAt(registry, DOWNSTREAM::url);
  }

  /** Forgets the request the test bound, if any. */
  @AfterEach
  void unbindRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Nested
  @DisplayName("set in headers")
  class Fixed {

    @Test
    @DisplayName("a client's calls carry its own headers and the defaults', the client's winning")
    void clientAndDefaultHeaders(@Autowired SignedClient signed) {
      signed.echo();

      var received = DOWNSTREAM.last();
      assertThat(received.values(HeadersTestData.PARTNER_ID)).containsExactly("orders");
      assertThat(received.values(ORIGIN)).containsExactly("signed");
    }

    @Test
    @DisplayName("another client gets the defaults only")
    void otherClientGetsDefaultsOnly(@Autowired PlainClient plain) {
      plain.echo();

      var received = DOWNSTREAM.last();
      assertThat(received.values(HeadersTestData.PARTNER_ID)).isEmpty();
      assertThat(received.values(ORIGIN)).containsExactly("shop");
    }

    @Test
    @DisplayName("an empty value drops the default: the header is not sent at all")
    void emptyValueSendsNothing(@Autowired LoneClient lone) {
      lone.echo();

      assertThat(DOWNSTREAM.last().values(ORIGIN)).isEmpty();
    }

    @Test
    @DisplayName("the client's ad-hoc calls carry them too")
    void adHocCall(@Autowired StratumHttpClients clients) {
      clients
          .restClient(HeadersTestData.SIGNED)
          .get()
          .uri(EchoDownstream.ECHO)
          .retrieve()
          .toBodilessEntity();

      assertThat(DOWNSTREAM.last().values(HeadersTestData.PARTNER_ID)).containsExactly("orders");
    }
  }

  @Nested
  @DisplayName("from several places at once")
  class OneValuePerName {

    @Test
    @DisplayName("the method's own header wins over the copied one, sent once")
    void methodWinsOverCopy(@Autowired SignedClient signed) {
      HeadersTestData.answering(HeadersTestData.TENANT, CALLER);

      signed.echoFor(METHOD_VALUE);

      assertThat(DOWNSTREAM.last().values(HeadersTestData.TENANT)).containsExactly(METHOD_VALUE);
    }

    @Test
    @DisplayName("from the method, a fixed header and the copy at once, the method's is sent, once")
    void methodWinsOverFixedAndCopy(@Autowired StratumHttpClients clients) {
      HeadersTestData.answering(HeadersTestData.TENANT, CALLER);

      clients
          .restClient(HeadersTestData.LONE)
          .get()
          .uri(EchoDownstream.ECHO)
          .header(HeadersTestData.TENANT, METHOD_VALUE)
          .retrieve()
          .toBodilessEntity();

      assertThat(DOWNSTREAM.last().values(HeadersTestData.TENANT)).containsExactly(METHOD_VALUE);
    }

    @Test
    @DisplayName("a fixed header wins over the copied one, sent once")
    void fixedWinsOverCopy(@Autowired LoneClient lone) {
      HeadersTestData.answering(HeadersTestData.TENANT, CALLER);

      lone.echo();

      assertThat(DOWNSTREAM.last().values(HeadersTestData.TENANT)).containsExactly("configured");
    }
  }
}
