package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.httpclient.HttpClientProperties;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

/**
 * Sets up the builder of one Stratum client: its address, connection pool and credential from
 * {@code stratum.http-client.*}, then the service's {@link StratumClientCustomizer} beans. The one
 * place both the client interfaces and {@link StratumHttpClients#restClient} get their set-up from.
 */
@StratumInternal
@RequiredArgsConstructor
public class StratumClientBuilders {

  /** The settings of every client. */
  private final HttpClientProperties properties;

  /** The pools the clients use. */
  private final ClientConnectionPools pools;

  /** The application's environment, checked for Spring Boot's group settings. */
  private final Environment environment;

  /** The service's changes to the clients' builders. */
  private final ObjectProvider<StratumClientCustomizer> customizers;

  /**
   * Sets one client's address, pool and credential, after the checks, then applies the customizers.
   *
   * @param client the client's name
   * @param builder the client's builder
   * @throws com.exepex.stratum.core.error.StratumException {@code SX-HTTPC-1001} if a Spring Boot
   *     group setting is set for the client, or {@code SX-HTTPC-1004} if it has no address
   */
  public void configure(String client, RestClient.Builder builder) {
    StratumClientChecks.checkNoBootSettings(environment, client);
    var settings = properties.clientSettings(client);
    builder
        .baseUrl(StratumClientChecks.requireUrl(client, settings))
        .requestFactory(pools.requestFactory(client, settings));
    Optional.ofNullable(settings.auth())
        .flatMap(ClientAuthInitializers::initializerFor)
        .ifPresent(builder::requestInitializer);
    customizers.orderedStream().forEach(customizer -> customizer.customize(client, builder));
  }
}
