package com.exepex.stratum.restclient;

import com.exepex.stratum.httpclient.HttpClientConstants;
import org.apache.hc.client5.http.HttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.springframework.boot.http.client.HttpComponentsClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.autoconfigure.ClientHttpRequestFactoryBuilderCustomizer;
import org.springframework.core.Ordered;

/**
 * Stops Apache HttpClient 5 from sending a blocking call again after a {@code 429} or {@code 503}
 * answer. Apache does that by default whatever the method, so a {@code POST} could reach the
 * downstream service twice. Apache still sends an idempotent call, such as a {@code GET}, again on
 * a dropped connection. A service's own customizer that declares no order runs later, so its retry
 * strategy wins.
 */
public class RestClientRetries
    implements ClientHttpRequestFactoryBuilderCustomizer<
            HttpComponentsClientHttpRequestFactoryBuilder>,
        Ordered {

  /** Apache's retries of idempotent calls on a dropped connection, and none on an answer. */
  private final HttpRequestRetryStrategy noRetryOnAnswer =
      new DefaultHttpRequestRetryStrategy() {
        @Override
        public boolean retryRequest(HttpResponse response, int execCount, HttpContext context) {
          return false;
        }
      };

  /**
   * Returns the builder with Apache's retries on an answer turned off.
   *
   * @param builder Spring Boot's builder of Apache HttpClient 5 request factories
   * @return the builder that makes clients without those retries
   */
  @Override
  public HttpComponentsClientHttpRequestFactoryBuilder customize(
      HttpComponentsClientHttpRequestFactoryBuilder builder) {
    return builder.withHttpClientCustomizer(client -> client.setRetryStrategy(noRetryOnAnswer));
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
}
