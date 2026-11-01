package com.exepex.stratum.webclient.it;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.exepex.stratum.test.DownstreamStub;
import com.exepex.stratum.webclient.WebClientGroupSettings;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Fails once Spring Boot applies a {@code WebClient} group's own timeouts by itself. When it fails,
 * remove {@link WebClientGroupSettings}, its bean and this test.
 */
@SpringBootTest(
    classes = InventoryApplication.class,
    properties = "spring.http.serviceclient.inventory.read-timeout=300ms")
@Timeout(15)
@DisplayName("Spring Boot alone still ignores a WebClient group's own read timeout")
class SpringBootGroupTimeoutBugIntegrationTest {

  /** The downstream inventory service. */
  private static final DownstreamStub INVENTORY = DownstreamStub.start();

  /** Well above the group's 300ms read timeout, which Spring Boot alone ignores. */
  private static final Duration WAIT = Duration.ofSeconds(1);

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
  @DisplayName("with Stratum's module off, a held call outlives the group's read timeout")
  void groupReadTimeoutIgnored() {
    assertThatIllegalStateException()
        .as("Spring Boot now applies the group's timeout: remove WebClientGroupSettings")
        .isThrownBy(() -> inventoryClient.heldStock().block(WAIT))
        .withMessageContaining("Timeout on blocking read");
  }
}
