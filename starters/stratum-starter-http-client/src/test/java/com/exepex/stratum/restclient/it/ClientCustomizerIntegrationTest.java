package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.restclient.StratumClientCustomizer;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import com.exepex.stratum.restclient.testsupport.clients.ClientsApplication;
import com.exepex.stratum.restclient.testsupport.clients.InventoryClient;
import com.exepex.stratum.restclient.testsupport.clients.PricingClient;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    classes = {
      ClientsApplication.class,
      ClientCustomizerIntegrationTest.InventoryUnderReports.class
    })
@DisplayName("A StratumClientCustomizer bean changes the clients it names, and no other")
class ClientCustomizerIntegrationTest {

  /** The path the customizer puts the inventory client under. */
  private static final String REPORTS = "/reports";

  /** The server both clients call. */
  @AutoClose private static final ScriptedDownstream DOWNSTREAM = ScriptedDownstream.start();

  @Autowired private InventoryClient inventoryClient;

  @Autowired private PricingClient pricingClient;

  /**
   * Points both clients at the server.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    RestClientTestData.pointClientsAt(registry, DOWNSTREAM);
  }

  @Test
  @DisplayName("a customizer that moves the inventory client under /reports leaves pricing as set")
  void onlyNamedClientChanged() {
    inventoryClient.stock();
    pricingClient.price();

    assertThat(DOWNSTREAM.paths())
        .containsExactly(REPORTS + ScriptedDownstream.STOCK, ScriptedDownstream.STOCK);
  }

  /** The service's customizer: it moves only the inventory client. */
  @TestConfiguration(proxyBeanMethods = false)
  static class InventoryUnderReports {

    /**
     * Moves the inventory client under {@code /reports}.
     *
     * @return the customizer
     */
    @Bean
    StratumClientCustomizer inventoryUnderReports() {
      return (client, builder) -> {
        if (RestClientTestData.INVENTORY.equals(client)) {
          builder.baseUrl(DOWNSTREAM.url() + REPORTS);
        }
      };
    }
  }
}
