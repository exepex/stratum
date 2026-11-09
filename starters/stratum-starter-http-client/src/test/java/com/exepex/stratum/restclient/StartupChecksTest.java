package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.badname.BadNameClients;
import com.exepex.stratum.restclient.testsupport.clients.InventoryClient;
import com.exepex.stratum.restclient.testsupport.clients.InventoryClients;
import com.exepex.stratum.restclient.testsupport.generated.PartnerOrdersApi;
import com.exepex.stratum.restclient.testsupport.reactive.ReactiveClients;
import com.exepex.stratum.restclient.testsupport.reactive.ReactiveStockClient;
import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;

@DisplayName("A client set up wrongly stops startup with a code that names the client or the key")
class StartupChecksTest {

  /** Both clients of the clients package, each with an address. */
  private final ApplicationContextRunner clients =
      RestClientTestData.contextRunner()
          .withUserConfiguration(InventoryClients.class)
          .withPropertyValues(
              RestClientTestData.setting(
                  RestClientTestData.INVENTORY_URL, RestClientTestData.UNUSED_URL),
              RestClientTestData.setting(
                  RestClientTestData.PRICING_URL, RestClientTestData.UNUSED_URL));

  @Test
  @DisplayName("with every client's address set, the application starts")
  void startsWhenSetUpRight() {
    clients.run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @DisplayName("a Spring Boot spring.http.serviceclient key for a Stratum client: SX-HTTPC-1001")
  void bootGroupKeyStopsStartup() {
    clients
        .withPropertyValues(
            HttpClientConstants.BOOT_SERVICE_CLIENTS + ".inventory.read-timeout=20s")
        .run(
            context ->
                assertStopped(context, "SX-HTTPC-1001")
                    .hasMessageContaining(RestClientTestData.INVENTORY_SETTINGS + ".*")
                    .hasMessageNotContaining("20s"));
  }

  @Test
  @DisplayName("a Spring Boot key for a group that is not a Stratum client is left alone")
  void bootKeyOfOtherGroupAllowed() {
    clients
        .withPropertyValues(HttpClientConstants.BOOT_SERVICE_CLIENTS + ".payments.read-timeout=20s")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @DisplayName("a method that returns Mono: SX-HTTPC-1002, naming the method")
  void reactiveMethodStopsStartup() {
    RestClientTestData.contextRunner()
        .withUserConfiguration(ReactiveClients.class)
        .run(
            context ->
                assertStopped(context, "SX-HTTPC-1002")
                    .hasMessageContaining("ReactiveStockClient.stock"));
  }

  @Test
  @DisplayName("a client name that is not a valid key part: SX-HTTPC-1003, naming the interface")
  void badClientNameStopsStartup() {
    RestClientTestData.contextRunner()
        .withUserConfiguration(BadNameClients.class)
        .run(
            context ->
                assertStopped(context, "SX-HTTPC-1003").hasMessageContaining("BadNameClient"));
  }

  @Test
  @DisplayName("a client without an address: SX-HTTPC-1004, naming the key")
  void missingUrlStopsStartup() {
    RestClientTestData.contextRunner()
        .withUserConfiguration(InventoryClients.class)
        .withPropertyValues(
            RestClientTestData.setting(
                RestClientTestData.INVENTORY_URL, RestClientTestData.UNUSED_URL))
        .run(
            context ->
                assertStopped(context, "SX-HTTPC-1004")
                    .hasMessageContaining(RestClientTestData.PRICING_URL));
  }

  @Test
  @DisplayName("a client whose address is blank, as an unset ${VARIABLE:} gives: SX-HTTPC-1004")
  void blankUrlStopsStartup() {
    RestClientTestData.contextRunner()
        .withUserConfiguration(InventoryClients.class)
        .withPropertyValues(
            RestClientTestData.setting(
                RestClientTestData.INVENTORY_URL, RestClientTestData.UNUSED_URL),
            RestClientTestData.setting(RestClientTestData.PRICING_URL, " "))
        .run(
            context ->
                assertStopped(context, "SX-HTTPC-1004")
                    .hasMessageContaining(RestClientTestData.PRICING_URL));
  }

  @Nested
  @DisplayName("a client's interfaces setting")
  class NamedInterfaces {

    /** The runner with the partner client's address and nothing scanned. */
    private final ApplicationContextRunner partner =
        RestClientTestData.contextRunner()
            .withPropertyValues(
                RestClientTestData.setting(
                    RestClientTestData.PARTNER_URL, RestClientTestData.UNUSED_URL));

    @Test
    @DisplayName("a generated interface without @StratumClient, named there, starts")
    void namedInterfaceStarts() {
      partner
          .withPropertyValues(
              RestClientTestData.setting(
                  RestClientTestData.PARTNER_INTERFACE, PartnerOrdersApi.class.getName()))
          .run(context -> assertThat(context).hasSingleBean(PartnerOrdersApi.class));
    }

    @Test
    @DisplayName("a name that is on no class: SX-HTTPC-1007, naming the key")
    void missingInterfaceStopsStartup() {
      partner
          .withPropertyValues(
              RestClientTestData.setting(
                  RestClientTestData.PARTNER_INTERFACE, "com.partner.MissingApi"))
          .run(
              context ->
                  assertStopped(context, "SX-HTTPC-1007")
                      .hasMessageContaining(RestClientTestData.PARTNER_SETTINGS + ".interfaces"));
    }

    @Test
    @DisplayName("a class instead of an interface: SX-HTTPC-1007")
    void classStopsStartup() {
      partner
          .withPropertyValues(
              RestClientTestData.setting(
                  RestClientTestData.PARTNER_INTERFACE, String.class.getName()))
          .run(context -> assertStopped(context, "SX-HTTPC-1007").hasMessageContaining("class"));
    }

    @Test
    @DisplayName("an interface marked for another client: SX-HTTPC-1008, naming both clients")
    void interfaceOfTwoClientsStopsStartup() {
      partner
          .withPropertyValues(
              RestClientTestData.setting(
                  RestClientTestData.PARTNER_INTERFACE, InventoryClient.class.getName()))
          .run(
              context ->
                  assertStopped(context, "SX-HTTPC-1008")
                      .hasMessageContaining(RestClientTestData.INVENTORY)
                      .hasMessageContaining(RestClientTestData.PARTNER_SETTINGS));
    }

    @Test
    @DisplayName("a named interface with a Mono method: SX-HTTPC-1002, naming the method")
    void namedReactiveInterfaceStopsStartup() {
      RestClientTestData.contextRunner()
          .withPropertyValues(
              RestClientTestData.setting(
                  RestClientTestData.INVENTORY_URL, RestClientTestData.UNUSED_URL),
              RestClientTestData.setting(
                  RestClientTestData.INVENTORY_SETTINGS + ".interfaces[0]",
                  ReactiveStockClient.class.getName()))
          .run(
              context ->
                  assertStopped(context, "SX-HTTPC-1002")
                      .hasMessageContaining("ReactiveStockClient.stock"));
    }
  }

  @Nested
  @DisplayName("a client whose auth is a Spring Security token")
  class TokenAuth {

    /** The inventory client with an address. */
    private final ApplicationContextRunner inventory =
        clients.withPropertyValues(
            RestClientTestData.setting(
                RestClientTestData.INVENTORY_CLIENT_CREDENTIALS, RestClientTestData.REGISTRATION));

    @Test
    @DisplayName("client-credentials with its registration: the application starts")
    void registrationFound() {
      inventory
          .withPropertyValues(
              RestClientTestData.registrationSettings(
                  "client_credentials", RestClientTestData.UNUSED_URL))
          .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("client-credentials without its registration: SX-HTTPC-1005, naming both")
    void registrationMissingStopsStartup() {
      inventory.run(
          context ->
              assertStopped(context, "SX-HTTPC-1005")
                  .hasMessageContaining(RestClientTestData.INVENTORY_CLIENT_CREDENTIALS)
                  .hasMessageContaining(RestClientTestData.REGISTRATION));
    }

    @Test
    @DisplayName("client-credentials naming a registration of another grant: SX-HTTPC-1005")
    void otherGrantStopsStartup() {
      inventory
          .withPropertyValues(
              RestClientTestData.registrationSettings(
                  "authorization_code", RestClientTestData.UNUSED_URL))
          .run(context -> assertStopped(context, "SX-HTTPC-1005"));
    }

    @Test
    @DisplayName("client-credentials without Spring Security's OAuth2 client: SX-HTTPC-1006")
    void oauthClientMissingStopsStartup() {
      inventory
          .withClassLoader(new FilteredClassLoader(OAuth2AuthorizedClientManager.class))
          .run(
              context ->
                  assertStopped(context, "SX-HTTPC-1006")
                      .hasMessageContaining(RestClientTestData.INVENTORY_SETTINGS + ".auth"));
    }

    @Test
    @DisplayName("token-relay without Spring Security's resource server: SX-HTTPC-1006")
    void resourceServerMissingStopsStartup() {
      clients
          .withPropertyValues(RestClientTestData.INVENTORY_SETTINGS + ".auth.token-relay=true")
          .withClassLoader(new FilteredClassLoader(AbstractOAuth2TokenAuthenticationToken.class))
          .run(context -> assertStopped(context, "SX-HTTPC-1006"));
    }
  }

  /** Checks that startup stopped with a Stratum code, and returns the check of its message. */
  private AbstractThrowableAssert<?, ? extends Throwable> assertStopped(
      AssertableApplicationContext context, String code) {
    assertThat(context).hasFailed();
    // A check while scanning fails startup itself; one while building a client is a cause.
    return assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
        .isInstanceOf(StratumException.class)
        .hasMessageStartingWith(code);
  }
}
