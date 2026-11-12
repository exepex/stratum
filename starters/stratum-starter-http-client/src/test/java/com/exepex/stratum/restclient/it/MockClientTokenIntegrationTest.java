package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.restclient.DownstreamException;
import com.exepex.stratum.restclient.MockClients;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.TokenDownstream;
import com.exepex.stratum.restclient.testsupport.payments.PaymentClient;
import com.exepex.stratum.restclient.testsupport.payments.PaymentsApplication;
import com.exepex.stratum.restclient.testsupport.payments.PaymentsSetup;
import java.util.Map;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The payments client with mock: true and a Spring Security token as its auth. Each group sets one
 * kind, since a client takes only one, so this is not a group of {@link MockClientIntegrationTest},
 * whose client sends a bearer token. Each group has its own identity provider, so that its count of
 * token requests starts at 0.
 */
@SpringBootTest(
    classes = PaymentsApplication.class,
    properties = RestClientTestData.PAYMENTS_SETTINGS + ".mock=true")
@Timeout(30)
@DisplayName("A client with mock: true and a Spring Security token never fetches or sends a token")
class MockClientTokenIntegrationTest {

  /** The answer the file lastPayment.json holds. */
  private static final Map<String, Object> FILE_ANSWER = Map.of("id", "p-last", "status", "PAID");

  /** The method a test calls, answered by the file lastPayment.json. */
  private static final String LAST_PAYMENT = "lastPayment";

  @Autowired private PaymentClient payments;

  @Autowired private PaymentsSetup paymentsSetup;

  /** Checks that a call is answered from its file, with no token sent and none fetched. */
  private void assertAnsweredWithoutToken(TokenDownstream provider) {
    assertThat(payments.lastPayment()).isEqualTo(FILE_ANSWER);
    assertThat(paymentsSetup.lastAuthorization()).isNull();
    assertThat(provider.tokenRequests()).isZero();
  }

  @Nested
  @TestPropertySource(
      properties =
          RestClientTestData.PAYMENTS_SETTINGS
              + ".auth.client-credentials="
              + RestClientTestData.REGISTRATION)
  @DisplayName("with client-credentials")
  class ClientCredentials {

    /** The identity provider of this group, which is also the client's address. */
    @AutoClose private static final TokenDownstream PROVIDER = new TokenDownstream();

    @Autowired private MockClients mockClients;

    /**
     * Points the client and the registration at this group's identity provider.
     *
     * @param registry the test's properties
     */
    @DynamicPropertySource
    static void providerAddress(DynamicPropertyRegistry registry) {
      RestClientTestData.pointPaymentsAtTokenServer(registry, PROVIDER);
    }

    @Test
    @DisplayName("answers from its file, and the token endpoint gets no request")
    void fetchesNoToken() {
      assertAnsweredWithoutToken(PROVIDER);
    }

    @Test
    @DisplayName("passes back a scripted 401, with no token fetched or renewed")
    void scripted401RenewsNoToken() {
      mockClients.of(PaymentClient.class).on(LAST_PAYMENT).fail(HttpStatus.UNAUTHORIZED);

      assertThatThrownBy(payments::lastPayment)
          .isInstanceOfSatisfying(
              DownstreamException.class,
              refused -> assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
      assertThat(PROVIDER.tokenRequests()).isZero();
    }
  }

  @Nested
  @TestPropertySource(properties = RestClientTestData.PAYMENTS_SETTINGS + ".auth.token-relay=true")
  @DisplayName("with token-relay")
  class TokenRelay {

    /** The identity provider of this group, which is also the client's address. */
    @AutoClose private static final TokenDownstream PROVIDER = new TokenDownstream();

    /**
     * Points the client and the registration at this group's identity provider.
     *
     * @param registry the test's properties
     */
    @DynamicPropertySource
    static void providerAddress(DynamicPropertyRegistry registry) {
      RestClientTestData.pointPaymentsAtTokenServer(registry, PROVIDER);
    }

    @Test
    @DisplayName("answers from its file when no one is signed in, with no SX-HTTPC-0004")
    void needsNoCallerToken() {
      assertAnsweredWithoutToken(PROVIDER);
    }
  }
}
