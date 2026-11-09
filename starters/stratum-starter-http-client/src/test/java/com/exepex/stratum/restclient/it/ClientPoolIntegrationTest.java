package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.testsupport.CallChecks;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import com.exepex.stratum.restclient.testsupport.clients.ClientsApplication;
import com.exepex.stratum.restclient.testsupport.clients.InventoryClient;
import com.exepex.stratum.restclient.testsupport.clients.InventoryReports;
import com.exepex.stratum.restclient.testsupport.clients.PricingClient;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(
    classes = ClientsApplication.class,
    properties = {
      HttpClientConstants.DEFAULTS + ".max-connections=1",
      HttpClientConstants.DEFAULTS + ".max-wait=300ms"
    })
@Timeout(30)
@DisplayName("Each client has its own bounded pool, which refuses a call after max-wait when full")
class ClientPoolIntegrationTest {

  /** The inventory service. */
  @AutoClose private static final ScriptedDownstream INVENTORY = ScriptedDownstream.start();

  /** The pricing service. */
  @AutoClose private static final ScriptedDownstream PRICING = ScriptedDownstream.start();

  @Autowired private InventoryClient inventoryClient;

  @Autowired private InventoryReports inventoryReports;

  @Autowired private PricingClient pricingClient;

  /**
   * Points each client at its server.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    registry.add(RestClientTestData.INVENTORY_URL, INVENTORY::url);
    registry.add(RestClientTestData.PRICING_URL, PRICING::url);
  }

  /** Lets every held call answer, so that the next test finds the pools free. */
  @AfterEach
  void releaseHeldCalls() {
    INVENTORY.release();
  }

  @Nested
  @DisplayName("with each client's own pool")
  class OwnPools {

    @Test
    @DisplayName("a full inventory pool refuses the next inventory call after max-wait")
    void fullPoolFailsAfterMaxWait() {
      var heldCall = CallChecks.holdOneConnection(inventoryClient::held, INVENTORY);

      CallChecks.assertFailsFast(inventoryClient::stock, ConnectionRequestTimeoutException.class);

      INVENTORY.release();
      assertThat(heldCall)
          .succeedsWithin(RestClientTestData.WAIT)
          .isEqualTo(ScriptedDownstream.BODY);
    }

    @Test
    @DisplayName("a full inventory pool leaves the pricing client's pool free")
    void eachClientHasItsOwnPool() {
      CallChecks.holdOneConnection(inventoryClient::held, INVENTORY);

      assertThat(pricingClient.price()).isEqualTo(ScriptedDownstream.BODY);
    }

    @Test
    @DisplayName("two interfaces with one client name share its address and its pool")
    void sameNameSharesGroup() {
      assertThat(inventoryReports.report()).isEqualTo(ScriptedDownstream.BODY);
      assertThat(INVENTORY.paths()).contains(ScriptedDownstream.STOCK);

      CallChecks.holdOneConnection(inventoryClient::held, INVENTORY);

      CallChecks.assertFailsFast(inventoryReports::report, ConnectionRequestTimeoutException.class);
    }
  }

  @Nested
  @TestPropertySource(properties = RestClientTestData.INVENTORY_SETTINGS + ".max-connections=6")
  @DisplayName("with max-connections: 6, above Apache's own limit of 5 per host")
  class AboveApacheHostLimit {

    /** The inventory client's max-connections in this group. */
    static final int MAX_CONNECTIONS = 6;

    @Test
    @DisplayName("all 6 connections reach the one host, and a 7th call is refused")
    void wholePoolServesOneHost() {
      var heldBefore = INVENTORY.heldRequests();
      for (var call = 0; call < MAX_CONNECTIONS; call++) {
        CallChecks.holdOneConnection(inventoryClient::held, INVENTORY);
      }

      assertThat(INVENTORY.heldRequests()).isEqualTo(heldBefore + MAX_CONNECTIONS);
      CallChecks.assertFailsFast(inventoryClient::stock, ConnectionRequestTimeoutException.class);
    }
  }

  @Nested
  @Import(ServiceRequestFactory.class)
  @DisplayName("with the service's RestClientCustomizer setting a request factory on every builder")
  class WithServiceRequestFactory {

    @Test
    @DisplayName("Stratum's pool replaces it, so a full pool still refuses after max-wait")
    void stratumPoolWins() {
      CallChecks.holdOneConnection(inventoryClient::held, INVENTORY);

      CallChecks.assertFailsFast(inventoryClient::stock, ConnectionRequestTimeoutException.class);
    }
  }

  @Nested
  @DisplayName("when the application stops")
  class Shutdown {

    @Test
    @DisplayName("the pools close their connections when the application stops")
    void poolsClosedOnShutdown() throws Exception {
      try (var downstream = ScriptedDownstream.start()) {
        try (var application =
            RestClientTestData.startApplication(
                RestClientTestData.setting(RestClientTestData.INVENTORY_URL, downstream.url()),
                RestClientTestData.setting(
                    RestClientTestData.PRICING_URL, RestClientTestData.UNUSED_URL))) {
          assertThat(application.getBean(InventoryClient.class).stock())
              .isEqualTo(ScriptedDownstream.BODY);
          assertThat(downstream.openConnections()).isOne();
        }

        await().atMost(RestClientTestData.WAIT).until(() -> downstream.openConnections() == 0);
      }
    }
  }

  @Nested
  @TestPropertySource(
      properties = {
        HttpClientConstants.DEFAULTS + ".shared-pool=true",
        RestClientTestData.INVENTORY_SETTINGS + ".max-connections=5",
        RestClientTestData.PRICING_SETTINGS + ".max-connections=5"
      })
  @DisplayName("with shared-pool: true")
  class SharedPool {

    @Test
    @DisplayName(
        "every client takes its connections from the one pool sized under defaults, not its own")
    void sharedPoolIsOne() {
      CallChecks.holdOneConnection(inventoryClient::held, INVENTORY);

      CallChecks.assertFailsFast(pricingClient::price, ConnectionRequestTimeoutException.class);
    }
  }

  /** The service's customizer of every Spring Boot RestClient builder, Stratum's included. */
  @TestConfiguration(proxyBeanMethods = false)
  static class ServiceRequestFactory {

    /**
     * Gives every builder Spring's plain request factory, which has no pool.
     *
     * @return the customizer
     */
    @Bean
    RestClientCustomizer plainRequestFactory() {
      return builder -> builder.requestFactory(new SimpleClientHttpRequestFactory());
    }
  }
}
