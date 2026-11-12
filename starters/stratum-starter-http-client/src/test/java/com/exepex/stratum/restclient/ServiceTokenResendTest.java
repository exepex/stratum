package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.httpclient.ClientAuth;
import com.exepex.stratum.httpclient.ClientSettings;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.restclient.testsupport.IssuingTokenStore;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

@DisplayName("A call with a service token")
class ServiceTokenResendTest {

  /** The auth of both clients: the service's token of the test registration. */
  private static final ClientAuth SERVICE_TOKEN =
      ClientAuth.builder().clientCredentials(RestClientTestData.REGISTRATION).build();

  private final IssuingTokenStore store = new IssuingTokenStore();

  private final ServiceTokenResend resend =
      new ServiceTokenResend(
          HttpClientProperties.builder()
              .clients(
                  Map.of(
                      RestClientTestData.INVENTORY,
                      ClientSettings.builder().auth(SERVICE_TOKEN).build(),
                      RestClientTestData.PAYMENTS,
                      ClientSettings.builder().mock(true).auth(SERVICE_TOKEN).build()))
              .build(),
          store.manager(),
          new ClientWarnings(Clock.systemUTC()));

  private final AtomicInteger attempts = new AtomicInteger();

  /** Returns the token the attempt's request sends, as the client's initializer reads it. */
  private String sentToken() {
    return store
        .manager()
        .token(
            RestClientTestData.INVENTORY, RestClientTestData.REGISTRATION, RestClientTestData.WAIT)
        .getTokenValue();
  }

  /** Sends the token; the first send is refused after another call stored a newer one. */
  private String attempt() {
    var sent = sentToken();
    if (attempts.getAndIncrement() == 0) {
      store.store("stored-by-another-call", IssuingTokenStore.LIFETIME);
      throw unauthorized();
    }
    return sent;
  }

  /** Counts the attempt and refuses it with a 401, as a mock scripted to fail does. */
  private String refuse() {
    attempts.incrementAndGet();
    throw unauthorized();
  }

  /** Returns the 401 the other service answers. */
  private HttpClientErrorException unauthorized() {
    return HttpClientErrorException.create(
        HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY, new byte[0], null);
  }

  @Nested
  @DisplayName("after a 401, replaces the token the attempt sent, keeping a newer one")
  class Resend {

    @Test
    @DisplayName("the resend sends the newer stored token, not the refused one, with no fetch")
    void replacesTokenSent() {
      store.store("before-the-call", IssuingTokenStore.LIFETIME);

      assertThat(
              resend.call(
                  RestClientTestData.INVENTORY, false, ServiceTokenResendTest.this::attempt))
          .isEqualTo("stored-by-another-call");
      assertThat(attempts).hasValue(2);
      assertThat(store.issued()).isZero();
    }
  }

  @Nested
  @DisplayName("fetches its token before the attempt creates its request")
  class Fetch {

    @Test
    @DisplayName("so the request's timeouts start after the fetch")
    void fetchesTokenBeforeAttempt() {
      assertThat(resend.call(RestClientTestData.INVENTORY, false, store::issued)).isEqualTo(1);
    }

    @Test
    @DisplayName("also when the request's body is streamed")
    void fetchesTokenBeforeStreamedAttempt() {
      assertThat(resend.call(RestClientTestData.INVENTORY, true, store::issued)).isEqualTo(1);
    }

    @Test
    @DisplayName("and the request sends that token, even one that lives less than the skew")
    void sendsFetchedToken() {
      store.issueFor(IssuingTokenStore.SHORT_LIFETIME);

      assertThat(
              resend.call(
                  RestClientTestData.INVENTORY, false, ServiceTokenResendTest.this::sentToken))
          .isEqualTo("t-1");
      assertThat(store.issued()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("of a client with mock: true runs the attempt as it is")
  class Mocked {

    @Test
    @DisplayName("with no token fetched")
    void fetchesNoToken() {
      assertThat(resend.call(RestClientTestData.PAYMENTS, false, store::issued)).isZero();
    }

    @Test
    @DisplayName("and passes back a 401 once, with no token fetched or renewed")
    void passesBack401() {
      assertThatThrownBy(
              () ->
                  resend.call(
                      RestClientTestData.PAYMENTS, false, ServiceTokenResendTest.this::refuse))
          .isInstanceOf(HttpClientErrorException.Unauthorized.class);
      assertThat(attempts).hasValue(1);
      assertThat(store.issued()).isZero();
    }
  }
}
