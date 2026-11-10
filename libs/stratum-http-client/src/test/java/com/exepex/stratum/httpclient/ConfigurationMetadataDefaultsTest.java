package com.exepex.stratum.httpclient;

import com.exepex.stratum.core.condition.StratumModuleSwitch;
import com.exepex.stratum.httpclient.testsupport.HttpClientTestData;
import com.exepex.stratum.httpclient.testsupport.SettingsChecks;
import com.exepex.stratum.test.MetadataDefaultChecks;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The HTTP client library's metadata lists the defaults the module uses")
class ConfigurationMetadataDefaultsTest {

  /**
   * An API key for the inventory client, so that its auth keys may be set: auth is refused under
   * defaults, and api-key-header and token-relay are refused without a kind.
   */
  private static final String INVENTORY_API_KEY =
      HttpClientTestData.INVENTORY_AUTH + ".api-key=" + HttpClientTestData.SECRET;

  @Test
  @DisplayName("each listed default is the value a client uses when its property is unset")
  void listsTheDefaultsInEffect() throws IOException {
    MetadataDefaultChecks.checkMetadataDefaults(this::inEffect);
  }

  /**
   * Returns the module switch and the inventory client's settings, with each key the metadata lists
   * under defaults set on that client instead.
   */
  private List<Object> inEffect(Map<String, String> properties) {
    var environment = SettingsChecks.environmentOf(INVENTORY_API_KEY);
    properties.forEach((key, value) -> environment.setProperty(clientKey(key), value));
    var inventory =
        HttpClientProperties.resolve(environment).clientSettings(HttpClientTestData.INVENTORY);
    return List.of(
        StratumModuleSwitch.isEnabled(environment, HttpClientConstants.MODULE),
        withValuesInEffect(inventory));
  }

  /** Returns the inventory client's key for a key under defaults, and any other key as it is. */
  private String clientKey(String key) {
    return key.replace(HttpClientConstants.DEFAULTS, HttpClientTestData.INVENTORY_SETTINGS);
  }

  /**
   * Returns a client's settings with the values its calls use for two keys whose unset value stays
   * null: retry.max-delay, which RetryRules reads as 2s while delay is shorter, as it is by
   * default, and auth.token-relay, which is off.
   */
  private ClientSettings withValuesInEffect(ClientSettings client) {
    var retry = Objects.requireNonNull(client.retry());
    var maxDelay =
        Optional.ofNullable(retry.maxDelay()).orElse(HttpClientConstants.MAX_RETRY_DELAY);
    var auth = Objects.requireNonNull(client.auth());
    return client.toBuilder()
        .retry(retry.toBuilder().maxDelay(maxDelay).build())
        .auth(auth.toBuilder().tokenRelay(auth.isTokenRelay()).build())
        .build();
  }
}
