package com.exepex.stratum.restclient.testsupport;

import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer;
import java.util.HashMap;
import java.util.Objects;

/**
 * A service's Java configuration that adds a client no file names: {@link #LEDGER}, with the
 * inventory client's address and settings, and no retries.
 */
public class LedgerClientSettings implements HttpClientPropertiesCustomizer {

  /** The client this configuration adds. */
  public static final String LEDGER = "ledger";

  @Override
  public HttpClientProperties customize(HttpClientProperties properties) {
    var clients = new HashMap<>(properties.clients());
    var inventory = properties.clientSettings(RestClientTestData.INVENTORY);
    var noRetries = Objects.requireNonNull(inventory.retry()).toBuilder().maxRetries(0).build();
    clients.put(LEDGER, inventory.toBuilder().retry(noRetries).build());
    return properties.toBuilder().clients(clients).build();
  }
}
