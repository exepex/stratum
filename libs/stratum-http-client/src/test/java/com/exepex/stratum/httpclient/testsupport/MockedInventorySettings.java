package com.exepex.stratum.httpclient.testsupport;

import com.exepex.stratum.httpclient.ClientSettings;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer;
import java.util.Map;

/**
 * A service's Java configuration for a demo in which the inventory service is not deployed: the
 * inventory client answers from its mocks.
 */
public class MockedInventorySettings implements HttpClientPropertiesCustomizer {

  @Override
  public HttpClientProperties customize(HttpClientProperties properties) {
    var inventory = properties.clients().get(HttpClientTestData.INVENTORY);
    var mocked =
        (inventory == null ? ClientSettings.builder() : inventory.toBuilder()).mock(true).build();
    return properties.toBuilder().clients(Map.of(HttpClientTestData.INVENTORY, mocked)).build();
  }
}
