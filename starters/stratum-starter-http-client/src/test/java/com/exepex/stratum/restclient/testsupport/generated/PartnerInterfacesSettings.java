package com.exepex.stratum.restclient.testsupport.generated;

import com.exepex.stratum.httpclient.ClientSettings;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

/**
 * A service's Java configuration: the partner client gets the generated {@link PartnerOrdersApi}.
 */
public class PartnerInterfacesSettings implements HttpClientPropertiesCustomizer {

  @Override
  public HttpClientProperties customize(HttpClientProperties properties) {
    var clients = new HashMap<>(properties.clients());
    var partner =
        Optional.ofNullable(clients.get(RestClientTestData.PARTNER))
            .map(ClientSettings::toBuilder)
            .orElseGet(ClientSettings::builder)
            .interfaces(List.of(PartnerOrdersApi.class.getName()))
            .build();
    clients.put(RestClientTestData.PARTNER, partner);
    return properties.toBuilder().clients(clients).build();
  }
}
