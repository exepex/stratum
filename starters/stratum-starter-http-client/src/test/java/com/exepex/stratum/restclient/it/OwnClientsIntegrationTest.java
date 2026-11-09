package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer;
import com.exepex.stratum.restclient.StratumHttpClients;
import com.exepex.stratum.restclient.testsupport.CallChecks;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import com.exepex.stratum.restclient.testsupport.clients.ClientsApplication;
import com.exepex.stratum.restclient.testsupport.generated.PartnerInterfacesSettings;
import com.exepex.stratum.restclient.testsupport.generated.PartnerOrdersApi;
import com.exepex.stratum.test.StarterTestSupport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    classes = {ClientsApplication.class, OwnClientsIntegrationTest.CountingCustomizer.class},
    properties = {
      RestClientTestData.PARTNER_SETTINGS + ".max-connections=1",
      RestClientTestData.PARTNER_SETTINGS + ".max-wait=300ms"
    })
@Timeout(30)
@DisplayName("Clients Stratum did not build get a Stratum client's settings, pool and customizers")
class OwnClientsIntegrationTest {

  /** The server every client calls. */
  @AutoClose private static final ScriptedDownstream DOWNSTREAM = ScriptedDownstream.start();

  /** How many requests Spring Boot's {@code RestClientCustomizer} beans have prepared. */
  private static final AtomicInteger CUSTOMIZED_REQUESTS = new AtomicInteger();

  @Autowired private PartnerOrdersApi partnerOrders;

  @Autowired private StratumHttpClients stratumHttpClients;

  /**
   * Points every client at the server, and names the partner's generated interface.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    RestClientTestData.pointClientsAt(registry, DOWNSTREAM);
    registry.add(RestClientTestData.PARTNER_URL, DOWNSTREAM::url);
    registry.add(RestClientTestData.PARTNER_INTERFACE, PartnerOrdersApi.class::getName);
  }

  /** Lets every held call answer, so that the next test finds the pool free. */
  @AfterEach
  void releaseHeldCalls() {
    DOWNSTREAM.release();
  }

  @Nested
  @DisplayName("a generated interface named in a client's interfaces setting")
  class NamedInterface {

    @Test
    @DisplayName("calls the client's address")
    void callsClientAddress() {
      assertThat(partnerOrders.orders()).isEqualTo(ScriptedDownstream.BODY);
    }

    @Test
    @DisplayName("uses the client's pool, which refuses a second call after max-wait")
    void usesClientPool() {
      CallChecks.holdOneConnection(partnerOrders::heldOrders, DOWNSTREAM);

      CallChecks.assertFailsFast(partnerOrders::orders, ConnectionRequestTimeoutException.class);
    }

    @Test
    @DisplayName("set in Java through a customizer in spring.factories, it is registered too")
    void javaSetInterfaceIsRegistered(@TempDir Path folder) throws IOException {
      var answer =
          StarterTestSupport.withCustomizers(
              folder,
              HttpClientPropertiesCustomizer.class,
              List.of(PartnerInterfacesSettings.class),
              OwnClientsIntegrationTest.this::callPartnerInFreshApplication);

      assertThat(answer).isEqualTo(ScriptedDownstream.BODY);
    }
  }

  @Nested
  @DisplayName("stratumHttpClients.restClient(name)")
  class RestClientOfClient {

    @Test
    @DisplayName("calls the client's address")
    void callsClientAddress() {
      assertThat(partnerRestClientCall()).isEqualTo(ScriptedDownstream.BODY);
    }

    @Test
    @DisplayName("shares the client's pool with its interfaces")
    void sharesClientPool() {
      CallChecks.holdOneConnection(partnerOrders::heldOrders, DOWNSTREAM);

      CallChecks.assertFailsFast(
          OwnClientsIntegrationTest.this::partnerRestClientCall,
          ConnectionRequestTimeoutException.class);
    }

    @Test
    @DisplayName("returns the same RestClient each time")
    void builtOnce() {
      assertThat(stratumHttpClients.restClient(RestClientTestData.PARTNER))
          .isSameAs(stratumHttpClients.restClient(RestClientTestData.PARTNER));
    }

    @Test
    @DisplayName("applies Spring Boot's RestClientCustomizer beans, as a client group gets them")
    void appliesBootCustomizers() {
      var before = CUSTOMIZED_REQUESTS.get();

      partnerRestClientCall();

      assertThat(CUSTOMIZED_REQUESTS.get()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("for a name without an address, fails at first use with SX-HTTPC-1004")
    void unknownClientFailsAtFirstUse() {
      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(() -> stratumHttpClients.restClient("billing"))
          .withMessageStartingWith("SX-HTTPC-1004")
          .withMessageContaining(HttpClientConstants.CLIENTS + ".billing.url");
    }
  }

  @Nested
  @DisplayName("stratumHttpClients.settings(name)")
  class SettingsOfClient {

    @Test
    @DisplayName("gives the client's own settings merged with the shared defaults")
    void mergedSettings() {
      var settings = stratumHttpClients.settings(RestClientTestData.PARTNER);

      assertThat(settings.url()).isEqualTo(DOWNSTREAM.url());
      assertThat(settings.maxConnections()).isEqualTo(1);
      assertThat(settings.timeout()).isEqualTo(HttpClientConstants.DEFAULT_TIMEOUT);
    }
  }

  /** Calls the partner service's plain path through the partner client's {@code RestClient}. */
  private String partnerRestClientCall() {
    return stratumHttpClients
        .restClient(RestClientTestData.PARTNER)
        .get()
        .uri(ScriptedDownstream.STOCK)
        .retrieve()
        .body(String.class);
  }

  /** Starts an application whose Java configuration names the interface, and calls it once. */
  private String callPartnerInFreshApplication() {
    try (var context =
        RestClientTestData.startApplication(
            RestClientTestData.setting(RestClientTestData.INVENTORY_URL, DOWNSTREAM.url()),
            RestClientTestData.setting(RestClientTestData.PRICING_URL, DOWNSTREAM.url()),
            RestClientTestData.setting(RestClientTestData.PARTNER_URL, DOWNSTREAM.url()))) {
      return context.getBean(PartnerOrdersApi.class).orders();
    }
  }

  /** A service's {@code RestClientCustomizer}, which counts the requests it prepares. */
  @TestConfiguration(proxyBeanMethods = false)
  static class CountingCustomizer {

    /**
     * Counts each request of every {@code RestClient} built through Spring Boot's customizers.
     *
     * @return the customizer
     */
    @Bean
    RestClientCustomizer countRequests() {
      return builder ->
          builder.requestInitializer(request -> CUSTOMIZED_REQUESTS.incrementAndGet());
    }
  }
}
