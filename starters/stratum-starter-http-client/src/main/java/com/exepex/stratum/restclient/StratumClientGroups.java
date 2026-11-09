package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.httpclient.ClientSettings;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.httpclient.StratumClient;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;
import org.springframework.web.service.registry.HttpServiceGroup;

/**
 * Sets up the HTTP service group of each Stratum client through {@link StratumClientBuilders}.
 * Groups that are not a Stratum client's, such as a service's own, are left as Spring Boot set them
 * up. Runs at startup, when Spring builds the clients.
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

  /** Stratum's set-up of a client's builder. */
  private final StratumClientBuilders builders;

  /**
   * Sets up each Stratum client's group.
   *
   * @param groups every HTTP service group of the application
   */
  @Override
  public void configureGroups(Groups<RestClient.Builder> groups) {
    groups
        .filter(this::isStratumClient)
        .forEachClient((group, builder) -> builders.configure(group.name(), builder));
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

  /**
   * Returns whether a group is a Stratum client's: one of its interfaces is marked {@code
   * StratumClient}, or is named in the {@code interfaces} setting of the client the group is named
   * after. The name alone never counts, so a service's own group of the same name stays its own.
   *
   * @param group an HTTP service group
   * @return whether Stratum sets the group up
   */
  public boolean isStratumClient(HttpServiceGroup group) {
    var named =
        Optional.ofNullable(properties.clients().get(group.name()))
            .map(ClientSettings::interfaces)
            .orElse(List.of());
    return group.httpServiceTypes().stream()
        .anyMatch(
            type ->
                type.isAnnotationPresent(StratumClient.class) || named.contains(type.getName()));
  }
}
