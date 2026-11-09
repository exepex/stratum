package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.testsupport.CallChecks;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import com.exepex.stratum.restclient.testsupport.clients.ClientsApplication;
import com.exepex.stratum.restclient.testsupport.clients.InventoryClient;
import java.net.http.HttpTimeoutException;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.registry.ImportHttpServices;

@Timeout(30)
@DisplayName(
    "A service's own RestClient keeps Spring Boot's engine, Java's client by Stratum's default")
class ForeignClientIntegrationTest {

  @Nested
  @DisplayName("the request factory Spring Boot gives the service's own clients")
  class ServiceRequestFactory {

    @Test
    @DisplayName(
        "Spring Boot's RestClient builder sends with Java's client, not Stratum's Apache one")
    void plainBuilderKeepsJdkClient() {
      assertThat(requestFactoryOfServiceClients()).isInstanceOf(JdkClientHttpRequestFactory.class);
    }

    @Test
    @DisplayName("the service's own spring.http.clients.imperative.factory wins")
    void teamFactoryWins() {
      assertThat(
              requestFactoryOfServiceClients(
                  RestClientTestData.setting(
                      HttpClientConstants.BOOT_IMPERATIVE_FACTORY, "simple")))
          .isInstanceOf(SimpleClientHttpRequestFactory.class);
    }

    @Test
    @DisplayName("with stratum.http-client.enabled=false, Spring Boot picks the engine itself")
    void moduleOffKeepsBootDetection() {
      assertThat(
              requestFactoryOfServiceClients(
                  RestClientTestData.setting(HttpClientConstants.ENABLED, false)))
          .isInstanceOf(HttpComponentsClientHttpRequestFactory.class);
    }

    /**
     * Starts the application, with its environment post-processors, and returns the request factory
     * Spring Boot gives the service's own clients.
     */
    private ClientHttpRequestFactory requestFactoryOfServiceClients(String... settings) {
      var allSettings =
          Stream.concat(
                  Stream.of(
                      RestClientTestData.setting(
                          RestClientTestData.INVENTORY_URL, RestClientTestData.UNUSED_URL),
                      RestClientTestData.setting(
                          RestClientTestData.PRICING_URL, RestClientTestData.UNUSED_URL)),
                  Arrays.stream(settings))
              .toArray(String[]::new);
      try (var context = RestClientTestData.startApplication(allSettings)) {
        return context.getBean(ClientHttpRequestFactoryBuilder.class).build();
      }
    }
  }

  @Nested
  @SpringBootTest(
      classes = {ClientsApplication.class, ServiceOwnGroup.PaymentsClients.class},
      properties =
          HttpClientConstants.BOOT_SERVICE_CLIENTS
              + "."
              + ServiceOwnGroup.PAYMENTS
              + ".read-timeout=300ms")
  @DisplayName("with the service's own HTTP service group next to Stratum's clients")
  class ServiceOwnGroup {

    /** The service's own group, set up only with Spring Boot's keys. */
    static final String PAYMENTS = "payments";

    /** The server of every client in this group. */
    @AutoClose static final ScriptedDownstream DOWNSTREAM = ScriptedDownstream.start();

    @Autowired private PaymentsClient paymentsClient;

    @Autowired private InventoryClient inventoryClient;

    /**
     * Points Stratum's clients and the service's own group at this group's server.
     *
     * @param registry the test's properties
     */
    @DynamicPropertySource
    static void addresses(DynamicPropertyRegistry registry) {
      RestClientTestData.pointClientsAt(registry, DOWNSTREAM);
      registry.add(
          HttpClientConstants.BOOT_SERVICE_CLIENTS + "." + PAYMENTS + ".base-url", DOWNSTREAM::url);
    }

    /** Lets a held call answer, so that the server closes promptly. */
    @AfterEach
    void releaseHeldCalls() {
      DOWNSTREAM.release();
    }

    @Test
    @DisplayName("the service's group keeps Spring Boot's settings, such as its read-timeout")
    void serviceGroupKeepsBootSettings() {
      CallChecks.assertFailsFast(paymentsClient::held, HttpTimeoutException.class);
    }

    @Test
    @DisplayName("Stratum's clients still answer")
    void stratumClientsStillAnswer() {
      assertThat(inventoryClient.stock()).isEqualTo(ScriptedDownstream.BODY);
    }

    /** A client of the service's own, without {@code @StratumClient}. */
    @FunctionalInterface
    interface PaymentsClient {

      /**
       * Returns an answer once the downstream releases it.
       *
       * @return the body
       */
      @GetExchange(ScriptedDownstream.HELD)
      String held();
    }

    /** Registers the service's own group, as a service does without Stratum. */
    @TestConfiguration(proxyBeanMethods = false)
    @ImportHttpServices(group = PAYMENTS, types = PaymentsClient.class)
    static class PaymentsClients {}
  }
}
