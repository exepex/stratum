package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.httpclient.ClientSettings;
import com.exepex.stratum.httpclient.HttpClientProperties;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.web.client.RestClient;

/**
 * Builds each client's {@code RestClient} once, as Spring Boot builds a client group: Spring Boot's
 * {@link RestClientCustomizer} beans first, such as the one that records each call, then Stratum's
 * set-up.
 */
@StratumInternal
@RequiredArgsConstructor
public class DefaultStratumHttpClients implements StratumHttpClients {

  /** The settings of every client. */
  private final HttpClientProperties properties;

  /** Stratum's set-up of a client's builder. */
  private final StratumClientBuilders builders;

  /** Spring Boot's and the service's changes to every {@code RestClient} builder. */
  private final ObjectProvider<RestClientCustomizer> restClientCustomizers;

  /** The clients built so far, by name; at most one per configured name. */
  private final Map<String, RestClient> restClients = new ConcurrentHashMap<>();

  /**
   * Returns the client's {@code RestClient}, built on first use.
   *
   * @param client the client's name
   * @return the client
   */
  @Override
  public RestClient restClient(String client) {
    return restClients.computeIfAbsent(client, this::build);
  }

  /**
   * Returns the client's settings merged with the shared defaults.
   *
   * @param client the client's name
   * @return the settings
   */
  @Override
  public ClientSettings settings(String client) {
    return properties.clientSettings(client);
  }

  /** Builds a client's {@code RestClient}: Spring Boot's customizers, then Stratum's set-up. */
  private RestClient build(String client) {
    var builder = RestClient.builder();
    restClientCustomizers.orderedStream().forEach(customizer -> customizer.customize(builder));
    builders.configure(client, builder);
    return builder.build();
  }
}
