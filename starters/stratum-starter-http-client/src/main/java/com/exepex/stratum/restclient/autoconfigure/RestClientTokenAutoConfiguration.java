package com.exepex.stratum.restclient.autoconfigure;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.restclient.ClientConnectionPools;
import com.exepex.stratum.restclient.ClientWarnings;
import com.exepex.stratum.restclient.ServiceTokenInitializers;
import com.exepex.stratum.restclient.ServiceTokenManagers;
import com.exepex.stratum.restclient.ServiceTokenResend;
import com.exepex.stratum.restclient.SingleFetchTokenManager;
import com.exepex.stratum.restclient.TokenRelayInitializers;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.restclient.autoconfigure.service.HttpServiceClientAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * Sends Spring Security's tokens on the calls of Stratum's clients: the service's own token for
 * {@code auth.client-credentials}, when Spring Security's OAuth2 client is present, and the
 * caller's verified token for {@code auth.token-relay}, when its resource server is. Each part
 * loads only with its library. Runs after Spring Boot's OAuth2 client set-up, whose registrations
 * and token store it uses.
 */
@StratumInternal
@AutoConfiguration(
    after = RestClientAutoConfiguration.class,
    before = HttpServiceClientAutoConfiguration.class,
    afterName =
        "org.springframework.boot.security.oauth2.client.autoconfigure"
            + ".OAuth2ClientAutoConfiguration")
@AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
@ConditionalOnStratumModule(HttpClientConstants.MODULE)
public final class RestClientTokenAutoConfiguration {

  /** The service's own token, with Spring Security's OAuth2 client. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule(HttpClientConstants.MODULE)
  @ConditionalOnClass(ClientRegistrationRepository.class)
  static final class ServiceTokens {

    /**
     * Creates the initializers of the service's token, after checking each client's registration.
     *
     * @param environment the service's environment, from which the settings are resolved
     * @param registrations Spring Boot's registrations, absent when none is configured
     * @param tokens the tokens of the registrations, absent without registrations
     * @return the initializers
     */
    @Bean
    @Fallback
    @ConditionalOnMissingBean
    ServiceTokenInitializers stratumServiceTokenInitializers(
        Environment environment,
        ObjectProvider<ClientRegistrationRepository> registrations,
        ObjectProvider<SingleFetchTokenManager> tokens) {
      return new ServiceTokenInitializers(
          HttpClientProperties.resolve(environment), registrations.getIfAvailable(), tokens);
    }

    /**
     * Creates the tokens of the registrations, each fetched once at a time.
     *
     * @param environment the service's environment, from which the settings are resolved
     * @param pools the pools, which give each client a token pool
     * @param registrations Spring Boot's registrations
     * @param store Spring Boot's store of fetched tokens
     * @return the tokens
     */
    @Bean
    @Fallback
    @ConditionalOnMissingBean
    @ConditionalOnBean({ClientRegistrationRepository.class, OAuth2AuthorizedClientService.class})
    SingleFetchTokenManager stratumServiceTokens(
        Environment environment,
        ClientConnectionPools pools,
        ClientRegistrationRepository registrations,
        OAuth2AuthorizedClientService store) {
      var properties = HttpClientProperties.resolve(environment);
      var clock = Clock.systemUTC();
      return new SingleFetchTokenManager(
          new ServiceTokenManagers(properties, pools, registrations, store),
          store,
          clock,
          new ClientWarnings(clock));
    }

    /**
     * Creates the resend after a 401 of a client with a service token.
     *
     * @param environment the service's environment, from which the settings are resolved
     * @param tokens the tokens of the registrations
     * @return the resend
     */
    @Bean
    @Fallback
    @ConditionalOnMissingBean
    @ConditionalOnBean(SingleFetchTokenManager.class)
    ServiceTokenResend stratumServiceTokenResend(
        Environment environment, SingleFetchTokenManager tokens) {
      return new ServiceTokenResend(
          HttpClientProperties.resolve(environment), tokens, new ClientWarnings(Clock.systemUTC()));
    }
  }

  /** The caller's verified token, with Spring Security's resource server. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule(HttpClientConstants.MODULE)
  @ConditionalOnClass(
      name =
          "org.springframework.security.oauth2.server.resource.authentication"
              + ".AbstractOAuth2TokenAuthenticationToken")
  static final class CallerTokens {

    /**
     * Creates the initializers that relay the caller's token.
     *
     * @return the initializers
     */
    @Bean
    @Fallback
    @ConditionalOnMissingBean
    TokenRelayInitializers stratumTokenRelayInitializers() {
      return new TokenRelayInitializers();
    }
  }
}
