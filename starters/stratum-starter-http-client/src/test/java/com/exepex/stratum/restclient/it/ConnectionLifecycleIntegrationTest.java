package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import com.exepex.stratum.restclient.testsupport.clients.InventoryClient;
import com.exepex.stratum.restclient.testsupport.clients.InventoryClients;
import java.time.Duration;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
@DisplayName(
    "Connections are closed when idle or old, checked when stale, and kept as the server allows")
class ConnectionLifecycleIntegrationTest {

  /** Longer than the stale check's 2s, so a reused connection is checked first. */
  private static final Duration PAST_STALE_CHECK =
      HttpClientConstants.STALE_CHECK_AFTER.plusMillis(500);

  /** Longer than the server's 1s keep-alive and shorter than the stale check. */
  private static final Duration PAST_KEEP_ALIVE = Duration.ofMillis(1500);

  @Test
  @DisplayName("a connection unused for idle-timeout is closed by the client")
  void idleConnectionClosed() throws Exception {
    try (var downstream = ScriptedDownstream.start()) {
      runInventory(
          downstream,
          "idle-timeout=300ms",
          inventory -> {
            inventory.stock();
            await().atMost(RestClientTestData.WAIT).until(() -> downstream.openConnections() == 0);
            assertThat(downstream.connections()).isOne();
          });
    }
  }

  @Test
  @DisplayName("a connection the server closed while it sat unused is replaced, and the call works")
  void staleConnectionReplaced() throws Exception {
    try (var downstream = ScriptedDownstream.startClosingIdleAfter(RestClientTestData.SHORT)) {
      runInventory(
          downstream,
          "idle-timeout=1m",
          inventory -> {
            inventory.stock();
            // The server closes the connection after 300ms; the client checks it only after 2s.
            await()
                .pollDelay(PAST_STALE_CHECK)
                .atMost(RestClientTestData.WAIT)
                .until(() -> downstream.idleConnectionsClosed() == 1);

            assertThat(inventory.stock()).isEqualTo(ScriptedDownstream.BODY);
            assertThat(downstream.connections()).isEqualTo(2);
          });
    }
  }

  @Test
  @DisplayName("a connection older than connection-lifetime is not used again")
  void oldConnectionNotReused() throws Exception {
    try (var downstream = ScriptedDownstream.start()) {
      runInventory(
          downstream,
          "connection-lifetime=300ms",
          inventory -> {
            inventory.stock();
            waitPast(RestClientTestData.SHORT.multipliedBy(2));

            inventory.stock();
            assertThat(downstream.connections()).isEqualTo(2);
          });
    }
  }

  @Test
  @DisplayName("a connection is not used after the server's Keep-Alive timeout")
  void keepAliveHonoured() throws Exception {
    try (var downstream = ScriptedDownstream.start()) {
      runInventory(
          downstream,
          "idle-timeout=1m",
          inventory -> {
            inventory.shortKeepAlive();
            waitPast(PAST_KEEP_ALIVE);

            inventory.stock();
            assertThat(downstream.connections()).isEqualTo(2);
          });
    }
  }

  @Test
  @DisplayName("without any limit passed, the client keeps using one connection")
  void connectionReusedWithinLimits() throws Exception {
    try (var downstream = ScriptedDownstream.start()) {
      runInventory(
          downstream,
          "idle-timeout=1m",
          inventory -> {
            inventory.stock();
            inventory.stock();
            assertThat(downstream.connections()).isOne();
          });
    }
  }

  /** Runs the inventory client against the server with one setting of its own. */
  private static void runInventory(
      ScriptedDownstream downstream, String setting, Consumer<InventoryClient> calls) {
    RestClientTestData.contextRunner()
        .withUserConfiguration(InventoryClients.class)
        .withPropertyValues(
            RestClientTestData.setting(RestClientTestData.INVENTORY_URL, downstream.url()),
            RestClientTestData.setting(
                RestClientTestData.PRICING_URL, RestClientTestData.UNUSED_URL),
            RestClientTestData.INVENTORY_SETTINGS + "." + setting)
        .run(context -> calls.accept(context.getBean(InventoryClient.class)));
  }

  /** Waits, so that a connection passes a limit while unused. */
  private static void waitPast(Duration limit) {
    await().pollDelay(limit).atMost(limit.plus(RestClientTestData.WAIT)).until(() -> true);
  }
}
