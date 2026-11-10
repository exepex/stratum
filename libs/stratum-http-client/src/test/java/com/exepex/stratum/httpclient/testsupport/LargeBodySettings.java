package com.exepex.stratum.httpclient.testsupport;

import com.exepex.stratum.httpclient.ClientSettings;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer;
import java.util.Map;
import org.springframework.util.unit.DataSize;

/** A service's Java configuration: the inventory client's interceptors may read 16 MB of a body. */
public class LargeBodySettings implements HttpClientPropertiesCustomizer {

  /** The limit this configuration sets. */
  public static final DataSize LIMIT = DataSize.ofMegabytes(16);

  @Override
  public HttpClientProperties customize(HttpClientProperties properties) {
    var inventory = properties.clients().get(HttpClientTestData.INVENTORY);
    var large =
        (inventory == null ? ClientSettings.builder() : inventory.toBuilder())
            .interceptorBodyLimit(LIMIT)
            .build();
    return properties.toBuilder().clients(Map.of(HttpClientTestData.INVENTORY, large)).build();
  }
}
