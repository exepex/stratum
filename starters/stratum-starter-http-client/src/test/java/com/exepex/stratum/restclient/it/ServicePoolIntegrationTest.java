package com.exepex.stratum.restclient.it;

import com.exepex.stratum.test.DownstreamStub;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.HttpComponentsClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.autoconfigure.ClientHttpRequestFactoryBuilderCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    classes = {InventoryApplication.class, ServicePoolIntegrationTest.ServicePool.class},
    properties = "stratum.http-client.enabled=true")
// A call that waited for a connection longer than the service pool's wait fails the test.
@org.junit.jupiter.api.Timeout(15)
@DisplayName("A service's own request factory builder customizer wins over Stratum's pool")
class ServicePoolIntegrationTest {

  /** The downstream inventory service. */
  private static final DownstreamStub INVENTORY = DownstreamStub.start();

  /** The service pool's wait for a free connection, much shorter than Stratum's 2s default. */
  private static final long SERVICE_POOL_WAIT_MILLIS = 300;

  @Autowired private InventoryClient inventoryClient;

  /**
   * Points the inventory client at the stub.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void inventoryAddress(DynamicPropertyRegistry registry) {
    registry.add(InventoryApplication.INVENTORY_BASE_URL, INVENTORY::baseUrl);
  }

  /** Stops the stub. */
  @AfterAll
  static void stopInventory() {
    INVENTORY.close();
  }

  @Test
  @DisplayName("the service's one-connection pool applies, so a second call fails fast")
  void servicePoolWins() {
    FullPoolCheck.assertSecondCallFailsFast(inventoryClient, INVENTORY);
  }

  /** The service's own pool of one connection, set on Spring Boot's builder with no order. */
  @TestConfiguration
  static class ServicePool {

    /**
     * Returns the customizer that gives every client a pool of one connection and a short wait.
     *
     * @return the customizer
     */
    @Bean
    ClientHttpRequestFactoryBuilderCustomizer<HttpComponentsClientHttpRequestFactoryBuilder>
        servicePool() {
      return builder ->
          builder
              .withConnectionManagerCustomizer(
                  connectionManager -> connectionManager.setMaxConnTotal(1).setMaxConnPerRoute(1))
              .withDefaultRequestConfigCustomizer(
                  requestConfig ->
                      requestConfig.setConnectionRequestTimeout(
                          Timeout.ofMilliseconds(SERVICE_POOL_WAIT_MILLIS)));
    }
  }
}
