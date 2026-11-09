package com.exepex.stratum.restclient.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer;
import com.exepex.stratum.restclient.ClientConnectionPools;
import com.exepex.stratum.restclient.StratumClientGroups;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.clients.InventoryClient;
import com.exepex.stratum.restclient.testsupport.clients.InventoryClients;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
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

  @Test
  @DisplayName("with the module on, each client is a bean, with Stratum's pools and set-up")
  void moduleOnCreatesClients() {
    clients.run(
        context ->
            assertThat(context)
                .hasSingleBean(InventoryClient.class)
                .hasSingleBean(ClientConnectionPools.class)
                .hasSingleBean(StratumClientGroups.class));
  }

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
                    .doesNotHaveBean(StratumClientGroups.class));
  }

  @Test
  @DisplayName("the service's own pools bean wins over Stratum's")
  void servicePoolsWin() {
    var servicePools = new ClientConnectionPools(HttpClientProperties.builder().build().defaults());
    clients
        .withBean(ClientConnectionPools.class, () -> servicePools)
        .run(
            context ->
                assertThat(context.getBean(ClientConnectionPools.class)).isSameAs(servicePools));
  }

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
