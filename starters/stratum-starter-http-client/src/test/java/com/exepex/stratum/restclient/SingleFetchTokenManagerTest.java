package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.restclient.testsupport.IssuingTokenStore;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("The service's token")
class SingleFetchTokenManagerTest {

  /** The token the other service refused. */
  private static final String REFUSED = "refused";

  private final IssuingTokenStore store = new IssuingTokenStore();

  /** Returns the token a call starting now gets. */
  private String token() {
    return store
        .manager()
        .token(
            RestClientTestData.INVENTORY, RestClientTestData.REGISTRATION, RestClientTestData.WAIT)
        .getTokenValue();
  }

  /** Replaces the refused token, as a resend after a 401 does. */
  private void renew() {
    store
        .manager()
        .renew(
            RestClientTestData.INVENTORY,
            RestClientTestData.REGISTRATION,
            REFUSED,
            RestClientTestData.WAIT);
  }

  @Nested
  @DisplayName("is kept until a minute before it expires")
  class Expiry {

    @Test
    @DisplayName("a stored token with more than a minute left is sent, with no fetch")
    void keepsValidToken() {
      store.store("valid", IssuingTokenStore.LIFETIME);

      assertThat(token()).isEqualTo("valid");
      assertThat(store.issued()).isZero();
    }

    @Test
    @DisplayName("a stored token with less than a minute left is fetched again")
    void fetchesTokenAboutToExpire() {
      store.store("about-to-expire", IssuingTokenStore.SHORT_LIFETIME);

      assertThat(token()).isEqualTo("t-1");
      assertThat(store.issued()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("is replaced after a 401 as one fetch of its registration")
  class Renew {

    @Test
    @DisplayName("the refused token is replaced by a new one")
    void replacesRefusedToken() {
      store.store(REFUSED, IssuingTokenStore.LIFETIME);

      renew();

      assertThat(store.storedValue()).contains("t-1");
      assertThat(store.issued()).isEqualTo(1);
    }

    @Test
    @DisplayName("a newer token that another call stored stays, with no fetch")
    void keepsNewerToken() {
      store.store("newer", IssuingTokenStore.LIFETIME);

      renew();

      assertThat(store.storedValue()).contains("newer");
      assertThat(store.issued()).isZero();
    }

    @Test
    @DisplayName(
        "a call that needs a token meanwhile waits for the new one, not dropped or fetched twice")
    void otherFetchWaitsForRenewal() {
      store.store(REFUSED, IssuingTokenStore.SHORT_LIFETIME);
      store.afterNextRead(SingleFetchTokenManagerTest.this::token, RestClientTestData.SHORT);

      renew();

      assertThat(store.storedValue()).contains("t-1");
      assertThat(store.issued()).isEqualTo(1);
    }
  }
}
