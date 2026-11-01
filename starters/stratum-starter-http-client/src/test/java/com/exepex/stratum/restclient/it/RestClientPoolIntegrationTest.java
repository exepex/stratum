package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.test.DownstreamStub;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
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
import org.springframework.web.client.ResourceAccessException;

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

  /** The longest the test waits for the held call to reach the stub. */
  private static final Duration WAIT = Duration.ofSeconds(5);

  /** Less than the 2s connect timeout, so the pool's wait is what failed the call. */
  private static final Duration FAILS_WITHIN = Duration.ofMillis(1500);

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
    final var heldCall = CompletableFuture.supplyAsync(inventoryClient::heldStock);
    await().atMost(WAIT).until(() -> INVENTORY.heldRequests() == 1);
    var startedAt = System.nanoTime();

    assertThatExceptionOfType(ResourceAccessException.class)
        .isThrownBy(inventoryClient::stock)
        .withRootCauseInstanceOf(ConnectionRequestTimeoutException.class);
    assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(FAILS_WITHIN);

    INVENTORY.releaseHeldRequests();
    assertThat(heldCall).succeedsWithin(WAIT).isEqualTo(DownstreamStub.STOCK_BODY);
  }
}
