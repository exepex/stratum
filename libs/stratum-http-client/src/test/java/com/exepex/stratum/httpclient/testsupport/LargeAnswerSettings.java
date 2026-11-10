package com.exepex.stratum.httpclient.testsupport;

import com.exepex.stratum.httpclient.ClientSettings;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer;
import java.util.Map;
import org.springframework.util.unit.DataSize;

/** A service's Java configuration: the inventory client reads answers of up to 128 MB. */
public class LargeAnswerSettings implements HttpClientPropertiesCustomizer {

  /** The limit this configuration sets. */
  public static final DataSize LIMIT = DataSize.ofMegabytes(128);

  @Override
  public HttpClientProperties customize(HttpClientProperties properties) {
    var inventory = properties.clients().get(HttpClientTestData.INVENTORY);
    var large =
        (inventory == null ? ClientSettings.builder() : inventory.toBuilder())
            .responseBodyLimit(LIMIT)
            .build();
    return properties.toBuilder().clients(Map.of(HttpClientTestData.INVENTORY, large)).build();
  }
}
