package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.exepex.stratum.httpclient.ClientSettings;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.clients.InventoryClient;
import com.exepex.stratum.test.LogLineCapture;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("MockClients warns about each mocked client and refuses a client that is not mocked")
class DefaultMockClientsTest {

  /** The mocks under test: payments is mocked, inventory is not. */
  private final DefaultMockClients mockClients =
      new DefaultMockClients(
          HttpClientProperties.builder()
              .defaults(ClientSettings.builder().build())
              .clients(
                  Map.of(
                      RestClientTestData.PAYMENTS,
                      ClientSettings.builder().mock(true).build(),
                      RestClientTestData.INVENTORY,
                      ClientSettings.builder().build()))
              .build(),
          RestClientTestData.answersWithoutMapper());

  @Nested
  @DisplayName("At startup")
  class Startup {

    @Test
    @DisplayName("each startup warns once per mocked client, naming it")
    void warnsPerMockedClient() {
      var log = new LogLineCapture(DefaultMockClients.class.getName());
      log.start();
      try {
        mockClients.afterSingletonsInstantiated();
      } finally {
        log.stop();
      }

      assertThat(log.onlyLine().getFormattedMessage())
          .isEqualTo(
              "HTTP client payments answers from mocks and never calls its service (mock: true)");
    }
  }

  @Nested
  @DisplayName("Asking for a client")
  class ClientLookup {

    @Test
    @DisplayName("asking for an interface whose client is not mocked stops the test clearly")
    void refusesClientNotMocked() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> mockClients.of(InventoryClient.class))
          .withMessageContaining(InventoryClient.class.getName())
          .withMessageContaining("mock=true");
    }
  }
}
