package com.exepex.stratum.httpclient.testsupport;

import com.exepex.stratum.httpclient.ClientSettings;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer;
import com.exepex.stratum.httpclient.OperationSettings;
import java.time.Duration;
import java.util.Map;

/**
 * A service's Java configuration: the inventory client's {@code list-orders} operation waits up to
 * 50s for an answer and 55s for the whole call.
 */
public class ReportTimeoutSettings implements HttpClientPropertiesCustomizer {

  /** The operation's read-timeout this configuration sets. */
  public static final Duration READ_TIMEOUT = Duration.ofSeconds(50);

  /** The operation's total-timeout this configuration sets. */
  public static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(55);

  @Override
  public HttpClientProperties customize(HttpClientProperties properties) {
    var inventory = properties.clients().get(HttpClientTestData.INVENTORY);
    var withTimeouts =
        (inventory == null ? ClientSettings.builder() : inventory.toBuilder())
            .operations(
                Map.of(
                    HttpClientTestData.LIST_ORDERS,
                    OperationSettings.builder()
                        .readTimeout(READ_TIMEOUT)
                        .totalTimeout(TOTAL_TIMEOUT)
                        .build()))
            .build();
    return properties.toBuilder()
        .clients(Map.of(HttpClientTestData.INVENTORY, withTimeouts))
        .build();
  }
}
