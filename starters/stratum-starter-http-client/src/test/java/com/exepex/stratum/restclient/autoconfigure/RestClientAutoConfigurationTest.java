package com.exepex.stratum.restclient.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer;
import com.exepex.stratum.restclient.ClientConnectionPools;
import com.exepex.stratum.restclient.ClientResilience;
import com.exepex.stratum.restclient.StratumClientGroups;
import com.exepex.stratum.restclient.StratumHttpClients;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.clients.InventoryAdmin;
import com.exepex.stratum.restclient.testsupport.clients.InventoryClient;
import com.exepex.stratum.restclient.testsupport.clients.InventoryClients;
import java.util.ArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.service.registry.HttpServiceProxyRegistry;

@DisplayName("RestClientAutoConfiguration makes the clients, unless the module is off")
class RestClientAutoConfigurationTest {

  /** The clients package, with every client's address. */
  private final ApplicationContextRunner clients =
      RestClientTestData.contextRunner()
          .withUserConfiguration(InventoryClients.class)
          .withPropertyValues(
              RestClientTestData.setting(
                  RestClientTestData.INVENTORY_URL, RestClientTestData.UNUSED_URL),
              RestClientTestData.setting(
                  RestClientTestData.PRICING_URL, RestClientTestData.UNUSED_URL));

  @Nested
  @DisplayName("with nothing changed")
  class Defaults {

    @Test
    @DisplayName("with the module on, each client is a bean, with Stratum's pools and set-up")
    void moduleOnCreatesClients() {
      clients.run(
          context ->
              assertThat(context)
                  .hasSingleBean(InventoryClient.class)
                  .hasSingleBean(ClientConnectionPools.class)
                  .hasSingleBean(StratumClientGroups.class)
                  .hasSingleBean(ClientResilience.class)
                  .hasSingleBean(StratumHttpClients.class));
    }

    @Test
    @DisplayName(
        "Stratum adds no request interceptor, which would hold every request body in memory")
    void noRequestInterceptor() {
      var interceptors = new ArrayList<ClientHttpRequestInterceptor>();

      clients.run(
          context ->
              context
                  .getBean(StratumHttpClients.class)
                  .restClient(RestClientTestData.INVENTORY)
                  .mutate()
                  .requestInterceptors(interceptors::addAll));

      assertThat(interceptors).isEmpty();
    }

    @Test
    @DisplayName("an interface that only extends a client, without @StratumClient, is not a client")
    void subInterfaceWithoutAnnotationSkipped() {
      clients.run(
          context -> assertThat(context).hasNotFailed().doesNotHaveBean(InventoryAdmin.class));
    }
  }

  @Nested
  @DisplayName("with the module switch")
  class Switch {

    @Test
    @DisplayName("with stratum.http-client.enabled=false, no client, registry, pool or set-up")
    void moduleOffCreatesNothing() {
      clients
          .withPropertyValues(RestClientTestData.setting(HttpClientConstants.ENABLED, false))
          .run(
              context ->
                  assertThat(context)
                      .hasNotFailed()
                      .doesNotHaveBean(InventoryClient.class)
                      .doesNotHaveBean(HttpServiceProxyRegistry.class)
                      .doesNotHaveBean(ClientConnectionPools.class)
                      .doesNotHaveBean(StratumClientGroups.class)
                      .doesNotHaveBean(ClientResilience.class)
                      .doesNotHaveBean(StratumHttpClients.class));
    }
  }

  @Nested
  @DisplayName("when the service or its framework declares a bean")
  class BackOff {

    @Test
    @DisplayName("the service's own pools bean wins over Stratum's")
    void servicePoolsWin() {
      var servicePools = RestClientTestData.ownPools();
      clients
          .withBean(ClientConnectionPools.class, () -> servicePools)
          .run(
              context ->
                  assertThat(context)
                      .hasSingleBean(ClientConnectionPools.class)
                      .getBean(ClientConnectionPools.class)
                      .isSameAs(servicePools));
    }

    @Test
    @DisplayName("the service's own client set-up bean wins over Stratum's")
    void serviceGroupsWin() {
      var serviceGroups = Mockito.mock(StratumClientGroups.class);
      clients
          .withBean(StratumClientGroups.class, () -> serviceGroups)
          .run(
              context ->
                  assertThat(context)
                      .hasSingleBean(StratumClientGroups.class)
                      .getBean(StratumClientGroups.class)
                      .isSameAs(serviceGroups));
    }

    @Test
    @DisplayName("the service's own retries and breakers bean wins over Stratum's")
    void serviceResilienceWins() {
      var serviceResilience = Mockito.mock(ClientResilience.class);
      clients
          .withBean(ClientResilience.class, () -> serviceResilience)
          .run(
              context ->
                  assertThat(context)
                      .hasSingleBean(ClientResilience.class)
                      .getBean(ClientResilience.class)
                      .isSameAs(serviceResilience));
    }

    @Test
    @DisplayName("the service's own StratumHttpClients bean wins over Stratum's")
    void serviceHttpClientsWin() {
      var serviceHttpClients = Mockito.mock(StratumHttpClients.class);
      clients
          .withBean(StratumHttpClients.class, () -> serviceHttpClients)
          .run(
              context ->
                  assertThat(context)
                      .hasSingleBean(StratumHttpClients.class)
                      .getBean(StratumHttpClients.class)
                      .isSameAs(serviceHttpClients));
    }

    @Test
    @DisplayName("a company framework's auto-configuration with no order wins over Stratum's pools")
    void companyPoolsWin() {
      clients
          .withConfiguration(AutoConfigurations.of(CompanyPoolsAutoConfiguration.class))
          .run(
              context ->
                  assertThat(context)
                      .hasSingleBean(ClientConnectionPools.class)
                      .getBean(ClientConnectionPools.class)
                      .isSameAs(context.getBean(CompanyPoolsAutoConfiguration.POOLS)));
    }
  }

  @Nested
  @DisplayName("Java configuration")
  class JavaConfiguration {

    @Test
    @DisplayName("a settings customizer declared as a bean stops startup with SX-CORE-1004")
    void customizerBeanStopsStartup() {
      clients
          .withBean(HttpClientPropertiesCustomizer.class, () -> properties -> properties)
          .run(
              context ->
                  assertThat(context.getStartupFailure())
                      .rootCause()
                      .hasMessageContaining("SX-CORE-1004"));
    }
  }

  /** A company framework's auto-configuration that declares its own pools and no order. */
  @AutoConfiguration
  static class CompanyPoolsAutoConfiguration {

    /** The name of the framework's pools bean. */
    static final String POOLS = "companyPools";

    /**
     * Declares the framework's pools.
     *
     * @return the pools
     */
    @Bean(POOLS)
    ClientConnectionPools companyPools() {
      return RestClientTestData.ownPools();
    }
  }
}
