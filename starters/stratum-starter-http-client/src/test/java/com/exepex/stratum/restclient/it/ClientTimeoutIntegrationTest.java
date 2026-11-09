package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.restclient.testsupport.CallChecks;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import com.exepex.stratum.restclient.testsupport.clients.ClientsApplication;
import com.exepex.stratum.restclient.testsupport.clients.InventoryClient;
import com.exepex.stratum.restclient.testsupport.clients.PricingClient;
import com.exepex.stratum.test.SilentPort;
import java.net.SocketTimeoutException;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    classes = ClientsApplication.class,
    properties = {
      RestClientTestData.INVENTORY_SETTINGS + ".timeout=300ms",
      RestClientTestData.PRICING_SETTINGS + ".connect-timeout=300ms"
    })
@Timeout(30)
@DisplayName("A client's own timeouts bound each call; redirects stay unfollowed and gzip is read")
class ClientTimeoutIntegrationTest {

  /** The inventory service. */
  @AutoClose private static final ScriptedDownstream INVENTORY = ScriptedDownstream.start();

  /** A pricing service that never takes a connection. */
  @AutoClose private static final SilentPort SILENT_PRICING = new SilentPort();

  @Autowired private InventoryClient inventoryClient;

  @Autowired private PricingClient pricingClient;

  /**
   * Points each client at its server.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    registry.add(RestClientTestData.INVENTORY_URL, INVENTORY::url);
    registry.add(
        RestClientTestData.PRICING_URL,
        () -> "http://" + SILENT_PRICING.host() + ":" + SILENT_PRICING.port());
  }

  /** Lets the held answer of the timeout test go, so that its connection is freed. */
  @AfterEach
  void releaseHeldAnswers() {
    INVENTORY.release();
  }

  @Nested
  @DisplayName("the client's timeouts")
  class Timeouts {

    @Test
    @DisplayName("an answer slower than the client's timeout fails the call after that timeout")
    void readTimeout() {
      CallChecks.assertFailsFast(inventoryClient::held, SocketTimeoutException.class);

      // The client dropped the connection rather than wait for the late answer on it.
      INVENTORY.release();
      await().atMost(RestClientTestData.WAIT).until(() -> INVENTORY.resetConnections() == 1);
    }

    @Test
    @DisplayName(
        "a service that takes no connection fails the call after the client's connect-timeout")
    void connectTimeout() {
      CallChecks.assertFailsFast(pricingClient::price, ConnectTimeoutException.class);
    }
  }

  @Nested
  @DisplayName("redirects")
  class Redirects {

    @Test
    @DisplayName("a redirect is returned as it is, never followed")
    void redirectNotFollowed() {
      assertThat(inventoryClient.moved()).isNull();
      assertThat(INVENTORY.paths())
          .contains(ScriptedDownstream.MOVED)
          .doesNotContain(ScriptedDownstream.STOCK);
    }
  }

  @Nested
  @DisplayName("compressed answers")
  class Compression {

    @Test
    @DisplayName("a gzip answer is unpacked")
    void gzipAnswerRead() {
      assertThat(inventoryClient.gzipped()).isEqualTo(ScriptedDownstream.BODY);
    }
  }
}
