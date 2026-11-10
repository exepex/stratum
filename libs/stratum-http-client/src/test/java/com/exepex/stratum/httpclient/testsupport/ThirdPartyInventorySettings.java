package com.exepex.stratum.httpclient.testsupport;

import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer;
import java.util.Map;

/**
 * A service's Java configuration when the inventory service is a third party: its calls carry no
 * trace or correlation header.
 */
public class ThirdPartyInventorySettings implements HttpClientPropertiesCustomizer {

  @Override
  public HttpClientProperties customize(HttpClientProperties properties) {
    var inventory = properties.clients().get(HttpClientTestData.INVENTORY);
    var thirdParty = inventory.toBuilder().traceHeaders(false).build();
    return properties.toBuilder().clients(Map.of(HttpClientTestData.INVENTORY, thirdParty)).build();
  }
}
