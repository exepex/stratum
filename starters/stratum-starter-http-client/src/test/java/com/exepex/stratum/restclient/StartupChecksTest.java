package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.badname.BadNameClients;
import com.exepex.stratum.restclient.testsupport.clients.InventoryClients;
import com.exepex.stratum.restclient.testsupport.reactive.ReactiveClients;
import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;

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
