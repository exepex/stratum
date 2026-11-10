package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.testsupport.ClientBeanCalls;
import com.exepex.stratum.restclient.testsupport.ClientBeanCalls.ClientBean;
import com.exepex.stratum.restclient.testsupport.QueuedDownstream;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import com.exepex.stratum.restclient.testsupport.TokenDownstream;
import com.exepex.stratum.restclient.testsupport.TokenDownstream.TokenAnswer;
import com.exepex.stratum.restclient.testsupport.beans.ObservedCalls;
import com.exepex.stratum.restclient.testsupport.clients.ClientsApplication;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

/**
 * Each client of this test shows one part of a client's transport through its beans: inventory,
 * pricing and partner a credential of each kind, invoicing a service token, and payments its mocks.
 */
@SpringBootTest(
    classes = {ClientsApplication.class, ObservedCalls.class},
    properties = {
      HttpClientConstants.DEFAULTS + ".retry-delay=10ms",
      RestClientTestData.INVENTORY_SETTINGS + ".auth.bearer=" + RestClientTestData.SECRET,
      RestClientTestData.PRICING_SETTINGS + ".auth.basic.username=orders",
      RestClientTestData.PRICING_SETTINGS + ".auth.basic.password=" + RestClientTestData.SECRET,
      RestClientTestData.PARTNER_SETTINGS + ".auth.api-key=" + RestClientTestData.SECRET,
      ClientBeansTransportIntegrationTest.INVOICING_SETTINGS + ".auth.client-credentials=invoices",
      ClientBeansTransportIntegrationTest.INVOICING_SETTINGS + ".timeout=5s",
      ClientBeansTransportIntegrationTest.INVOICING_SETTINGS + ".total-timeout=1s",
      ClientBeansTransportIntegrationTest.INVOICING_SETTINGS + ".max-connections=1",
      ClientBeansTransportIntegrationTest.INVOICING_SETTINGS + ".max-wait=1s",
      RestClientTestData.PAYMENTS_SETTINGS + ".mock=true",
      RestClientTestData.PAYMENTS_SETTINGS + ".url=" + RestClientTestData.UNUSED_URL
    })
@Timeout(60)
@DisplayName("A client's beans send its credential and its service token on every attempt")
class ClientBeansTransportIntegrationTest {

  /** A client with a service token. */
  static final String INVOICING = "invoicing";

  /** The settings of the invoicing client. */
  static final String INVOICING_SETTINGS = HttpClientConstants.CLIENTS + "." + INVOICING;

  /** A 503, after which a call without a body is sent again. */
  private static final int UNAVAILABLE = HttpStatus.SERVICE_UNAVAILABLE.value();

  /** The attempts of a call answered 503, 503, then 200. */
  private static final int THIRD_ATTEMPT = 3;

  /** The server whose answer changes per attempt. */
  @AutoClose private static final QueuedDownstream QUEUED = QueuedDownstream.start();

  /** The identity provider and the service the invoicing client calls. */
  @AutoClose private static final TokenDownstream TOKENS = new TokenDownstream();

  @Autowired private ApplicationContext context;

  /**
   * Points each client at its server, and the registration at the identity provider.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    registry.add(RestClientTestData.INVENTORY_URL, QUEUED::url);
    registry.add(RestClientTestData.PRICING_URL, QUEUED::url);
    registry.add(RestClientTestData.PARTNER_URL, QUEUED::url);
    registry.add(INVOICING_SETTINGS + ".url", TOKENS::url);
    RestClientTestData.pointRegistrationAtTokenServer(registry, TOKENS);
  }

  /** Lets the identity provider issue tokens again. */
  @AfterEach
  void issueTokensAgain() {
    TOKENS.tokenAnswer(TokenAnswer.ISSUE);
  }

  @Nested
  @DisplayName("credentials")
  class Credentials {

    /** Each kind of fixed credential, its header and its value, on each bean. */
    private static final List<Arguments> FIXED_CREDENTIALS =
        ClientBeanCalls.BEANS.stream()
            .flatMap(
                bean ->
                    List.of(
                        Arguments.of(
                            bean,
                            RestClientTestData.INVENTORY,
                            HttpHeaders.AUTHORIZATION,
                            "Bearer " + RestClientTestData.SECRET),
                        Arguments.of(
                            bean,
                            RestClientTestData.PRICING,
                            HttpHeaders.AUTHORIZATION,
                            "Basic "
                                + Base64.getEncoder()
                                    .encodeToString(
                                        ("orders:" + RestClientTestData.SECRET)
                                            .getBytes(StandardCharsets.UTF_8))),
                        Arguments.of(
                            bean,
                            RestClientTestData.PARTNER,
                            HttpClientConstants.DEFAULT_API_KEY_HEADER,
                            RestClientTestData.SECRET))
                        .stream())
            .toList();

    @Test
    @DisplayName("the RestTemplate calls a relative path on the client's address, with its token")
    void restTemplateCarriesAddressAndCredential() {
      ClientBeanCalls.restTemplate(context, RestClientTestData.INVENTORY)
          .getForObject(ScriptedDownstream.STOCK, String.class);

      assertThat(QUEUED.requests().getLast()).isEqualTo("GET " + ScriptedDownstream.STOCK);
      assertThat(QUEUED.headerValues(HttpHeaders.AUTHORIZATION).getLast())
          .isEqualTo("Bearer " + RestClientTestData.SECRET);
    }

    @ParameterizedTest
    @FieldSource("FIXED_CREDENTIALS")
    @DisplayName("bearer, basic and API key go on each attempt of a retried call")
    void credentialOnEachAttempt(ClientBean bean, String client, String header, String value) {
      QUEUED.answerNext(UNAVAILABLE, UNAVAILABLE);

      assertThat(ClientBeanCalls.get(context, bean, client, ScriptedDownstream.STOCK))
          .isEqualTo(ScriptedDownstream.BODY);
      var sent = QUEUED.headerValues(header);
      assertThat(sent.subList(sent.size() - THIRD_ATTEMPT, sent.size())).containsOnly(value);
    }
  }

  @Nested
  @DisplayName("a service token")
  class ServiceToken {

    @ParameterizedTest
    @FieldSource(ClientBeanCalls.BOTH_BEANS)
    @DisplayName("is renewed after a 401 for a GET; a POST with a body gets its 401")
    void renewedAfterUnauthorized(ClientBean bean) {
      invoicingGet(bean);
      TOKENS.refuseIssuedTokens();

      assertThat(invoicingGet(bean)).isEqualTo(ScriptedDownstream.BODY);
      var sent = TOKENS.authorizations();
      assertThat(sent.getLast()).isNotEqualTo(sent.reversed().get(1));
      TOKENS.refuseIssuedTokens();
      var callsBefore = TOKENS.authorizations().size();

      assertThatExceptionOfType(HttpClientErrorException.Unauthorized.class)
          .isThrownBy(
              () ->
                  ClientBeanCalls.post(
                      context, bean, INVOICING, ScriptedDownstream.STOCK, "invoice"));
      assertThat(TOKENS.authorizations()).hasSize(callsBefore + 1);
    }

    @ParameterizedTest
    @FieldSource(ClientBeanCalls.BOTH_BEANS)
    @DisplayName("that cannot be renewed frees the 401's connection for the next call")
    void refusedRenewalFreesConnection(ClientBean bean) {
      invoicingGet(bean);
      TOKENS.refuseIssuedTokens();
      TOKENS.tokenAnswer(TokenAnswer.REFUSE);

      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(() -> invoicingGet(bean))
          .withMessageStartingWith("SX-HTTPC-0002");
      TOKENS.tokenAnswer(TokenAnswer.ISSUE);

      assertThat(invoicingGet(bean)).isEqualTo(ScriptedDownstream.BODY);
    }

    /** Gets the protected service's stock through one of the invoicing client's beans. */
    private String invoicingGet(ClientBean bean) {
      return ClientBeanCalls.get(context, bean, INVOICING, ScriptedDownstream.STOCK);
    }
  }

  @Nested
  @DisplayName("when its token endpoint does not answer")
  class HeldToken {

    /**
     * The latest end of a call whose token fetch is held: the client's total-timeout of 1s, its
     * first fetch's start-up and some slack, well below its timeout of 5s.
     */
    private static final Duration TOKEN_WAIT_ENDS_BEFORE = Duration.ofSeconds(3);

    /** The identity provider of this group, which has issued no token, so each call fetches. */
    @AutoClose private static final TokenDownstream HOLDING = new TokenDownstream();

    /**
     * Points the invoicing client and the registration at this group's server.
     *
     * @param registry the test's properties
     */
    @DynamicPropertySource
    static void holdingServer(DynamicPropertyRegistry registry) {
      registry.add(INVOICING_SETTINGS + ".url", HOLDING::url);
      RestClientTestData.pointRegistrationAtTokenServer(registry, HOLDING);
    }

    @ParameterizedTest
    @FieldSource(ClientBeanCalls.BOTH_BEANS)
    @DisplayName("the token is fetched inside the attempt, so the call ends by total-timeout")
    void fetchedWithinTotalTimeout(ClientBean bean) {
      final var tokenRequestsBefore = HOLDING.tokenRequests();
      HOLDING.tokenAnswer(TokenAnswer.HOLD);
      var startedAt = System.nanoTime();

      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(() -> ClientBeanCalls.get(context, bean, INVOICING, ScriptedDownstream.STOCK))
          .withMessageStartingWith("SX-HTTPC-0002");
      assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
          .isLessThan(TOKEN_WAIT_ENDS_BEFORE);
      assertThat(HOLDING.tokenRequests()).isEqualTo(tokenRequestsBefore + 1);
    }
  }

  @Nested
  @DisplayName("mocks and observations")
  class MocksAndObservations {

    @ParameterizedTest
    @FieldSource(ClientBeanCalls.BOTH_BEANS)
    @DisplayName("a mocked client's beans answer 501, a POST with a body too")
    void mockedClientAnswers501(ClientBean bean) {
      assertThatExceptionOfType(HttpServerErrorException.NotImplemented.class)
          .isThrownBy(
              () -> ClientBeanCalls.get(context, bean, RestClientTestData.PAYMENTS, "/payments"));
      assertThatExceptionOfType(HttpServerErrorException.NotImplemented.class)
          .isThrownBy(
              () ->
                  ClientBeanCalls.post(
                      context, bean, RestClientTestData.PAYMENTS, "/payments", "payment"));
    }

    @ParameterizedTest
    @FieldSource(ClientBeanCalls.BOTH_BEANS)
    @DisplayName("a retried GET is one recorded call, with the last answer")
    void retriedCallRecordedOnce(ClientBean bean, @Autowired ObservedCalls observed) {
      var requestsBefore = QUEUED.requests().size();
      final var recordedBefore = observed.recordedCalls();
      QUEUED.answerNext(UNAVAILABLE);

      ClientBeanCalls.get(context, bean, RestClientTestData.INVENTORY, ScriptedDownstream.STOCK);

      assertThat(QUEUED.requests()).hasSize(requestsBefore + 2);
      assertThat(observed.recordedCalls()).isEqualTo(recordedBefore + 1);
    }
  }
}
