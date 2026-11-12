package com.exepex.stratum.restclient.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.restclient.ClientCallLog;
import com.exepex.stratum.restclient.ClientMeters;
import com.exepex.stratum.restclient.MicrometerClientMeters;
import com.exepex.stratum.restclient.testsupport.RecordedMeters;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.clients.InventoryClient;
import com.exepex.stratum.restclient.testsupport.clients.InventoryClients;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("RestClientAutoConfiguration makes the call log, and the meters with a registry")
class CallObservationAutoConfigurationTest {

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
  @DisplayName("by what the service has")
  class Defaults {

    @Test
    @DisplayName("with no meter registry, the call log is made and no meters")
    void noRegistryNoMeters() {
      clients.run(
          context ->
              assertThat(context)
                  .hasSingleBean(ClientCallLog.class)
                  .doesNotHaveBean(ClientMeters.class));
    }

    @Test
    @DisplayName("with a meter registry, Stratum's Micrometer meters are made")
    void registryGivesMeters() {
      clients
          .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
          .run(
              context ->
                  assertThat(context)
                      .getBean(ClientMeters.class)
                      .isInstanceOf(MicrometerClientMeters.class));
    }

    @Test
    @DisplayName("without Micrometer on the classpath, the clients still work, with no meters")
    void noMicrometer() {
      clients
          .withClassLoader(new FilteredClassLoader(MeterRegistry.class))
          .run(
              context ->
                  assertThat(context)
                      .hasNotFailed()
                      .hasSingleBean(InventoryClient.class)
                      .doesNotHaveBean(ClientMeters.class));
    }
  }

  @Nested
  @DisplayName("with the module switch")
  class Switch {

    @Test
    @DisplayName("with stratum.http-client.enabled=false, no call log and no meters")
    void moduleOffCreatesNothing() {
      clients
          .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
          .withPropertyValues(RestClientTestData.setting(HttpClientConstants.ENABLED, false))
          .run(
              context ->
                  assertThat(context)
                      .doesNotHaveBean(ClientCallLog.class)
                      .doesNotHaveBean(ClientMeters.class));
    }
  }

  @Nested
  @DisplayName("when the service declares a bean")
  class BackOff {

    @Test
    @DisplayName("the service's own call log wins over Stratum's")
    void serviceCallLogWins() {
      var serviceCallLog = new ClientCallLog(HttpClientProperties.builder().build(), null);

      clients
          .withBean(ClientCallLog.class, () -> serviceCallLog)
          .run(
              context ->
                  assertThat(context)
                      .hasSingleBean(ClientCallLog.class)
                      .getBean(ClientCallLog.class)
                      .isSameAs(serviceCallLog));
    }

    @Test
    @DisplayName("the service's own meters win over Stratum's")
    void serviceMetersWin() {
      var serviceMeters = new RecordedMeters();

      clients
          .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
          .withBean(ClientMeters.class, () -> serviceMeters)
          .run(
              context ->
                  assertThat(context)
                      .hasSingleBean(ClientMeters.class)
                      .getBean(ClientMeters.class)
                      .isSameAs(serviceMeters));
    }
  }
}
