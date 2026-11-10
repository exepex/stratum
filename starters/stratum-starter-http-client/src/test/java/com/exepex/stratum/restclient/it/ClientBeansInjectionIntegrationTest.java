package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientErrorCode;
import com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer;
import com.exepex.stratum.restclient.ClientResilience;
import com.exepex.stratum.restclient.StratumHttpClients;
import com.exepex.stratum.restclient.testsupport.CallChecks;
import com.exepex.stratum.restclient.testsupport.ClientBeanCalls;
import com.exepex.stratum.restclient.testsupport.ClientBeanCalls.ClientBean;
import com.exepex.stratum.restclient.testsupport.LedgerClientSettings;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import com.exepex.stratum.restclient.testsupport.beans.TeamBeans;
import com.exepex.stratum.restclient.testsupport.clients.ClientsApplication;
import com.exepex.stratum.test.StarterTestSupport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.restclient.autoconfigure.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

@SpringBootTest(
    classes = {ClientsApplication.class, TeamBeans.class},
    properties = HttpClientConstants.DEFAULTS + ".retry.delay=10ms")
@Timeout(30)
@DisplayName("Each client is a RestClient bean and a RestTemplate bean, injected by its name")
class ClientBeansInjectionIntegrationTest {

  /** The server every client calls. */
  @AutoClose private static final ScriptedDownstream DOWNSTREAM = ScriptedDownstream.start();

  @Autowired private ApplicationContext context;

  @Autowired private StratumHttpClients stratumHttpClients;

  @Autowired private ClientResilience resilience;

  /**
   * Points every client at the server.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    RestClientTestData.pointClientsAt(registry, DOWNSTREAM);
    registry.add(RestClientTestData.PARTNER_URL, DOWNSTREAM::url);
    registry.add(HttpClientConstants.CLIENTS + "." + TeamBeans.GUARDED + ".url", DOWNSTREAM::url);
  }

  @Nested
  @DisplayName("injection")
  class Injection {

    @Test
    @DisplayName("a constructor gets the client's beans; the RestClient is restClient(name)'s")
    void constructorInjection(@Autowired TeamBeans.InjectedInventory injected) {
      assertThat(injected.restClient())
          .isSameAs(stratumHttpClients.restClient(RestClientTestData.INVENTORY));
      assertThat(injected.restTemplate())
          .isSameAs(ClientBeanCalls.restTemplate(context, RestClientTestData.INVENTORY));
    }

    @Test
    @DisplayName("a setter and a lookup by qualifier get both beans, which call the client")
    void setterAndLookup(@Autowired TeamBeans.SetterInjectedInventory injected) {
      assertThat(injected.getRestClient())
          .isSameAs(ClientBeanCalls.restClient(context, RestClientTestData.INVENTORY));
      assertThat(injected.getRestTemplate())
          .isSameAs(ClientBeanCalls.restTemplate(context, RestClientTestData.INVENTORY));
      assertThat(
              ClientBeanCalls.get(
                  context,
                  ClientBean.REST_TEMPLATE,
                  RestClientTestData.INVENTORY,
                  ScriptedDownstream.STOCK))
          .isEqualTo(ScriptedDownstream.BODY);
    }

    @Test
    @DisplayName("the service's own beans stay the only ones injected without a qualifier")
    void ownUnqualifiedBeansKept(
        @Autowired RestTemplate unqualified, @Autowired List<RestClient> restClients) {
      assertThat(unqualified).isSameAs(context.getBean("ownRestTemplate"));
      assertThat(restClients)
          .containsExactlyInAnyOrder(
              context.getBean("ownRestClient", RestClient.class),
              context.getBean("ownPartnerRestClient", RestClient.class));
    }

    @Test
    @DisplayName("the service's own bean with a client's qualifier wins over Stratum's")
    void ownQualifiedBeanWins(
        @Autowired @Qualifier(RestClientTestData.PARTNER) RestClient partner) {
      assertThat(partner)
          .isSameAs(context.getBean("ownPartnerRestClient"))
          .isNotSameAs(stratumHttpClients.restClient(RestClientTestData.PARTNER));
    }

    @Test
    @DisplayName("next to the service's own bean for that name, a lookup by qualifier finds both")
    void lookupNextToOwnQualifiedBeanFindsBoth() {
      assertThatExceptionOfType(NoUniqueBeanDefinitionException.class)
          .isThrownBy(() -> ClientBeanCalls.restClient(context, RestClientTestData.PARTNER));
      assertThat(context.getBean("ownPartnerRestClient", RestClient.class))
          .isNotSameAs(stratumHttpClients.restClient(RestClientTestData.PARTNER));
    }

    @Test
    @DisplayName("the beans are named stratum.http-client.<name>.rest-client and .rest-template")
    void beanNames() {
      var prefix = HttpClientConstants.PREFIX + "." + RestClientTestData.INVENTORY;

      assertThat(context.getBean(prefix + ".rest-client"))
          .isSameAs(stratumHttpClients.restClient(RestClientTestData.INVENTORY));
      assertThat(context.getBean(prefix + ".rest-template"))
          .isSameAs(ClientBeanCalls.restTemplate(context, RestClientTestData.INVENTORY));
    }
  }

  @Nested
  @DisplayName("settings")
  class Settings {

    /** The files that set the same audit client, one in each format. */
    private static final List<String> FILES =
        List.of("client-beans.yml", "client-beans.properties");

    @ParameterizedTest
    @FieldSource("FILES")
    @DisplayName("set in .yml or .properties, both beans call the client with its retries")
    void filesBindTheSame(String file) {
      var attempts =
          attemptsPerBean(
              "audit",
              RestClientTestData.setting("spring.config.import", "classpath:" + file),
              RestClientTestData.setting("audit-url", DOWNSTREAM.url()));

      assertThat(attempts).containsExactly(2, 2);
    }

    @Test
    @DisplayName("set only in Java, a client gets both beans, with Java's retries")
    void javaOnlyClientGetsBeans(@TempDir Path folder) throws IOException {
      var attempts =
          StarterTestSupport.withCustomizers(
              folder,
              HttpClientPropertiesCustomizer.class,
              List.of(LedgerClientSettings.class),
              () -> attemptsPerBean(LedgerClientSettings.LEDGER));

      assertThat(attempts).containsExactly(1, 1);
    }
  }

  @Nested
  @DisplayName("customizers")
  class Customizers {

    @Test
    @DisplayName("customize reaches the RestClient bean")
    void customizeReachesRestClient() {
      ClientBeanCalls.get(
          context, ClientBean.REST_CLIENT, RestClientTestData.INVENTORY, ScriptedDownstream.STOCK);

      assertThat(DOWNSTREAM.headerValues(TeamBeans.TENANT).getLast()).isEqualTo("retail");
    }

    @Test
    @DisplayName("customizeRestTemplate's builders reach the RestTemplate bean, in their order")
    void customizeRestTemplateThreaded() {
      ClientBeanCalls.get(
          context,
          ClientBean.REST_TEMPLATE,
          RestClientTestData.INVENTORY,
          ScriptedDownstream.STOCK);

      assertThat(DOWNSTREAM.headerValues(TeamBeans.CHANNEL).getLast()).isEqualTo("web");
      assertThat(DOWNSTREAM.headerValues(TeamBeans.TENANT).getLast()).isEqualTo("wholesale");
    }

    @ParameterizedTest
    @FieldSource(ClientBeanCalls.BOTH_BEANS)
    @DisplayName("customizeRetry sets each bean's attempts")
    void customizeRetryReachesBeans(ClientBean bean) {
      assertThat(attempts(context, bean, RestClientTestData.PRICING)).isEqualTo(2);
    }

    @ParameterizedTest
    @FieldSource(ClientBeanCalls.BOTH_BEANS)
    @DisplayName("customizeCircuitBreaker sets each bean's breaker, which then refuses calls")
    void customizeCircuitBreakerReachesBeans(ClientBean bean) {
      resilience.circuitBreaker(TeamBeans.GUARDED).orElseThrow().reset();
      var failing = ScriptedDownstream.STATUS + "500";
      Stream.of(1, 2)
          .forEach(
              _ ->
                  assertThatExceptionOfType(HttpServerErrorException.class)
                      .isThrownBy(
                          () -> ClientBeanCalls.get(context, bean, TeamBeans.GUARDED, failing)));

      CallChecks.assertEndsWith(
          () -> ClientBeanCalls.get(context, bean, TeamBeans.GUARDED, failing),
          HttpClientErrorCode.BREAKER_OPEN);
    }
  }

  @Nested
  @DisplayName("startup")
  class Startup {

    @Test
    @DisplayName("a client without url stops startup with SX-HTTPC-1004, naming the key")
    void clientWithoutUrlStopsStartup() {
      var billingTimeout = HttpClientConstants.CLIENTS + ".billing.timeout";

      assertThatThrownBy(() -> startWith(RestClientTestData.setting(billingTimeout, "1s")).close())
          .rootCause()
          .hasMessageStartingWith("SX-HTTPC-1004")
          .hasMessageContaining(HttpClientConstants.CLIENTS + ".billing.url");
    }

    @Test
    @DisplayName("with stratum.http-client.enabled=false there is neither bean")
    void moduleOffMakesNoBeans() {
      try (var application =
          startWith(RestClientTestData.setting(HttpClientConstants.ENABLED, false))) {
        assertThat(application.getBeanNamesForType(RestClient.class)).isEmpty();
        assertThat(application.getBeanNamesForType(RestTemplate.class)).isEmpty();
      }
    }

    @Test
    @DisplayName(
        "without Spring Boot's RestTemplateBuilder, the RestTemplate still calls the client")
    void noRestTemplateBuilder() {
      try (var application =
          startWith(
              RestClientTestData.setting(
                  "spring.autoconfigure.exclude", RestTemplateAutoConfiguration.class.getName()))) {
        assertThat(application.getBeanNamesForType(RestTemplateBuilder.class)).isEmpty();
        assertThat(
                ClientBeanCalls.get(
                    application,
                    ClientBean.REST_TEMPLATE,
                    RestClientTestData.INVENTORY,
                    ScriptedDownstream.STOCK))
            .isEqualTo(ScriptedDownstream.BODY);
      }
    }
  }

  /** Starts the test application with both clients pointed at the server, and more settings. */
  private ConfigurableApplicationContext startWith(String... settings) {
    var allSettings =
        Stream.concat(
                Stream.of(
                    RestClientTestData.setting(RestClientTestData.INVENTORY_URL, DOWNSTREAM.url()),
                    RestClientTestData.setting(RestClientTestData.PRICING_URL, DOWNSTREAM.url())),
                Stream.of(settings))
            .toArray(String[]::new);
    return RestClientTestData.startApplication(allSettings);
  }

  /**
   * Starts the test application with more settings, and returns how often a GET answered 503 is
   * sent through each bean of a client, in the order of {@link ClientBeanCalls#BEANS}.
   */
  private List<Integer> attemptsPerBean(String client, String... settings) {
    try (var application = startWith(settings)) {
      return ClientBeanCalls.BEANS.stream()
          .map(bean -> attempts(application, bean, client))
          .toList();
    }
  }

  /** Returns how often a GET answered 503 through one bean of a client is sent. */
  private int attempts(ApplicationContext application, ClientBean bean, String client) {
    var requestsBefore = DOWNSTREAM.paths().size();

    assertThatExceptionOfType(HttpServerErrorException.ServiceUnavailable.class)
        .isThrownBy(
            () -> ClientBeanCalls.get(application, bean, client, ScriptedDownstream.UNAVAILABLE));
    return DOWNSTREAM.paths().size() - requestsBefore;
  }
}
