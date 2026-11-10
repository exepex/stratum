package com.exepex.stratum.httpclient;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;
import lombok.Builder;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * The settings of one client, or of every client under {@code defaults}. A setting a client leaves
 * unset comes from {@code defaults}, and one {@code defaults} leaves unset from Stratum. The
 * descriptions of the components are what IDEs show, so they are plain sentences.
 *
 * @param url The address of the other service, such as http://inventory:8080. Required for each
 *     client; not allowed under defaults.
 * @param connectTimeout The time allowed to open a connection, such as 2s. Defaults to 2s.
 * @param timeout The longest wait for the first byte of an answer and between two of its bytes,
 *     such as 5s or 20s for a slow report. A slow answer whose bytes keep coming takes as long as
 *     it needs. Defaults to 5s.
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
 * @param auth How the client proves who it is to the other service: one of api-key, bearer or
 *     basic. Not allowed under defaults, so that a secret goes only to the client it is for.
 * @param sslBundle The name of a spring.ssl.bundle bundle whose certificate the client presents and
 *     whose trust material it checks the other service with (mutual TLS), such as bank. Not allowed
 *     under defaults, nor with shared-pool.
 * @param retry When a failed call is sent again, and how long the client waits first.
 * @param totalTimeout The time allowed for a whole call, retries and waits included, such as 8s.
 *     Defaults to 8s.
 * @param circuitBreaker When calls stop for a while, failing at once, after many of them fail.
 * @param mock Whether the client answers from files under stratum-mocks and from test scripts
 *     instead of calling the other service, for a service that is not deployed yet and for tests.
 *     Logs a warning at every startup. Defaults to false.
 * @param interfaces The full names of generated interfaces, such as com.partner.OrdersApi, that
 *     call this client's service with all of its settings, as an interface marked @StratumClient
 *     does. Only for a client; not allowed under defaults. Defaults to none.
 * @param callLog Which calls write a log line: errors, one WARN per failed call after its retries;
 *     all, also one INFO per successful call; or none. Defaults to errors.
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
    @Nullable Boolean sharedPool,
    @NestedConfigurationProperty @Nullable ClientAuth auth,
    @Nullable String sslBundle,
    @NestedConfigurationProperty @Nullable ClientRetry retry,
    @Nullable Duration totalTimeout,
    @NestedConfigurationProperty @Nullable ClientCircuitBreaker circuitBreaker,
    @Nullable Boolean mock,
    List<String> interfaces,
    @Nullable CallLog callLog) {

  /** Keeps the interfaces a fixed list, empty when none is named. */
  public ClientSettings {
    interfaces = interfaces == null ? List.of() : List.copyOf(interfaces);
  }

  /** Stratum's default for every setting but the address. */
  static final ClientSettings STRATUM_DEFAULTS =
      ClientSettings.builder()
          .connectTimeout(HttpClientConstants.DEFAULT_CONNECT_TIMEOUT)
          .timeout(HttpClientConstants.DEFAULT_TIMEOUT)
          .maxConnections(HttpClientConstants.DEFAULT_MAX_CONNECTIONS)
          .maxWait(HttpClientConstants.DEFAULT_MAX_WAIT)
          .idleTimeout(HttpClientConstants.DEFAULT_IDLE_TIMEOUT)
          .connectionLifetime(HttpClientConstants.DEFAULT_CONNECTION_LIFETIME)
          .retry(
              ClientRetry.builder()
                  .maxRetries(HttpClientConstants.DEFAULT_RETRIES)
                  .backoffPolicy(BackoffPolicy.EXPONENTIAL)
                  .delay(HttpClientConstants.DEFAULT_RETRY_DELAY)
                  .multiplier(HttpClientConstants.DEFAULT_MULTIPLIER)
                  .build())
          .totalTimeout(HttpClientConstants.DEFAULT_TOTAL_TIMEOUT)
          .circuitBreaker(
              ClientCircuitBreaker.builder()
                  .enabled(true)
                  .failureRateThreshold(HttpClientConstants.DEFAULT_FAILURE_RATE_THRESHOLD)
                  .slidingWindowSize(HttpClientConstants.DEFAULT_SLIDING_WINDOW_SIZE)
                  .minimumNumberOfCalls(HttpClientConstants.DEFAULT_MINIMUM_NUMBER_OF_CALLS)
                  .waitDurationInOpenState(HttpClientConstants.DEFAULT_WAIT_IN_OPEN_STATE)
                  .permittedNumberOfCallsInHalfOpenState(
                      HttpClientConstants.DEFAULT_HALF_OPEN_CALLS)
                  .build())
          .sharedPool(false)
          .mock(false)
          .callLog(CallLog.ERRORS)
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
        .auth(either(auth, fallback.auth))
        .sslBundle(either(sslBundle, fallback.sslBundle))
        .retry(retry == null ? fallback.retry : retry.orElse(fallback.retry))
        .totalTimeout(either(totalTimeout, fallback.totalTimeout))
        .circuitBreaker(
            circuitBreaker == null
                ? fallback.circuitBreaker
                : circuitBreaker.orElse(fallback.circuitBreaker))
        .mock(either(mock, fallback.mock))
        .interfaces(interfaces.isEmpty() ? fallback.interfaces : interfaces)
        .callLog(either(callLog, fallback.callLog))
        .build();
  }

  /** Returns the first value when it is set, or else the second, which may be unset too. */
  private <T> @Nullable T either(@Nullable T value, @Nullable T fallback) {
    return value != null ? value : fallback;
  }

  /**
   * Returns the durations that are set, those of {@code retry} and {@code circuit-breaker}
   * included.
   *
   * @return the set durations
   */
  Stream<Duration> durations() {
    return Stream.of(
            Stream.of(
                connectTimeout, timeout, maxWait, idleTimeout, connectionLifetime, totalTimeout),
            Stream.ofNullable(retry).flatMap(ClientRetry::durations),
            Stream.ofNullable(circuitBreaker).flatMap(ClientCircuitBreaker::durations))
        .flatMap(Function.identity())
        .filter(Objects::nonNull);
  }
}
