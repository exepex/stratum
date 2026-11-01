package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.test.DownstreamStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpComponentsClientHttpRequestFactoryBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    classes = InventoryApplication.class,
    properties = {
      "stratum.http-client.enabled=true",
      HttpClientConstants.POOL_MAX_CONNECTIONS + "=1",
      HttpClientConstants.POOL_MAX_WAIT + "=300ms"
    })
// A call that waited for a connection longer than the pool's wait fails the test.
@Timeout(15)
@DisplayName("A blocking client's pool is bounded, and a call that finds it full fails fast")
class RestClientPoolIntegrationTest {

  /** The downstream inventory service. */
  private static final DownstreamStub INVENTORY = DownstreamStub.start();

  @Autowired private InventoryClient inventoryClient;

  @Autowired private ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder;

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
  @DisplayName("Spring Boot sends the calls with Apache HttpClient 5")
  void usesApacheHttpClient() {
    assertThat(requestFactoryBuilder)
        .isInstanceOf(HttpComponentsClientHttpRequestFactoryBuilder.class);
  }

  @Test
  @DisplayName(
      "with the only connection busy, a second call fails after the pool's wait, and the first"
          + " still answers")
  void fullPoolFailsFast() {
    FullPoolCheck.assertSecondCallFailsFast(inventoryClient, INVENTORY);
  }
}
