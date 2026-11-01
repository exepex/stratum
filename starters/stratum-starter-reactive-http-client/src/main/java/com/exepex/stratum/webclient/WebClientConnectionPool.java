package com.exepex.stratum.webclient;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.http.client.autoconfigure.reactive.ClientHttpConnectorBuilderCustomizer;
import org.springframework.boot.http.client.reactive.ReactorClientHttpConnectorBuilder;
import org.springframework.core.Ordered;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * Gives every reactive client that Spring Boot builds on Reactor Netty, the plain {@code
 * WebClient.Builder} and each HTTP interface group alike, one bounded connection pool: at most
 * {@code stratum.http-client.pool.max-connections} to each downstream service, and a call that
 * finds them all busy fails after {@code stratum.http-client.pool.max-wait}. The clients run on
 * Reactor Netty's shared event loops, and the pool is closed when the application stops. A
 * service's own customizer that declares no order runs later, so its pool wins.
 */
public class WebClientConnectionPool
    implements ClientHttpConnectorBuilderCustomizer<ReactorClientHttpConnectorBuilder>,
        DisposableBean,
        Ordered {

  /** The bounded pool every client shares. */
  private final ConnectionProvider connectionProvider;

  /**
   * Makes the pool.
   *
   * @param poolSettings the pool's size and wait
   * @param recordPoolMetrics whether the pool reports its connections to Micrometer
   */
  public WebClientConnectionPool(
      HttpClientProperties.PoolSettings poolSettings, boolean recordPoolMetrics) {
    this.connectionProvider =
        ConnectionProvider.builder(HttpClientConstants.POOL_NAME)
            .maxConnections(poolSettings.maxConnections())
            .pendingAcquireTimeout(poolSettings.maxWait())
            .metrics(recordPoolMetrics)
            .build();
  }

  /**
   * Returns the builder with every client made on the bounded pool.
   *
   * @param builder Spring Boot's builder of Reactor Netty connectors
   * @return the builder that makes clients on the pool
   */
  @Override
  public ReactorClientHttpConnectorBuilder customize(ReactorClientHttpConnectorBuilder builder) {
    return builder.withHttpClientFactory(() -> HttpClient.create(connectionProvider));
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

  /** Closes the pool and its connections. */
  @Override
  public void destroy() {
    connectionProvider.dispose();
  }
}
