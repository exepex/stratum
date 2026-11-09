package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import com.exepex.stratum.restclient.testsupport.clients.ClientsApplication;
import com.exepex.stratum.restclient.testsupport.clients.InventoryClient;
import java.time.Duration;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Each group runs its own application and server, so that each test counts the connections of its
 * own client from zero.
 */
@Timeout(30)
@DisplayName(
    "Connections are closed when idle or old, checked when stale, and kept as the server allows")
class ConnectionLifecycleIntegrationTest {

  /** Longer than the stale check's 2s, so a reused connection is checked first. */
  private static final Duration PAST_STALE_CHECK =
      HttpClientConstants.STALE_CHECK_AFTER.plusMillis(500);

  /** Longer than the server's 1s keep-alive and shorter than the stale check. */
  private static final Duration PAST_KEEP_ALIVE = Duration.ofMillis(1500);

  /** A long idle timeout, so that only the limit under test closes a connection. */
  private static final String LONG_IDLE =
      RestClientTestData.INVENTORY_SETTINGS + ".idle-timeout=1m";

  /** Waits, so that a connection passes a limit while unused. */
  private static void waitPast(Duration limit) {
    await().pollDelay(limit).atMost(limit.plus(RestClientTestData.WAIT)).until(() -> true);
  }

  @Nested
  @SpringBootTest(
      classes = ClientsApplication.class,
      properties = RestClientTestData.INVENTORY_SETTINGS + ".idle-timeout=300ms")
  @DisplayName("with idle-timeout: 300ms")
  class ShortIdleTimeout {

    /** This group's server. */
    @AutoClose static final ScriptedDownstream DOWNSTREAM = ScriptedDownstream.start();

    @Autowired private InventoryClient inventory;

    /**
     * Points both clients at this group's server.
     *
     * @param registry the test's properties
     */
    @DynamicPropertySource
    static void addresses(DynamicPropertyRegistry registry) {
      RestClientTestData.pointClientsAt(registry, DOWNSTREAM);
    }

    @Test
    @DisplayName("a connection unused for idle-timeout is closed by the client")
    void idleConnectionClosed() {
      inventory.stock();
      await().atMost(RestClientTestData.WAIT).until(() -> DOWNSTREAM.openConnections() == 0);
      assertThat(DOWNSTREAM.connections()).isOne();
    }
  }

  @Nested
  @SpringBootTest(classes = ClientsApplication.class, properties = LONG_IDLE)
  @DisplayName("against a server that closes idle connections after 300ms")
  class ServerClosesIdle {

    /** This group's server. */
    @AutoClose
    static final ScriptedDownstream DOWNSTREAM =
        ScriptedDownstream.startClosingIdleAfter(RestClientTestData.SHORT);

    @Autowired private InventoryClient inventory;

    /**
     * Points both clients at this group's server.
     *
     * @param registry the test's properties
     */
    @DynamicPropertySource
    static void addresses(DynamicPropertyRegistry registry) {
      RestClientTestData.pointClientsAt(registry, DOWNSTREAM);
    }

    @Test
    @DisplayName(
        "a connection the server closed while it sat unused is replaced, and the call works")
    void staleConnectionReplaced() {
      inventory.stock();
      // The server closes the connection after 300ms; the client checks it only after 2s.
      await()
          .pollDelay(PAST_STALE_CHECK)
          .atMost(RestClientTestData.WAIT)
          .until(() -> DOWNSTREAM.idleConnectionsClosed() == 1);

      assertThat(inventory.stock()).isEqualTo(ScriptedDownstream.BODY);
      assertThat(DOWNSTREAM.connections()).isEqualTo(2);
    }
  }

  @Nested
  @SpringBootTest(
      classes = ClientsApplication.class,
      properties = RestClientTestData.INVENTORY_SETTINGS + ".connection-lifetime=300ms")
  @DisplayName("with connection-lifetime: 300ms")
  class ShortLifetime {

    /** This group's server. */
    @AutoClose static final ScriptedDownstream DOWNSTREAM = ScriptedDownstream.start();

    @Autowired private InventoryClient inventory;

    /**
     * Points both clients at this group's server.
     *
     * @param registry the test's properties
     */
    @DynamicPropertySource
    static void addresses(DynamicPropertyRegistry registry) {
      RestClientTestData.pointClientsAt(registry, DOWNSTREAM);
    }

    @Test
    @DisplayName("a connection older than connection-lifetime is not used again")
    void oldConnectionNotReused() {
      inventory.stock();
      waitPast(RestClientTestData.SHORT.multipliedBy(2));

      inventory.stock();
      assertThat(DOWNSTREAM.connections()).isEqualTo(2);
    }
  }

  @Nested
  @SpringBootTest(classes = ClientsApplication.class, properties = LONG_IDLE)
  @DisplayName("against a server that sends Keep-Alive: timeout=1")
  class ServerKeepAlive {

    /** This group's server. */
    @AutoClose static final ScriptedDownstream DOWNSTREAM = ScriptedDownstream.start();

    @Autowired private InventoryClient inventory;

    /**
     * Points both clients at this group's server.
     *
     * @param registry the test's properties
     */
    @DynamicPropertySource
    static void addresses(DynamicPropertyRegistry registry) {
      RestClientTestData.pointClientsAt(registry, DOWNSTREAM);
    }

    @Test
    @DisplayName("a connection is not used after the server's Keep-Alive timeout")
    void keepAliveHonoured() {
      inventory.shortKeepAlive();
      waitPast(PAST_KEEP_ALIVE);

      inventory.stock();
      assertThat(DOWNSTREAM.connections()).isEqualTo(2);
    }
  }

  @Nested
  @SpringBootTest(classes = ClientsApplication.class, properties = LONG_IDLE)
  @DisplayName("within every limit")
  class WithinLimits {

    /** This group's server. */
    @AutoClose static final ScriptedDownstream DOWNSTREAM = ScriptedDownstream.start();

    @Autowired private InventoryClient inventory;

    /**
     * Points both clients at this group's server.
     *
     * @param registry the test's properties
     */
    @DynamicPropertySource
    static void addresses(DynamicPropertyRegistry registry) {
      RestClientTestData.pointClientsAt(registry, DOWNSTREAM);
    }

    @Test
    @DisplayName("the client keeps using one connection")
    void connectionReusedWithinLimits() {
      inventory.stock();
      inventory.stock();
      assertThat(DOWNSTREAM.connections()).isOne();
    }
  }
}
