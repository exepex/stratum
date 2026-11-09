package com.exepex.stratum.httpclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.httpclient.testsupport.HttpClientTestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("A client's auth never shows a secret and fills the API key's header")
class ClientAuthTest {

  @Test
  @DisplayName("its text and that of Basic credentials name the kind only")
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

  @Test
  @DisplayName("an API key without a header goes in X-Api-Key")
  void apiKeyDefaultHeader() {
    var auth = ClientAuth.builder().apiKey(HttpClientTestData.SECRET).build();

    assertThat(auth.apiKeyHeader()).isEqualTo(HttpClientConstants.DEFAULT_API_KEY_HEADER);
    assertThat(auth.toString()).doesNotContain(HttpClientTestData.SECRET);
  }
}
