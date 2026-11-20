package com.exepex.stratum.httpclient.testsupport;

import com.exepex.stratum.httpclient.ClientSettings;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer;
import java.util.Map;

/**
 * A service's Java configuration that gives a code to status 700, which is no error status, so that
 * the settings are refused after it ran.
 */
public class OutOfRangeErrorCodeSettings implements HttpClientPropertiesCustomizer {

  @Override
  public HttpClientProperties customize(HttpClientProperties properties) {
    var inventory =
        ClientSettings.builder()
            .errors(Map.of(HttpClientTestData.NOT_AN_ERROR, "ORD-0700"))
            .build();
    return properties.toBuilder().clients(Map.of(HttpClientTestData.INVENTORY, inventory)).build();
  }
}
