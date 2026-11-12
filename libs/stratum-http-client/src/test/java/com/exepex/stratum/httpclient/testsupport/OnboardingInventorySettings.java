package com.exepex.stratum.httpclient.testsupport;

import com.exepex.stratum.httpclient.CallLog;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer;
import java.util.Map;

/**
 * A service's Java configuration while it onboards the inventory service: every inventory call
 * writes a log line, a success included.
 */
public class OnboardingInventorySettings implements HttpClientPropertiesCustomizer {

  @Override
  public HttpClientProperties customize(HttpClientProperties properties) {
    var inventory = properties.clients().get(HttpClientTestData.INVENTORY);
    var onboarding = inventory.toBuilder().callLog(CallLog.ALL).build();
    return properties.toBuilder().clients(Map.of(HttpClientTestData.INVENTORY, onboarding)).build();
  }
}
