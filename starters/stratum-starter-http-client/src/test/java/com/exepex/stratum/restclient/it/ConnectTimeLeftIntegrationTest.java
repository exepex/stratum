package com.exepex.stratum.restclient.it;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.testsupport.CallChecks;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.clients.ClientsApplication;
import com.exepex.stratum.restclient.testsupport.clients.PricingClient;
import com.exepex.stratum.test.SilentPort;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    classes = ClientsApplication.class,
    properties = {
      HttpClientConstants.DEFAULTS + ".retries=1",
      RestClientTestData.PRICING_SETTINGS + ".connect-timeout=10s",
      RestClientTestData.PRICING_SETTINGS + ".total-timeout=300ms"
    })
@Timeout(30)
@DisplayName("A connection is waited for only within the time left of the call")
class ConnectTimeLeftIntegrationTest {

  /** A pricing service that never takes a connection. */
  @AutoClose private static final SilentPort SILENT_PRICING = new SilentPort();

  @Autowired private PricingClient pricingClient;

  /**
   * Points pricing at the silent port and inventory at a port that is never called.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    registry.add(RestClientTestData.INVENTORY_URL, () -> RestClientTestData.UNUSED_URL);
    registry.add(
        RestClientTestData.PRICING_URL,
        () -> "http://" + SILENT_PRICING.host() + ":" + SILENT_PRICING.port());
  }

  @Test
  @DisplayName("a connect-timeout of 10s ends by a total-timeout of 300ms")
  void connectEndsByTotalTimeout() {
    CallChecks.assertFailsFast(pricingClient::price, ConnectTimeoutException.class);
  }
}
