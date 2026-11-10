package com.exepex.stratum.restclient.testsupport.beans;

import com.exepex.stratum.restclient.ClientAttempt;
import com.exepex.stratum.restclient.StratumClientCustomizer;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.RetryConfig;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/**
 * A service's own beans next to Stratum's client beans: beans that take the inventory client's
 * beans, its own unqualified {@code RestClient} and {@code RestTemplate}, its own {@code
 * RestClient} with the partner client's qualifier, and two customizers.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TeamBeans {

  /** The header the customizers set to the tenant. */
  public static final String TENANT = "X-Tenant";

  /** The header the first customizer sets to the channel. */
  public static final String CHANNEL = "X-Channel";

  /** A client whose breaker the first customizer makes judge two calls. */
  public static final String GUARDED = "guarded";

  /**
   * Makes the service's bean that takes the inventory client's beans through its constructor.
   *
   * @param restClient the client's {@code RestClient}
   * @param restTemplate the client's {@code RestTemplate}
   * @return the bean
   */
  @Bean
  InjectedInventory injectedInventory(
      @Qualifier(RestClientTestData.INVENTORY) RestClient restClient,
      @Qualifier(RestClientTestData.INVENTORY) RestTemplate restTemplate) {
    return new InjectedInventory(restClient, restTemplate);
  }

  /**
   * Makes the service's bean that takes the inventory client's beans through setters.
   *
   * @return the bean
   */
  @Bean
  SetterInjectedInventory setterInjectedInventory() {
    return new SetterInjectedInventory();
  }

  /**
   * Makes the service's own unqualified {@code RestClient}.
   *
   * @return the client
   */
  @Bean
  RestClient ownRestClient() {
    return RestClient.create();
  }

  /**
   * Makes the service's own unqualified {@code RestTemplate}.
   *
   * @return the client
   */
  @Bean
  RestTemplate ownRestTemplate() {
    return new RestTemplate();
  }

  /**
   * Makes the service's own {@code RestClient} with the partner client's qualifier.
   *
   * @return the client
   */
  @Bean
  @Qualifier(RestClientTestData.PARTNER)
  RestClient ownPartnerRestClient() {
    return RestClient.create();
  }

  /**
   * Makes the second customizer, declared before the first, so only their order runs them in turn.
   *
   * @return the customizer
   */
  @Bean
  @Order(2)
  StratumClientCustomizer wholesaleTenant() {
    return new WholesaleTenant();
  }

  /**
   * Makes the first customizer.
   *
   * @return the customizer
   */
  @Bean
  @Order(1)
  StratumClientCustomizer retailSetup() {
    return new RetailSetup();
  }

  /**
   * A service's bean that takes the inventory client's beans through its constructor.
   *
   * @param restClient the client's {@code RestClient}
   * @param restTemplate the client's {@code RestTemplate}
   */
  public record InjectedInventory(RestClient restClient, RestTemplate restTemplate) {}

  /** A service's bean that takes the inventory client's beans through setters. */
  @Getter
  public static class SetterInjectedInventory {

    /** The client's {@code RestClient}. */
    @Setter(onMethod_ = @Autowired, onParam_ = @Qualifier(RestClientTestData.INVENTORY))
    private RestClient restClient;

    /** The client's {@code RestTemplate}. */
    @Setter(onMethod_ = @Autowired, onParam_ = @Qualifier(RestClientTestData.INVENTORY))
    private RestTemplate restTemplate;
  }

  /**
   * The first customizer: headers for the inventory client's beans, two attempts for pricing and a
   * breaker that judges two calls for the guarded client.
   */
  static class RetailSetup implements StratumClientCustomizer {

    @Override
    public void customize(String client, RestClient.Builder builder) {
      if (RestClientTestData.INVENTORY.equals(client)) {
        builder.defaultHeader(TENANT, "retail");
      }
    }

    @Override
    public RestTemplateBuilder customizeRestTemplate(String client, RestTemplateBuilder builder) {
      return RestClientTestData.INVENTORY.equals(client)
          ? builder.defaultHeader(TENANT, "retail").defaultHeader(CHANNEL, "web")
          : builder;
    }

    @Override
    public void customizeRetry(String client, RetryConfig.Builder<ClientAttempt> retry) {
      if (RestClientTestData.PRICING.equals(client)) {
        retry.maxAttempts(2);
      }
    }

    @Override
    public void customizeCircuitBreaker(String client, CircuitBreakerConfig.Builder breaker) {
      if (GUARDED.equals(client)) {
        breaker.slidingWindowSize(2).minimumNumberOfCalls(2);
      }
    }
  }

  /** The second customizer, which changes the tenant the first one set on the RestTemplate. */
  static class WholesaleTenant implements StratumClientCustomizer {

    @Override
    public void customize(String client, RestClient.Builder builder) {
      // The RestClient keeps the first customizer's tenant.
    }

    @Override
    public RestTemplateBuilder customizeRestTemplate(String client, RestTemplateBuilder builder) {
      return RestClientTestData.INVENTORY.equals(client)
          ? builder.defaultHeader(TENANT, "wholesale")
          : builder;
    }
  }
}
