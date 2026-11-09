package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.restclient.StratumClientCustomizer;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import com.exepex.stratum.restclient.testsupport.clients.InventoryClient;
import com.exepex.stratum.restclient.testsupport.clients.InventoryClients;
import com.exepex.stratum.restclient.testsupport.clients.PricingClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("A StratumClientCustomizer bean changes the clients it names, and no other")
class ClientCustomizerIntegrationTest {

  /** The path the customizer puts the inventory client under. */
  private static final String REPORTS = "/reports";

  @Test
  @DisplayName("a customizer that moves the inventory client under /reports leaves pricing as set")
  void onlyNamedClientChanged() throws Exception {
    try (var downstream = ScriptedDownstream.start()) {
      StratumClientCustomizer inventoryUnderReports =
          (client, builder) -> {
            if (RestClientTestData.INVENTORY.equals(client)) {
              builder.baseUrl(downstream.url() + REPORTS);
            }
          };
      RestClientTestData.contextRunner()
          .withUserConfiguration(InventoryClients.class)
          .withPropertyValues(
              RestClientTestData.setting(RestClientTestData.INVENTORY_URL, downstream.url()),
              RestClientTestData.setting(RestClientTestData.PRICING_URL, downstream.url()))
          .withBean(StratumClientCustomizer.class, () -> inventoryUnderReports)
          .run(
              context -> {
                context.getBean(InventoryClient.class).stock();
                context.getBean(PricingClient.class).price();
              });

      assertThat(downstream.paths())
          .containsExactly(REPORTS + ScriptedDownstream.STOCK, ScriptedDownstream.STOCK);
    }
  }
}
