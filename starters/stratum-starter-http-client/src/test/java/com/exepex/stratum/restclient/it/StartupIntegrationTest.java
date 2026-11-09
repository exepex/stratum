package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import com.exepex.stratum.restclient.testsupport.clients.InventoryClient;
import com.exepex.stratum.restclient.testsupport.clients.InventoryClients;
import com.exepex.stratum.restclient.testsupport.clients.PricingClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Startup opens no connection, so it does not depend on a downstream service")
class StartupIntegrationTest {

  @Test
  @DisplayName("the application starts with one service down and opens no connection to the other")
  void startsWithDownstreamDown() throws Exception {
    try (var inventory = ScriptedDownstream.start()) {
      RestClientTestData.contextRunner()
          .withUserConfiguration(InventoryClients.class)
          .withPropertyValues(
              RestClientTestData.setting(RestClientTestData.INVENTORY_URL, inventory.url()),
              // Nothing listens on this port: the pricing service is down.
              RestClientTestData.setting(
                  RestClientTestData.PRICING_URL, RestClientTestData.UNUSED_URL))
          .run(
              context ->
                  assertThat(context)
                      .hasNotFailed()
                      .hasSingleBean(InventoryClient.class)
                      .hasSingleBean(PricingClient.class));

      assertThat(inventory.connections()).isZero();
    }
  }
}
