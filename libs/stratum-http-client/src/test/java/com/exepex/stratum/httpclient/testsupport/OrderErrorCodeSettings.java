package com.exepex.stratum.httpclient.testsupport;

import com.exepex.stratum.httpclient.ClientSettings;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer;
import com.exepex.stratum.httpclient.OperationSettings;
import java.util.Map;

/**
 * A service's Java configuration: the inventory client's 409 is {@code ORD-7409}, and its {@code
 * list-orders} operation's 404 is {@code ORD-7404}.
 */
public class OrderErrorCodeSettings implements HttpClientPropertiesCustomizer {

  /** The client's codes this configuration sets. */
  public static final Map<Integer, String> ERRORS = Map.of(HttpClientTestData.CONFLICT, "ORD-7409");

  /** The operations this configuration sets. */
  public static final Map<String, OperationSettings> OPERATIONS =
      Map.of(
          HttpClientTestData.LIST_ORDERS,
          OperationSettings.builder()
              .errors(Map.of(HttpClientTestData.NOT_FOUND, "ORD-7404"))
              .build());

  @Override
  public HttpClientProperties customize(HttpClientProperties properties) {
    var inventory = properties.clients().get(HttpClientTestData.INVENTORY);
    var withCodes =
        (inventory == null ? ClientSettings.builder() : inventory.toBuilder())
            .errors(ERRORS)
            .operations(OPERATIONS)
            .build();
    return properties.toBuilder().clients(Map.of(HttpClientTestData.INVENTORY, withCodes)).build();
  }
}
