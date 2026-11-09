package com.exepex.stratum.httpclient.testsupport;

import com.exepex.stratum.httpclient.ClientAuth;
import com.exepex.stratum.httpclient.ClientSettings;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer;
import java.util.Map;

/**
 * A service's Java configuration, as one that reads secrets from a vault would write it: the
 * inventory client sends an API key and uses the bank SSL bundle.
 */
public class VendorKeySettings implements HttpClientPropertiesCustomizer {

  /** The auth this configuration sets. */
  public static final ClientAuth AUTH =
      ClientAuth.builder().apiKey(HttpClientTestData.SECRET).build();

  /** The SSL bundle this configuration sets. */
  public static final String SSL_BUNDLE = "bank";

  @Override
  public HttpClientProperties customize(HttpClientProperties properties) {
    var inventory = properties.clients().get(HttpClientTestData.INVENTORY);
    var withKey =
        (inventory == null ? ClientSettings.builder() : inventory.toBuilder())
            .auth(AUTH)
            .sslBundle(SSL_BUNDLE)
            .build();
    return properties.toBuilder().clients(Map.of(HttpClientTestData.INVENTORY, withKey)).build();
  }
}
