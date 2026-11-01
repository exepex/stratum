package com.exepex.stratum.webclient.autoconfigure;

import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.core.properties.StratumCustomizers;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer;
import com.exepex.stratum.webclient.WebClientConnectionPool;
import com.exepex.stratum.webclient.WebClientGroupSettings;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.autoconfigure.HttpClientSettingsPropertyMapper;
import org.springframework.boot.http.client.autoconfigure.reactive.ReactiveHttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientProperties;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientPropertiesAutoConfiguration;
import org.springframework.boot.http.client.reactive.ClientHttpConnectorBuilder;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Fallback;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import reactor.netty.http.client.HttpClient;

/**
 * Gives the reactive HTTP clients a bounded connection pool, and each {@code WebClient} HTTP
 * interface group its own timeouts, when {@code stratum.http-client.enabled=true} and Reactor Netty
 * is present. Ordered after Spring Boot's reactive HTTP client auto-configurations, whose beans the
 * group settings use.
 */
@AutoConfiguration(
    after = {
      ReactiveHttpClientAutoConfiguration.class,
      HttpServiceClientPropertiesAutoConfiguration.class
    })
@AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
@ConditionalOnStratumModule(HttpClientConstants.MODULE)
@ConditionalOnClass(HttpClient.class)
public final class WebClientConnectionPoolAutoConfiguration {

  /**
   * Stops startup with {@code SX-CORE-1004} when the service declares an HTTP client customizer as
   * a bean, which would be silently ignored.
   *
   * @param beanFactory the service's beans
   */
  public WebClientConnectionPoolAutoConfiguration(ListableBeanFactory beanFactory) {
    StratumCustomizers.rejectBeans(beanFactory);
  }

  /**
   * Gives every client Spring Boot builds on Reactor Netty the bounded pool, which reports its
   * connections when Micrometer is present. Its settings come from {@link
   * HttpClientProperties#resolve}, so the service's {@link HttpClientPropertiesCustomizer} classes
   * win.
   *
   * @param environment the application's environment
   * @return the pool
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public WebClientConnectionPool webClientConnectionPool(Environment environment) {
    return new WebClientConnectionPool(
        HttpClientProperties.resolve(environment).pool(),
        ClassUtils.isPresent(HttpClientConstants.METER_REGISTRY, null));
  }

  /**
   * Gives each {@code WebClient} HTTP interface group its own {@code
   * spring.http.serviceclient.<name>.*} timeouts and SSL settings, which Spring Boot 4.1.2 loses.
   *
   * @param groupProperties every group's keys
   * @param sslBundles the service's SSL bundles, if any
   * @param connectorBuilder Spring Boot's builder of connectors
   * @param settingsForEveryClient the settings for every client
   * @return the group settings
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  @ConditionalOnBean({HttpServiceClientProperties.class, ClientHttpConnectorBuilder.class})
  public WebClientGroupSettings webClientGroupSettings(
      HttpServiceClientProperties groupProperties,
      ObjectProvider<SslBundles> sslBundles,
      ClientHttpConnectorBuilder<?> connectorBuilder,
      ObjectProvider<HttpClientSettings> settingsForEveryClient) {
    return new WebClientGroupSettings(
        groupProperties,
        new HttpClientSettingsPropertyMapper(
            sslBundles.getIfAvailable(), settingsForEveryClient.getIfAvailable()),
        connectorBuilder);
  }
}
