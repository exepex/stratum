package com.exepex.stratum.httpclient.testsupport;

import com.exepex.stratum.httpclient.ClientSettings;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer;
import java.util.Map;

/** A service's Java configuration: the inventory client waits 20s for an answer. */
public class SlowInventorySettings implements HttpClientPropertiesCustomizer {

  @Override
  public HttpClientProperties customize(HttpClientProperties properties) {
    var inventory = properties.clients().get(HttpClientTestData.INVENTORY);
    var slow =
        (inventory == null ? ClientSettings.builder() : inventory.toBuilder())
            .timeout(HttpClientTestData.REPORT_TIMEOUT)
            .build();
    return properties.toBuilder().clients(Map.of(HttpClientTestData.INVENTORY, slow)).build();
  }
}
