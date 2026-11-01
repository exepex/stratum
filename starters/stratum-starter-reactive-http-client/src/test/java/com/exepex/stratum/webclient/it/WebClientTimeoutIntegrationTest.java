package com.exepex.stratum.webclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.test.DownstreamStub;
import io.netty.handler.timeout.ReadTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClientRequestException;

@SpringBootTest(
    classes = InventoryApplication.class,
    properties = {
      "stratum.http-client.enabled=true",
      "spring.http.serviceclient.inventory.read-timeout=300ms"
    })
// A call that waited for the 5s default instead of its client's own read timeout fails the test.
@Timeout(15)
@DisplayName("Every reactive client has timeouts, and a client's own Spring Boot key wins")
class WebClientTimeoutIntegrationTest {

  /** The downstream inventory service. */
  private static final DownstreamStub INVENTORY = DownstreamStub.start();

  /** The longest the test waits for an answer. */
  private static final Duration WAIT = Duration.ofSeconds(5);

  /** Less than the 5s default, so the client's own read timeout is what failed the call. */
  private static final Duration FAILS_WITHIN = Duration.ofSeconds(2);

  /** Stratum's defaults, spelled out so a changed default fails the test. */
  private static final Duration CONNECT_WITHIN = Duration.ofSeconds(2);

  private static final Duration READ_WITHIN = Duration.ofSeconds(5);

  @Autowired private InventoryClient inventoryClient;

  @Autowired private HttpClientSettings clientSettings;

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
  @DisplayName("every client connects within 2s and reads within 5s unless it sets its own")
  void defaultTimeouts() {
    assertThat(clientSettings.connectTimeout()).isEqualTo(CONNECT_WITHIN);
    assertThat(clientSettings.readTimeout()).isEqualTo(READ_WITHIN);
  }

  @Test
  @DisplayName("a client's spring.http.serviceclient read timeout wins over Stratum's default")
  void clientReadTimeoutWins() {
    var startedAt = System.nanoTime();

    assertThatExceptionOfType(WebClientRequestException.class)
        .isThrownBy(() -> inventoryClient.heldStock().block(WAIT))
        .withRootCauseInstanceOf(ReadTimeoutException.class);
    assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(FAILS_WITHIN);
  }

  @Test
  @DisplayName("a downstream service that answers in time is read as usual")
  void answersInTime() {
    assertThat(inventoryClient.stock().block(WAIT)).isEqualTo(DownstreamStub.STOCK_BODY);
  }
}
