package com.exepex.stratum.webclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.exepex.stratum.test.DownstreamStub;
import com.exepex.stratum.webclient.testsupport.PoolGauges;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webclient.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@SpringBootTest(
    classes = {InventoryApplication.class, ServiceConnectorIntegrationTest.ServiceConnector.class},
    properties = "stratum.http-client.enabled=true")
@Timeout(15)
@DisplayName(
    "A group with only an address keeps the connector a service's WebClientCustomizer sets")
class ServiceConnectorIntegrationTest {

  /** The downstream inventory service. */
  private static final DownstreamStub INVENTORY = DownstreamStub.start();

  /** The longest the test waits for an answer. */
  private static final Duration WAIT = Duration.ofSeconds(5);

  /** The name of the pool behind the service's own connector. */
  private static final String SERVICE_CONNECTOR_POOL = "service-connector";

  /** The size of the pool behind the service's own connector, unlike Stratum's default of 50. */
  private static final int SERVICE_CONNECTOR_POOL_SIZE = 9;

  @Autowired private InventoryClient inventoryClient;

  @Autowired private MeterRegistry meterRegistry;

  /**
   * Points the inventory client at the stub, its only key of its own.
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
  @DisplayName("calls go through the service's connector, not one Stratum builds")
  void serviceConnectorKept() {
    assertThat(inventoryClient.stock().block(WAIT)).isEqualTo(DownstreamStub.STOCK_BODY);

    assertThat(PoolGauges.maxConnectionsByPool(meterRegistry, INVENTORY))
        .containsExactly(entry(SERVICE_CONNECTOR_POOL, (double) SERVICE_CONNECTOR_POOL_SIZE));
  }

  /** The service's own connector, set on every {@code WebClient.Builder}. */
  @TestConfiguration
  static class ServiceConnector {

    /**
     * Returns the pool behind the service's connector, closed when the test's application stops.
     *
     * @return the pool
     */
    @Bean(destroyMethod = "dispose")
    ConnectionProvider serviceConnectorProvider() {
      return ConnectionProvider.builder(SERVICE_CONNECTOR_POOL)
          .maxConnections(SERVICE_CONNECTOR_POOL_SIZE)
          .metrics(true)
          .build();
    }

    /**
     * Returns the customizer that gives every {@code WebClient} the service's connector.
     *
     * @param serviceConnectorProvider the pool behind the connector
     * @return the customizer
     */
    @Bean
    WebClientCustomizer serviceConnector(ConnectionProvider serviceConnectorProvider) {
      return builder ->
          builder.clientConnector(
              new ReactorClientHttpConnector(HttpClient.create(serviceConnectorProvider)));
    }
  }
}
