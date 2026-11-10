package com.exepex.stratum.httpclient.testsupport;

import com.exepex.stratum.httpclient.ClientSettings;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer;
import java.util.List;
import java.util.Map;

/**
 * A service's Java configuration: the inventory client sends {@code X-Partner-Id: java} and copies
 * {@code X-Tenant}.
 */
public class TenantHeaderSettings implements HttpClientPropertiesCustomizer {

  /** The headers this configuration sets. */
  public static final Map<String, String> HEADERS = Map.of("X-Partner-Id", "java");

  /** The copied headers this configuration sets. */
  public static final List<String> COPY_HEADERS = List.of("X-Tenant");

  @Override
  public HttpClientProperties customize(HttpClientProperties properties) {
    var inventory = properties.clients().get(HttpClientTestData.INVENTORY);
    var withHeaders =
        (inventory == null ? ClientSettings.builder() : inventory.toBuilder())
            .headers(HEADERS)
            .copyHeaders(COPY_HEADERS)
            .build();
    return properties.toBuilder()
        .clients(Map.of(HttpClientTestData.INVENTORY, withHeaders))
        .build();
  }
}
