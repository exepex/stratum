package com.exepex.stratum.restclient.testsupport;

import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer;
import java.util.HashMap;

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
    clients.put(
        LEDGER,
        properties.clientSettings(RestClientTestData.INVENTORY).toBuilder().retries(0).build());
    return properties.toBuilder().clients(clients).build();
  }
}
