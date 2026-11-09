package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;

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

  private final IssuingTokenStore store = new IssuingTokenStore();

  private final ServiceTokenResend resend =
      new ServiceTokenResend(
          HttpClientProperties.builder()
              .clients(
                  Map.of(
                      RestClientTestData.INVENTORY,
                      ClientSettings.builder()
                          .auth(
                              ClientAuth.builder()
                                  .clientCredentials(RestClientTestData.REGISTRATION)
                                  .build())
                          .build()))
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
      throw HttpClientErrorException.create(
          HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY, new byte[0], null);
    }
    return sent;
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
}
