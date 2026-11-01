package com.exepex.stratum.webclient;

import java.util.Objects;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.http.client.autoconfigure.HttpClientSettingsProperties;
import org.springframework.boot.http.client.autoconfigure.HttpClientSettingsPropertyMapper;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientProperties;
import org.springframework.boot.http.client.reactive.ClientHttpConnectorBuilder;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientHttpServiceGroupConfigurer;
import org.springframework.web.service.registry.HttpServiceGroup;

/**
 * Makes each {@code WebClient} HTTP interface group use its own {@code
 * spring.http.serviceclient.<name>.*} timeouts and SSL settings, as blocking groups do. Spring Boot
 * 4.1.2 builds the group's connector from them, but then its {@code WebClientCustomizer} for the
 * shared connector replaces it, so the group gets only the settings for every client. This runs
 * after Spring Boot's customizers and builds the group's connector again, the same way Spring Boot
 * does.
 */
@RequiredArgsConstructor
public class WebClientGroupSettings implements WebClientHttpServiceGroupConfigurer, Ordered {

  /** Runs after Spring Boot's configurer that applies the {@code WebClientCustomizer} beans. */
  private static final int AFTER_WEB_CLIENT_CUSTOMIZERS = 1;

  /** Every group's {@code spring.http.serviceclient.<name>.*} keys. */
  private final HttpServiceClientProperties groupProperties;

  /** Turns a group's keys into its settings, with the settings for every client underneath. */
  private final HttpClientSettingsPropertyMapper settingsMapper;

  /** Spring Boot's builder of connectors, with every customizer applied, Stratum's pool too. */
  private final ClientHttpConnectorBuilder<?> connectorBuilder;

  /**
   * Gives each group that has keys of its own a connector built from them.
   *
   * @param groups the HTTP interface groups that use {@code WebClient}
   */
  @Override
  public void configureGroups(Groups<WebClient.Builder> groups) {
    groups.forEachClient(this::useOwnSettings);
  }

  /**
   * Returns the order after Spring Boot's configurers.
   *
   * @return the order
   */
  @Override
  public int getOrder() {
    return AFTER_WEB_CLIENT_CUSTOMIZERS;
  }

  /**
   * Builds the group's connector from its own keys, if it sets any of the connection's own. A group
   * with only an address or headers keeps the connector it has, a service's own included.
   */
  private void useOwnSettings(HttpServiceGroup group, WebClient.Builder webClientBuilder) {
    var ownProperties = groupProperties.get(group.name());
    if (ownProperties != null && setsConnectionKeys(ownProperties)) {
      webClientBuilder.clientConnector(connectorBuilder.build(settingsMapper.map(ownProperties)));
    }
  }

  /** Returns whether a group sets a key that its connector is built from. */
  private boolean setsConnectionKeys(HttpClientSettingsProperties ownProperties) {
    return Stream.of(
            ownProperties.getConnectTimeout(),
            ownProperties.getReadTimeout(),
            ownProperties.getRedirects(),
            ownProperties.getCookieHandling(),
            ownProperties.getSsl().getBundle())
        .anyMatch(Objects::nonNull);
  }
}
