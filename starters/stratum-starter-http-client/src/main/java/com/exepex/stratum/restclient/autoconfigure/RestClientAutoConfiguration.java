package com.exepex.stratum.restclient.autoconfigure;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.core.properties.StratumCustomizers;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.restclient.ClientConnectionPools;
import com.exepex.stratum.restclient.StratumClientCustomizer;
import com.exepex.stratum.restclient.StratumClientGroups;
import com.exepex.stratum.restclient.StratumClientRegistrar;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.restclient.autoconfigure.service.HttpServiceClientAutoConfiguration;
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
   * @return the pools
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public ClientConnectionPools stratumClientConnectionPools(Environment environment) {
    return new ClientConnectionPools(HttpClientProperties.resolve(environment).defaults());
  }

  /**
   * Creates the set-up of each Stratum client's group.
   *
   * @param environment the service's environment, from which the settings are resolved
   * @param pools the connection pools
   * @param customizers the service's changes to the clients' builders
   * @return the set-up
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public StratumClientGroups stratumClientGroups(
      Environment environment,
      ClientConnectionPools pools,
      ObjectProvider<StratumClientCustomizer> customizers) {
    return new StratumClientGroups(
        HttpClientProperties.resolve(environment), pools, environment, customizers);
  }
}
