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
import org.springframework.boot.http.client.autoconfigure.reactive.ClientHttpConnectorBuilderCustomizer;
import org.springframework.boot.http.client.reactive.ReactorClientHttpConnectorBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@SpringBootTest(
    classes = {InventoryApplication.class, ServicePoolIntegrationTest.ServicePool.class},
    properties = "stratum.http-client.enabled=true")
@Timeout(15)
@DisplayName("A service's own connector builder customizer wins over Stratum's pool")
class ServicePoolIntegrationTest {

  /** The downstream inventory service. */
  private static final DownstreamStub INVENTORY = DownstreamStub.start();

  /** The longest the test waits for an answer. */
  private static final Duration WAIT = Duration.ofSeconds(5);

  /** The name of the service's own pool. */
  private static final String SERVICE_POOL = "service-pool";

  /** The service's own pool size, unlike Stratum's default of 50. */
  private static final int SERVICE_POOL_SIZE = 7;

  @Autowired private InventoryClient inventoryClient;

  @Autowired private MeterRegistry meterRegistry;

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
  @DisplayName("calls go through the service's pool, not Stratum's")
  void servicePoolWins() {
    assertThat(inventoryClient.stock().block(WAIT)).isEqualTo(DownstreamStub.STOCK_BODY);

    assertThat(PoolGauges.maxConnectionsByPool(meterRegistry, INVENTORY))
        .containsExactly(entry(SERVICE_POOL, (double) SERVICE_POOL_SIZE));
  }

  /** The service's own pool, set on Spring Boot's connector builder with no order. */
  @TestConfiguration
  static class ServicePool {

    /**
     * Returns the service's pool, closed when the test's application stops.
     *
     * @return the pool
     */
    @Bean(destroyMethod = "dispose")
    ConnectionProvider servicePoolProvider() {
      return ConnectionProvider.builder(SERVICE_POOL)
          .maxConnections(SERVICE_POOL_SIZE)
          .metrics(true)
          .build();
    }

    /**
     * Returns the customizer that makes every client on the service's pool.
     *
     * @param servicePoolProvider the service's pool
     * @return the customizer
     */
    @Bean
    ClientHttpConnectorBuilderCustomizer<ReactorClientHttpConnectorBuilder> servicePool(
        ConnectionProvider servicePoolProvider) {
      return builder -> builder.withHttpClientFactory(() -> HttpClient.create(servicePoolProvider));
    }
  }
}
