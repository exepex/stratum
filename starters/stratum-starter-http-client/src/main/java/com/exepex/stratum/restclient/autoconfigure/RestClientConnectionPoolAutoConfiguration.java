package com.exepex.stratum.restclient.autoconfigure;

import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.core.properties.StratumCustomizers;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer;
import com.exepex.stratum.restclient.RestClientConnectionPool;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Fallback;
import org.springframework.core.env.Environment;

/**
 * Bounds the connection pool of the blocking HTTP clients, when {@code
 * stratum.http-client.enabled=true} and Apache HttpClient 5 is present.
 */
@AutoConfiguration
@AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
@ConditionalOnStratumModule(HttpClientConstants.MODULE)
@ConditionalOnClass(HttpClientBuilder.class)
public final class RestClientConnectionPoolAutoConfiguration {

  /**
   * Stops startup with {@code SX-CORE-1004} when the service declares an HTTP client customizer as
   * a bean, which would be silently ignored.
   *
   * @param beanFactory the service's beans
   */
  public RestClientConnectionPoolAutoConfiguration(ListableBeanFactory beanFactory) {
    StratumCustomizers.rejectBeans(beanFactory);
  }

  /**
   * Bounds the pool of every client Spring Boot builds on Apache HttpClient 5. Its settings come
   * from {@link HttpClientProperties#resolve}, so the service's {@link
   * HttpClientPropertiesCustomizer} classes win.
   *
   * @param environment the application's environment
   * @return the pool's settings for Spring Boot's builder
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public RestClientConnectionPool restClientConnectionPool(Environment environment) {
    return new RestClientConnectionPool(HttpClientProperties.resolve(environment).pool());
  }
}
