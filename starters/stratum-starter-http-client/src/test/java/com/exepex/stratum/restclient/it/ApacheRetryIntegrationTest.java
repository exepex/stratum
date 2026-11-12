package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.restclient.DownstreamException;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import com.exepex.stratum.restclient.testsupport.clients.ClientsApplication;
import com.exepex.stratum.restclient.testsupport.clients.InventoryClient;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = ClientsApplication.class, properties = RestClientTestData.NO_RETRIES)
@Timeout(30)
@DisplayName("Apache HttpClient 5 sends no call again on its own")
class ApacheRetryIntegrationTest {

  /** The inventory service. */
  @AutoClose private static final ScriptedDownstream INVENTORY = ScriptedDownstream.start();

  @Autowired private InventoryClient inventoryClient;

  /**
   * Points the clients at the server.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    RestClientTestData.pointClientsAt(registry, INVENTORY);
  }

  @Test
  @DisplayName("a 503 answer fails the call after one request; Apache's resend after 503 is off")
  void sentOnceOn503() {
    assertThatExceptionOfType(DownstreamException.class)
        .isThrownBy(inventoryClient::unavailable)
        .satisfies(
            failure ->
                assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    assertThat(INVENTORY.paths()).containsOnlyOnce(ScriptedDownstream.UNAVAILABLE);
  }
}
