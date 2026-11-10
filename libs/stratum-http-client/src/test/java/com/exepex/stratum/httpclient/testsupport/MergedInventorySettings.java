package com.exepex.stratum.httpclient.testsupport;

import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer;
import java.util.HashMap;

/**
 * A service's Java configuration written as the module page shows it: it starts from the inventory
 * client's merged settings, changes only its timeout, and stores them back as the client's own.
 */
public class MergedInventorySettings implements HttpClientPropertiesCustomizer {

  @Override
  public HttpClientProperties customize(HttpClientProperties properties) {
    var inventory =
        properties.clientSettings(HttpClientTestData.INVENTORY).toBuilder()
            .timeout(HttpClientTestData.REPORT_TIMEOUT)
            .build();
    var clients = new HashMap<>(properties.clients());
    clients.put(HttpClientTestData.INVENTORY, inventory);
    return properties.toBuilder().clients(clients).build();
  }
}
