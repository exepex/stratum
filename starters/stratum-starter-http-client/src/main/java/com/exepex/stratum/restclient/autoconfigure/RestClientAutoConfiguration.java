package com.exepex.stratum.restclient.autoconfigure;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.core.logging.LogRedaction;
import com.exepex.stratum.core.properties.StratumCustomizers;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.restclient.ClientCallLog;
import com.exepex.stratum.restclient.ClientCallReports;
import com.exepex.stratum.restclient.ClientConnectionPools;
import com.exepex.stratum.restclient.ClientErrors;
import com.exepex.stratum.restclient.ClientExchangeDecorator;
import com.exepex.stratum.restclient.ClientInterceptors;
import com.exepex.stratum.restclient.ClientMeters;
import com.exepex.stratum.restclient.ClientResilience;
import com.exepex.stratum.restclient.DefaultMockClients;
import com.exepex.stratum.restclient.DefaultStratumHttpClients;
import com.exepex.stratum.restclient.MicrometerClientMeters;
import com.exepex.stratum.restclient.MockAnswers;
import com.exepex.stratum.restclient.MockClients;
import com.exepex.stratum.restclient.OptionalNotFound;
import com.exepex.stratum.restclient.ServiceTokenResend;
import com.exepex.stratum.restclient.StratumClientBeansRegistrar;
import com.exepex.stratum.restclient.StratumClientBuilders;
import com.exepex.stratum.restclient.StratumClientChecks;
import com.exepex.stratum.restclient.StratumClientCustomizer;
import com.exepex.stratum.restclient.StratumClientGroups;
import com.exepex.stratum.restclient.StratumClientRegistrar;
import com.exepex.stratum.restclient.StratumHttpClients;
import com.exepex.stratum.restclient.TokenInitializers;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.restclient.autoconfigure.service.HttpServiceClientAutoConfiguration;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns each {@code @StratumClient} interface into a client with its own connection pool, and gives
 * each configured client a {@code RestClient} and a {@code RestTemplate} bean, unless {@code
 * stratum.http-client.enabled=false}. Ordered before Spring Boot's HTTP service client
 * auto-configuration, which sets up the groups only when their registry exists, and the registrar
 * this one imports creates it.
 */
@StratumInternal
@AutoConfiguration(
    before = HttpServiceClientAutoConfiguration.class,
    afterName = {
      RestClientAutoConfiguration.METRICS_AUTO_CONFIGURATION,
      RestClientAutoConfiguration.COMPOSITE_REGISTRY_AUTO_CONFIGURATION
    })
@AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
@ConditionalOnStratumModule(HttpClientConstants.MODULE)
@Import({StratumClientRegistrar.class, StratumClientBeansRegistrar.class})
public final class RestClientAutoConfiguration {

  /** Spring Boot's Micrometer set-up, after which the service's registry exists. */
  static final String METRICS_AUTO_CONFIGURATION =
      "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration";

  /** Spring Boot's composite registry, made after every registry it gathers. */
  static final String COMPOSITE_REGISTRY_AUTO_CONFIGURATION =
      "org.springframework.boot.micrometer.metrics.autoconfigure."
          + "CompositeMeterRegistryAutoConfiguration";

  /** Micrometer's registry, which the clients' meters are made in. */
  static final String METER_REGISTRY = "io.micrometer.core.instrument.MeterRegistry";

  /**
   * Stops startup with {@code SX-CORE-1004} when the service declares a settings customizer as a
   * bean, which would be silently ignored, and with {@code SX-HTTPC-1006} when a client's kind of
   * auth needs a Spring Security library the service does not have.
   *
   * @param beanFactory the service's beans
   * @param environment the service's environment, from which the settings are resolved
   */
  public RestClientAutoConfiguration(
      ConfigurableListableBeanFactory beanFactory, Environment environment) {
    StratumCustomizers.rejectBeans(beanFactory);
    HttpClientProperties.resolve(environment)
        .clients()
        .forEach(
            (client, settings) ->
                Optional.ofNullable(settings.auth())
                    .ifPresent(
                        auth ->
                            StratumClientChecks.checkAuthLibrary(
                                client, auth, beanFactory.getBeanClassLoader())));
  }

  /**
   * Creates the connection pools of Stratum's clients, which close with the application.
   *
   * @param environment the service's environment, from which the settings are resolved
   * @param sslBundles Spring Boot's SSL bundles, for clients that name one
   * @param meters the clients' meters, present with Micrometer and a registry
   * @return the pools
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public ClientConnectionPools stratumClientConnectionPools(
      Environment environment,
      ObjectProvider<SslBundles> sslBundles,
      ObjectProvider<ClientMeters> meters) {
    return new ClientConnectionPools(
        HttpClientProperties.resolve(environment).defaults(), sslBundles, meters);
  }

  /**
   * Creates Stratum's set-up of a client, shared by the client interfaces, {@link
   * StratumHttpClients} and each client's {@code RestClient} and {@code RestTemplate} beans.
   *
   * @param environment the service's environment, from which the settings are resolved
   * @param pools the connection pools
   * @param customizers the service's changes to the clients' builders
   * @param tokenInitializers the senders of Spring Security's tokens, present with their libraries
   * @param mockAnswers the answers of the clients set to {@code mock: true}
   * @param resilience the retries and circuit breakers the beans' calls run through
   * @param interceptors the interceptors of each client
   * @return the set-up
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public StratumClientBuilders stratumClientBuilders(
      Environment environment,
      ClientConnectionPools pools,
      ObjectProvider<StratumClientCustomizer> customizers,
      ObjectProvider<TokenInitializers> tokenInitializers,
      MockAnswers mockAnswers,
      ClientResilience resilience,
      ClientInterceptors interceptors) {
    return new StratumClientBuilders(
        HttpClientProperties.resolve(environment),
        pools,
        environment,
        customizers,
        tokenInitializers,
        mockAnswers,
        resilience,
        interceptors);
  }

  /**
   * Creates the set-up of each Stratum client's group.
   *
   * @param environment the service's environment, from which the settings are resolved
   * @param builders Stratum's set-up of a client's builder
   * @param decorators what Stratum puts around each client's calls, such as the retries
   * @param mockAnswers the answers and calls of the clients set to {@code mock: true}
   * @param interceptors the interceptors of each client
   * @return the set-up
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public StratumClientGroups stratumClientGroups(
      Environment environment,
      StratumClientBuilders builders,
      ObjectProvider<ClientExchangeDecorator> decorators,
      MockAnswers mockAnswers,
      ClientInterceptors interceptors) {
    return new StratumClientGroups(
        HttpClientProperties.resolve(environment), builders, decorators, mockAnswers, interceptors);
  }

  /**
   * Creates the answers and recorded calls of the clients set to {@code mock: true}.
   *
   * @param jsonMappers the application's JSON mapper, which writes scripted answers
   * @return the answers
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public MockAnswers stratumMockAnswers(ObjectProvider<JsonMapper> jsonMappers) {
    return new MockAnswers(jsonMappers);
  }

  /**
   * Creates the mocks tests script, and the startup warning of each mocked client.
   *
   * @param environment the service's environment, from which the settings are resolved
   * @param mockAnswers the answers of the mocked clients
   * @return the mocks
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public MockClients stratumMockClients(Environment environment, MockAnswers mockAnswers) {
    return new DefaultMockClients(HttpClientProperties.resolve(environment), mockAnswers);
  }

  /**
   * Creates the retries and circuit breaker of each Stratum client.
   *
   * @param environment the service's environment, from which the settings are resolved
   * @param customizers the service's changes to the clients' retry and breaker rules
   * @param tokenResend the resend after a 401 of a client with a service token, if present
   * @param callLog the log line of each call
   * @param meters the clients' meters, present with Micrometer and a registry
   * @return the retries and breakers
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public ClientResilience stratumClientResilience(
      Environment environment,
      ObjectProvider<StratumClientCustomizer> customizers,
      ObjectProvider<ServiceTokenResend> tokenResend,
      ClientCallLog callLog,
      ObjectProvider<ClientMeters> meters) {
    return new ClientResilience(
        HttpClientProperties.resolve(environment),
        customizers,
        Clock.systemUTC(),
        tokenResend,
        new ClientCallReports(callLog, meters));
  }

  /**
   * Creates the log line of each call of a Stratum client, which carries the called URL only while
   * the logging starter's redaction runs.
   *
   * @param environment the service's environment, from which the settings are resolved
   * @param redaction the marker that the logging starter's redaction runs, present only then
   * @return the call log
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public ClientCallLog stratumClientCallLog(
      Environment environment, ObjectProvider<LogRedaction> redaction) {
    return new ClientCallLog(HttpClientProperties.resolve(environment), redaction.getIfAvailable());
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

  /**
   * Creates the clients' meters when Micrometer is on the classpath and the service has a registry,
   * as with Spring Boot's Actuator or the observability starter. A nested class, so Micrometer's
   * types are only loaded then.
   */
  @StratumInternal
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule(HttpClientConstants.MODULE)
  @ConditionalOnClass(name = METER_REGISTRY)
  @ConditionalOnBean(type = METER_REGISTRY)
  public static class ClientMetersConfiguration {

    /**
     * Creates the meters of each call, retry, breaker state and pool of Stratum's clients.
     *
     * @param registry the service's registry
     * @return the meters
     */
    @Bean
    @Fallback
    @ConditionalOnMissingBean
    public ClientMeters stratumClientMeters(MeterRegistry registry) {
      return new MicrometerClientMeters(registry);
    }
  }
}
