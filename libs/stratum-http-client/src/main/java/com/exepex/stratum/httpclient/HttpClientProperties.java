package com.exepex.stratum.httpclient;

import com.exepex.stratum.core.properties.StratumCustomizers;
import jakarta.validation.constraints.AssertTrue;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.env.Environment;

/**
 * The settings of the HTTP client module, bound to {@code stratum.http-client.*} and then changed
 * by the service's {@link HttpClientPropertiesCustomizer} classes, which win.
 *
 * @param defaults The settings every client shares; a client's own setting wins over them.
 * @param clients The settings of each client, by the name in its {@code @StratumClient}, such as
 *     inventory.
 */
@Builder(toBuilder = true)
@ConfigurationProperties(prefix = HttpClientConstants.PREFIX)
public record HttpClientProperties(
    @NestedConfigurationProperty @DefaultValue ClientSettings defaults,
    Map<String, ClientSettings> clients) {

  /** Fills the defaults left unset with Stratum's, and keeps the clients a fixed map. */
  public HttpClientProperties {
    defaults =
        Optional.ofNullable(defaults)
            .orElseGet(() -> ClientSettings.builder().build())
            .orElse(ClientSettings.STRATUM_DEFAULTS);
    clients = clients == null ? Map.of() : Map.copyOf(clients);
  }

  /**
   * Returns the settings the module uses: {@code stratum.http-client.*} with the defaults for
   * anything unset, changed by every {@link HttpClientPropertiesCustomizer} in {@code
   * META-INF/spring.factories}, then validated.
   *
   * @param environment the application's environment
   * @return the settings
   * @throws com.exepex.stratum.core.error.StratumException {@code SX-CORE-1003} if a setting breaks
   *     its rules
   */
  public static HttpClientProperties resolve(Environment environment) {
    return StratumCustomizers.resolveSettings(
        environment,
        HttpClientConstants.PREFIX,
        HttpClientProperties.class,
        HttpClientPropertiesCustomizer.class);
  }

  /**
   * Returns the settings one client uses: its own, then the shared defaults, then Stratum's. Its
   * {@code url} stays unset when the client sets none.
   *
   * @param client the client's name
   * @return the client's settings
   */
  public ClientSettings clientSettings(String client) {
    return Optional.ofNullable(clients.get(client))
        .map(own -> own.orElse(defaults))
        .orElse(defaults);
  }

  /**
   * Returns whether every duration, shared or of one client, is above 0. Validation reports one
   * that is not as {@code stratum.http-client.durations-positive breaks @AssertTrue}.
   *
   * @return whether every duration is above 0
   */
  @AssertTrue
  public boolean isDurationsPositive() {
    return allSettings()
        .flatMap(ClientSettings::durations)
        .allMatch(duration -> duration.compareTo(Duration.ZERO) > 0);
  }

  /**
   * Returns whether every {@code max-connections}, shared or of one client, is from 1 to 10000.
   * Validation reports one that is not as {@code stratum.http-client.max-connections-in-range
   * breaks @AssertTrue}.
   *
   * @return whether every pool size is in range
   */
  @AssertTrue
  public boolean isMaxConnectionsInRange() {
    return allSettings()
        .map(ClientSettings::maxConnections)
        .filter(Objects::nonNull)
        .allMatch(size -> size >= 1 && size <= HttpClientConstants.MAX_MAX_CONNECTIONS);
  }

  /**
   * Returns whether every client's name is lowercase letters, digits and hyphens, from a letter.
   * Validation reports one that is not as {@code stratum.http-client.client-names-valid
   * breaks @AssertTrue}, without the name.
   *
   * @return whether every name is valid
   */
  @AssertTrue
  public boolean isClientNamesValid() {
    return clients.keySet().stream()
        .allMatch(name -> HttpClientConstants.CLIENT_NAME.matcher(name).matches());
  }

  /**
   * Returns whether the shared defaults leave {@code url} unset, since each client calls its own
   * service. Validation reports one that is set as {@code stratum.http-client.defaults-without-url
   * breaks @AssertTrue}.
   *
   * @return whether the defaults have no address
   */
  @AssertTrue
  public boolean isDefaultsWithoutUrl() {
    return defaults.url() == null;
  }

  /** Returns the shared defaults and the settings of each client. */
  private Stream<ClientSettings> allSettings() {
    return Stream.concat(Stream.of(defaults), clients.values().stream());
  }
}
