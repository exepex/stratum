package com.exepex.stratum.restclient;

import com.exepex.stratum.httpclient.HttpClientProperties;
import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.http.client.HttpComponentsClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.autoconfigure.ClientHttpRequestFactoryBuilderCustomizer;

/**
 * Bounds the connections of every blocking client that Spring Boot builds on Apache HttpClient 5,
 * the plain {@code RestClient.Builder} and each HTTP interface group alike: at most {@code
 * stratum.http-client.pool.max-connections} to each downstream service, and a call that finds them
 * all busy fails after {@code stratum.http-client.pool.max-wait}.
 */
@RequiredArgsConstructor
public class RestClientConnectionPool
    implements ClientHttpRequestFactoryBuilderCustomizer<
        HttpComponentsClientHttpRequestFactoryBuilder> {

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
