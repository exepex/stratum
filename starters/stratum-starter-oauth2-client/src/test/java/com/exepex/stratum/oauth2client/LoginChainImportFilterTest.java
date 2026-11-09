package com.exepex.stratum.oauth2client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;

@DisplayName("Boot's OAuth2 client login chain")
class LoginChainImportFilterTest {

  /** Another auto-configuration, which is always kept. */
  private static final String OTHER =
      "org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration";

  /** The auto-configurations the filter is asked about, a null entry among them. */
  private static final String[] CANDIDATES = {OTHER, LoginChainImportFilter.LOGIN_CHAIN, null};

  /** No metadata, which the filter does not read. */
  private static final AutoConfigurationMetadata NO_METADATA = null;

  @Nested
  @DisplayName("is left out without Boot's security set-up")
  class WithoutBootSecurity {

    @Test
    @DisplayName("and every other auto-configuration is kept")
    void leavesOutLoginChain() {
      var filter = new LoginChainImportFilter();
      filter.setBeanClassLoader(Thread.currentThread().getContextClassLoader());

      assertThat(filter.match(CANDIDATES, NO_METADATA)).containsExactly(true, false, true);
    }
  }

  @Nested
  @DisplayName("is kept with Boot's security set-up")
  class WithBootSecurity {

    @Test
    @DisplayName("as when the service adds Spring Security itself")
    void keepsLoginChain() {
      var filter = new LoginChainImportFilter(String.class.getName());
      filter.setBeanClassLoader(Thread.currentThread().getContextClassLoader());

      assertThat(filter.match(CANDIDATES, NO_METADATA)).containsExactly(true, true, true);
    }
  }
}
