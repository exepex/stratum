package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.restclient.StratumHttpClients;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import com.exepex.stratum.restclient.testsupport.clients.ClientsApplication;
import com.exepex.stratum.restclient.testsupport.clients.InventoryClient;
import com.exepex.stratum.restclient.testsupport.clients.PricingClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Each group sets one kind of auth on the inventory client and none on the pricing client, so each
 * check also shows that a client without auth sends no credential.
 */
@SpringBootTest(classes = ClientsApplication.class)
@DisplayName("A client's fixed credential goes with every call of that client, and of no other")
class StaticCredentialIntegrationTest {

  /** The prefix of the inventory client's auth settings. */
  private static final String AUTH = RestClientTestData.INVENTORY_SETTINGS + ".auth";

  /** The header a vendor names for its API key. */
  private static final String VENDOR_KEY_HEADER = "X-Vendor-Key";

  /** The header an API key goes in when the client names none. */
  private static final String DEFAULT_KEY_HEADER = "X-Api-Key";

  /** The calls each test makes: two of the inventory client, then one of the pricing client. */
  private static final int CALLS = 3;

  /** The server both clients call, shared by every group's application. */
  @AutoClose private static final ScriptedDownstream DOWNSTREAM = ScriptedDownstream.start();

  /**
   * Points both clients at the server.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    RestClientTestData.pointClientsAt(registry, DOWNSTREAM);
  }

  /** Calls the inventory client twice, then the pricing client once. */
  private void callBothClients(InventoryClient inventory, PricingClient pricing) {
    inventory.stock();
    inventory.stock();
    pricing.price();
  }

  /** Returns one header's value in each of the last test's calls, in order. */
  private List<String> sent(String header) {
    var values = DOWNSTREAM.headerValues(header);
    return values.subList(values.size() - CALLS, values.size());
  }

  @Nested
  @TestPropertySource(properties = AUTH + ".api-key=" + RestClientTestData.SECRET)
  @DisplayName("with an API key")
  class ApiKey {

    @Test
    @DisplayName("the key goes in X-Api-Key on each call of that client only")
    void apiKeyDefaultHeader(
        @Autowired InventoryClient inventory, @Autowired PricingClient pricing) {
      callBothClients(inventory, pricing);

      assertThat(sent(DEFAULT_KEY_HEADER))
          .containsExactly(RestClientTestData.SECRET, RestClientTestData.SECRET, "");
      assertThat(sent(HttpHeaders.AUTHORIZATION)).containsOnly("");
    }

    @Test
    @DisplayName("the key goes with the client's ad-hoc calls too")
    void apiKeyOnAdHocCall(@Autowired StratumHttpClients clients) {
      var adHoc = clients.restClient(RestClientTestData.INVENTORY);

      adHoc.get().uri(ScriptedDownstream.STOCK).retrieve().toBodilessEntity();

      var values = DOWNSTREAM.headerValues(DEFAULT_KEY_HEADER);
      assertThat(values.getLast()).isEqualTo(RestClientTestData.SECRET);
    }
  }

  @Nested
  @TestPropertySource(
      properties = {
        AUTH + ".api-key=" + RestClientTestData.SECRET,
        AUTH + ".api-key-header=" + VENDOR_KEY_HEADER
      })
  @DisplayName("with an API key and its own header")
  class ApiKeyOwnHeader {

    @Test
    @DisplayName("the key goes in the header the client names, not in X-Api-Key")
    void apiKeyOwnHeader(@Autowired InventoryClient inventory, @Autowired PricingClient pricing) {
      callBothClients(inventory, pricing);

      assertThat(sent(VENDOR_KEY_HEADER))
          .containsExactly(RestClientTestData.SECRET, RestClientTestData.SECRET, "");
      assertThat(sent(DEFAULT_KEY_HEADER)).containsOnly("");
    }
  }

  @Nested
  @TestPropertySource(properties = AUTH + ".bearer=" + RestClientTestData.SECRET)
  @DisplayName("with a bearer token")
  class Bearer {

    @Test
    @DisplayName("the token goes in Authorization on each call of that client only")
    void bearer(@Autowired InventoryClient inventory, @Autowired PricingClient pricing) {
      callBothClients(inventory, pricing);

      var expected = "Bearer " + RestClientTestData.SECRET;
      assertThat(sent(HttpHeaders.AUTHORIZATION)).containsExactly(expected, expected, "");
    }
  }

  @Nested
  @TestPropertySource(
      properties = {
        AUTH + ".basic.username=orders",
        AUTH + ".basic.password=" + RestClientTestData.SECRET
      })
  @DisplayName("with Basic credentials")
  class Basic {

    @Test
    @DisplayName("they go in Authorization, encoded, on each call of that client only")
    void basic(@Autowired InventoryClient inventory, @Autowired PricingClient pricing) {
      callBothClients(inventory, pricing);

      var encoded =
          Base64.getEncoder()
              .encodeToString(
                  ("orders:" + RestClientTestData.SECRET).getBytes(StandardCharsets.UTF_8));
      var expected = "Basic " + encoded;
      assertThat(sent(HttpHeaders.AUTHORIZATION)).containsExactly(expected, expected, "");
    }
  }
}
