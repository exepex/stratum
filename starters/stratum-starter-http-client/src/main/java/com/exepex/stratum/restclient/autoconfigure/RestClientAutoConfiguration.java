package com.exepex.stratum.restclient.autoconfigure;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.core.properties.StratumCustomizers;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.restclient.ClientConnectionPools;
import com.exepex.stratum.restclient.ClientErrors;
import com.exepex.stratum.restclient.ClientExchangeDecorator;
import com.exepex.stratum.restclient.ClientResilience;
import com.exepex.stratum.restclient.DefaultStratumHttpClients;
import com.exepex.stratum.restclient.OptionalNotFound;
import com.exepex.stratum.restclient.StratumClientBuilders;
import com.exepex.stratum.restclient.StratumClientCustomizer;
import com.exepex.stratum.restclient.StratumClientGroups;
import com.exepex.stratum.restclient.StratumClientRegistrar;
import com.exepex.stratum.restclient.StratumHttpClients;
import java.time.Clock;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.restclient.autoconfigure.service.HttpServiceClientAutoConfiguration;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Fallback;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * Turns each {@code @StratumClient} interface into a client with its own connection pool, unless
 * {@code stratum.http-client.enabled=false}. Ordered before Spring Boot's HTTP service client
 * auto-configuration, which sets up the groups only when their registry exists, and the registrar
 * this one imports creates it.
 */
@StratumInternal
@AutoConfiguration(before = HttpServiceClientAutoConfiguration.class)
@AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
@ConditionalOnStratumModule(HttpClientConstants.MODULE)
@Import(StratumClientRegistrar.class)
public final class RestClientAutoConfiguration {

  /**
   * Stops startup with {@code SX-CORE-1004} when the service declares a settings customizer as a
   * bean, which would be silently ignored.
   *
   * @param beanFactory the service's beans
   */
  public RestClientAutoConfiguration(ListableBeanFactory beanFactory) {
    StratumCustomizers.rejectBeans(beanFactory);
  }

  /**
   * Creates the connection pools of Stratum's clients, which close with the application.
   *
   * @param environment the service's environment, from which the settings are resolved
   * @param sslBundles Spring Boot's SSL bundles, for clients that name one
   * @return the pools
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public ClientConnectionPools stratumClientConnectionPools(
      Environment environment, ObjectProvider<SslBundles> sslBundles) {
    return new ClientConnectionPools(
        HttpClientProperties.resolve(environment).defaults(), sslBundles);
  }

  /**
   * Creates Stratum's set-up of a client's builder, shared by the client interfaces and {@link
   * StratumHttpClients}.
   *
   * @param environment the service's environment, from which the settings are resolved
   * @param pools the connection pools
   * @param customizers the service's changes to the clients' builders
   * @return the set-up
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public StratumClientBuilders stratumClientBuilders(
      Environment environment,
      ClientConnectionPools pools,
      ObjectProvider<StratumClientCustomizer> customizers) {
    return new StratumClientBuilders(
        HttpClientProperties.resolve(environment), pools, environment, customizers);
  }

  /**
   * Creates the set-up of each Stratum client's group.
   *
   * @param environment the service's environment, from which the settings are resolved
   * @param builders Stratum's set-up of a client's builder
   * @param decorators what Stratum puts around each client's calls, such as the retries
   * @return the set-up
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public StratumClientGroups stratumClientGroups(
      Environment environment,
      StratumClientBuilders builders,
      ObjectProvider<ClientExchangeDecorator> decorators) {
    return new StratumClientGroups(HttpClientProperties.resolve(environment), builders, decorators);
  }

  /**
   * Creates the retries and circuit breaker of each Stratum client.
   *
   * @param environment the service's environment, from which the settings are resolved
   * @param customizers the service's changes to the clients' retry and breaker rules
   * @return the retries and breakers
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public ClientResilience stratumClientResilience(
      Environment environment, ObjectProvider<StratumClientCustomizer> customizers) {
    return new ClientResilience(
        HttpClientProperties.resolve(environment), customizers, Clock.systemUTC());
  }

  /**
   * Creates the translation of the failure that ends a call of a Stratum client's interface into a
   * {@code DownstreamException} or a code.
   *
   * @param customizers the service's changes to the clients, which may map an error answer
   * @return the translation
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public ClientErrors stratumClientErrors(ObjectProvider<StratumClientCustomizer> customizers) {
    return new ClientErrors(customizers);
  }

  /**
   * Creates the reading of a 404 as an empty {@code Optional}, for a method of a Stratum client's
   * interface that returns {@code Optional}.
   *
   * @return the reading of that 404
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public OptionalNotFound stratumOptionalNotFound() {
    return new OptionalNotFound();
  }

  /**
   * Creates the {@code RestClient} and settings of each client for clients Stratum did not build.
   *
   * @param environment the service's environment, from which the settings are resolved
   * @param builders Stratum's set-up of a client's builder
   * @param restClientCustomizers Spring Boot's and the service's {@code RestClient} customizers
   * @return the clients
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public StratumHttpClients stratumHttpClients(
      Environment environment,
      StratumClientBuilders builders,
      ObjectProvider<RestClientCustomizer> restClientCustomizers) {
    return new DefaultStratumHttpClients(
        HttpClientProperties.resolve(environment), builders, restClientCustomizers);
  }
}
