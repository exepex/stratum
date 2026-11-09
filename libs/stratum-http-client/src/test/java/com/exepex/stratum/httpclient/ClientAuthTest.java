package com.exepex.stratum.httpclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.httpclient.testsupport.HttpClientTestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("A client's auth never shows a secret and fills the API key's header")
class ClientAuthTest {

  @Nested
  @DisplayName("its text")
  class Text {

    @Test
    @DisplayName("names the kind only, as does that of Basic credentials")
    void toStringHidesSecrets() {
      var auth =
          ClientAuth.builder()
              .basic(new BasicCredentials(HttpClientTestData.SECRET, HttpClientTestData.SECRET))
              .build();
      var settings = ClientSettings.builder().auth(auth).build();

      assertThat(settings.toString())
          .doesNotContain(HttpClientTestData.SECRET)
          .contains("basic=true");
      assertThat(auth.basic().toString()).doesNotContain(HttpClientTestData.SECRET);
    }
  }

  @Nested
  @DisplayName("an API key")
  class ApiKey {

    @Test
    @DisplayName("goes in X-Api-Key when no header is named")
    void apiKeyDefaultHeader() {
      var auth = ClientAuth.builder().apiKey(HttpClientTestData.SECRET).build();

      assertThat(auth.apiKeyHeader()).isEqualTo(HttpClientConstants.DEFAULT_API_KEY_HEADER);
      assertThat(auth.toString()).doesNotContain(HttpClientTestData.SECRET);
    }
  }
}
