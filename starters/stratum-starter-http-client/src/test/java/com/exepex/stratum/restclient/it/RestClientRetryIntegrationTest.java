package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.test.DownstreamStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpServerErrorException;

@SpringBootTest(
    classes = InventoryApplication.class,
    properties = "stratum.http-client.enabled=true")
@Timeout(15)
@DisplayName("A blocking call that gets a 503 answer is not sent again")
class RestClientRetryIntegrationTest {

  /** The downstream inventory service. */
  private static final DownstreamStub INVENTORY = DownstreamStub.start();

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
  @DisplayName("a POST answered with 503 reaches the downstream service once")
  void postSentOnce() {
    assertThatExceptionOfType(HttpServerErrorException.ServiceUnavailable.class)
        .isThrownBy(inventoryClient::reserveStock);

    assertThat(INVENTORY.unavailableRequests()).isOne();
  }
}
