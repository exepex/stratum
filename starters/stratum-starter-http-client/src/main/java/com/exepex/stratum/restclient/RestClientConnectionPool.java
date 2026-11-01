package com.exepex.stratum.restclient;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientProperties;
import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.http.client.HttpComponentsClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.autoconfigure.ClientHttpRequestFactoryBuilderCustomizer;
import org.springframework.core.Ordered;

/**
 * Bounds the connections of every blocking client that Spring Boot builds on Apache HttpClient 5:
 * each HTTP interface group, and each {@code RestClient} built from Spring Boot's builder, gets a
 * pool of at most {@code stratum.http-client.pool.max-connections}, and a call that finds them all
 * busy fails after {@code stratum.http-client.pool.max-wait}. A service's own customizer that
 * declares no order runs later, so its pool settings win.
 */
@RequiredArgsConstructor
public class RestClientConnectionPool
    implements ClientHttpRequestFactoryBuilderCustomizer<
            HttpComponentsClientHttpRequestFactoryBuilder>,
        Ordered {

  /** The pool's size and wait. */
  private final HttpClientProperties.PoolSettings poolSettings;

  /**
   * Returns the builder with the pool's size and wait set.
   *
   * @param builder Spring Boot's builder of Apache HttpClient 5 request factories
   * @return the builder that makes bounded pools
   */
  @Override
  public HttpComponentsClientHttpRequestFactoryBuilder customize(
      HttpComponentsClientHttpRequestFactoryBuilder builder) {
    return builder
        .withConnectionManagerCustomizer(this::boundConnections)
        .withDefaultRequestConfigCustomizer(this::boundWait);
  }

  /**
   * Returns the order after Spring Boot's customizers and before a service's own.
   *
   * @return the order
   */
  @Override
  public int getOrder() {
    return HttpClientConstants.CUSTOMIZER_ORDER;
  }

  /** Allows the pool's size in all and to any one downstream service. */
  private void boundConnections(PoolingHttpClientConnectionManagerBuilder connectionManager) {
    connectionManager
        .setMaxConnTotal(poolSettings.maxConnections())
        .setMaxConnPerRoute(poolSettings.maxConnections());
  }

  /** Fails a call that waits longer than the pool's wait for a free connection. */
  private void boundWait(RequestConfig.Builder requestConfig) {
    requestConfig.setConnectionRequestTimeout(Timeout.of(poolSettings.maxWait()));
  }
}
