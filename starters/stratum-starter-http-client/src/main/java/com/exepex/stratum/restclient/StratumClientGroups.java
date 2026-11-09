package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.httpclient.StratumClient;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;
import org.springframework.web.service.registry.HttpServiceGroup;

/**
 * Sets up the HTTP service group of each Stratum client: its address and its connection pool from
 * {@code stratum.http-client.*}, then the service's {@link StratumClientCustomizer} beans. Groups
 * without a {@code @StratumClient} interface, such as a service's own, are left as Spring Boot set
 * them up. Runs at startup, when Spring builds the clients.
 */
@StratumInternal
@RequiredArgsConstructor
public class StratumClientGroups implements RestClientHttpServiceGroupConfigurer {

  /**
   * After Spring Boot's own group set-up, at {@code Ordered.HIGHEST_PRECEDENCE + 10} and 0, which
   * gives every group Spring Boot's request factory, so that Stratum's address and pool win.
   */
  private static final int ORDER = 1;

  /** The settings of every client. */
  private final HttpClientProperties properties;

  /** The pools the clients use. */
  private final ClientConnectionPools pools;

  /** The application's environment, checked for Spring Boot's group settings. */
  private final Environment environment;

  /** The service's changes to the clients' builders. */
  private final ObjectProvider<StratumClientCustomizer> customizers;

  /**
   * Sets up each Stratum client's group.
   *
   * @param groups every HTTP service group of the application
   */
  @Override
  public void configureGroups(Groups<RestClient.Builder> groups) {
    groups.filter(this::isStratumClient).forEachClient(this::configureClient);
  }

  /**
   * Returns the order after Spring Boot's group set-up.
   *
   * @return the order
   */
  @Override
  public int getOrder() {
    return ORDER;
  }

  /** Sets one client's address and pool, after the checks, then applies the customizers. */
  private void configureClient(HttpServiceGroup group, RestClient.Builder builder) {
    var client = group.name();
    StratumClientChecks.checkNoBootSettings(environment, client);
    var settings = properties.clientSettings(client);
    builder
        .baseUrl(StratumClientChecks.requireUrl(client, settings))
        .requestFactory(pools.requestFactory(client, settings));
    customizers.orderedStream().forEach(customizer -> customizer.customize(client, builder));
  }

  /** Returns whether the group is a Stratum client's. */
  private boolean isStratumClient(HttpServiceGroup group) {
    return group.httpServiceTypes().stream()
        .anyMatch(type -> type.isAnnotationPresent(StratumClient.class));
  }
}
