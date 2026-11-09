package com.exepex.stratum.httpclient;

import java.time.Duration;
import java.util.Objects;
import java.util.stream.Stream;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * The settings of one client, or of every client under {@code defaults}. A setting a client leaves
 * unset comes from {@code defaults}, and one {@code defaults} leaves unset from Stratum. The
 * descriptions of the components are what IDEs show, so they are plain sentences.
 *
 * @param url The address of the other service, such as http://inventory:8080. Required for each
 *     client; not allowed under defaults.
 * @param connectTimeout The time allowed to open a connection, such as 2s. Defaults to 2s.
 * @param timeout The time allowed for the answer to one call, such as 5s or 20s for a slow report.
 *     Defaults to 5s.
 * @param maxConnections The most connections the client keeps open at once, from 1 to 10000. A call
 *     that finds them all busy waits for one. Defaults to 50.
 * @param maxWait How long a call waits for a free connection before it fails, such as 2s or 500ms.
 *     Defaults to 2s.
 * @param idleTimeout How long a connection may stay unused before it is closed, such as 30s.
 *     Defaults to 30s.
 * @param connectionLifetime How old a connection may get before it is closed instead of used again,
 *     so that calls follow changes of address. Defaults to 5m.
 * @param sharedPool Whether the client uses the one pool shared by every client that sets it,
 *     instead of its own. That pool takes its size, connect timeout, idle timeout and lifetime from
 *     defaults. Defaults to false.
 */
@Builder(toBuilder = true)
public record ClientSettings(
    @Nullable String url,
    @Nullable Duration connectTimeout,
    @Nullable Duration timeout,
    @Nullable Integer maxConnections,
    @Nullable Duration maxWait,
    @Nullable Duration idleTimeout,
    @Nullable Duration connectionLifetime,
    @Nullable Boolean sharedPool) {

  /** Stratum's default for every setting but the address. */
  static final ClientSettings STRATUM_DEFAULTS =
      ClientSettings.builder()
          .connectTimeout(HttpClientConstants.DEFAULT_CONNECT_TIMEOUT)
          .timeout(HttpClientConstants.DEFAULT_TIMEOUT)
          .maxConnections(HttpClientConstants.DEFAULT_MAX_CONNECTIONS)
          .maxWait(HttpClientConstants.DEFAULT_MAX_WAIT)
          .idleTimeout(HttpClientConstants.DEFAULT_IDLE_TIMEOUT)
          .connectionLifetime(HttpClientConstants.DEFAULT_CONNECTION_LIFETIME)
          .sharedPool(false)
          .build();

  /**
   * Returns these settings, with each one left unset taken from the fallback.
   *
   * @param fallback the settings used where these leave one unset
   * @return the merged settings
   */
  public ClientSettings orElse(ClientSettings fallback) {
    return ClientSettings.builder()
        .url(either(url, fallback.url))
        .connectTimeout(either(connectTimeout, fallback.connectTimeout))
        .timeout(either(timeout, fallback.timeout))
        .maxConnections(either(maxConnections, fallback.maxConnections))
        .maxWait(either(maxWait, fallback.maxWait))
        .idleTimeout(either(idleTimeout, fallback.idleTimeout))
        .connectionLifetime(either(connectionLifetime, fallback.connectionLifetime))
        .sharedPool(either(sharedPool, fallback.sharedPool))
        .build();
  }

  /** Returns the first value when it is set, or else the second, which may be unset too. */
  private <T> @Nullable T either(@Nullable T value, @Nullable T fallback) {
    return value != null ? value : fallback;
  }

  /**
   * Returns the durations that are set.
   *
   * @return the set durations
   */
  Stream<Duration> durations() {
    return Stream.of(connectTimeout, timeout, maxWait, idleTimeout, connectionLifetime)
        .filter(Objects::nonNull);
  }
}
