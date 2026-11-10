package com.exepex.stratum.httpclient;

import com.exepex.stratum.core.properties.StratumCustomizers;
import jakarta.validation.constraints.AssertTrue;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import lombok.Builder;
import org.jspecify.annotations.Nullable;
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
    return allInRange(
        allSettings().map(ClientSettings::maxConnections),
        1,
        HttpClientConstants.MAX_MAX_CONNECTIONS);
  }

  /**
   * Returns whether every {@code retry.max-retries}, shared or of one client, is from 0 to 9.
   * Validation reports one that is not as {@code stratum.http-client.max-retries-in-range
   * breaks @AssertTrue}.
   *
   * @return whether every retry count is in range
   */
  @AssertTrue
  public boolean isMaxRetriesInRange() {
    return allInRange(retryValues(ClientRetry::maxRetries), 0, HttpClientConstants.MAX_RETRIES);
  }

  /**
   * Returns whether every {@code retry.multiplier}, shared or of one client, is from 1.0 to 10.0.
   * Validation reports one that is not as {@code stratum.http-client.multiplier-in-range
   * breaks @AssertTrue}.
   *
   * @return whether every multiplier is in range
   */
  @AssertTrue
  public boolean isMultiplierInRange() {
    return allInRange(
        retryValues(ClientRetry::multiplier),
        HttpClientConstants.MIN_MULTIPLIER,
        HttpClientConstants.MAX_MULTIPLIER);
  }

  /**
   * Returns whether every client's {@code retry.max-delay} is at least its {@code retry.delay},
   * where its {@code backoff-policy} is exponential, since fixed ignores {@code max-delay}. It
   * reads the shared defaults and each client's merged settings, as the two keys may come from
   * either. Validation reports one that is not as {@code
   * stratum.http-client.max-delay-at-least-delay breaks @AssertTrue}.
   *
   * @return whether every exponential back-off's longest wait is not shorter than its first
   */
  @AssertTrue
  public boolean isMaxDelayAtLeastDelay() {
    return mergedSettings()
        .map(ClientSettings::retry)
        .filter(Objects::nonNull)
        .filter(retry -> retry.backoffPolicy() == BackoffPolicy.EXPONENTIAL)
        .allMatch(ClientRetry::isMaxDelayAtLeastDelay);
  }

  /**
   * Returns whether every {@code circuit-breaker.failure-rate-threshold}, shared or of one client,
   * is from 1 to 100. Validation reports one that is not as {@code
   * stratum.http-client.failure-rate-threshold-in-range breaks @AssertTrue}.
   *
   * @return whether every failure rate is in range
   */
  @AssertTrue
  public boolean isFailureRateThresholdInRange() {
    return allInRange(
        breakerValues(ClientCircuitBreaker::failureRateThreshold),
        HttpClientConstants.MIN_RATE_THRESHOLD,
        HttpClientConstants.MAX_RATE_THRESHOLD);
  }

  /**
   * Returns whether every {@code circuit-breaker.slow-call-rate-threshold}, shared or of one
   * client, is from 1 to 100. Validation reports one that is not as {@code
   * stratum.http-client.slow-call-rate-threshold-in-range breaks @AssertTrue}.
   *
   * @return whether every slow-call rate is in range
   */
  @AssertTrue
  public boolean isSlowCallRateThresholdInRange() {
    return allInRange(
        breakerValues(ClientCircuitBreaker::slowCallRateThreshold),
        HttpClientConstants.MIN_RATE_THRESHOLD,
        HttpClientConstants.MAX_RATE_THRESHOLD);
  }

  /**
   * Returns whether every {@code circuit-breaker.sliding-window-size}, shared or of one client, is
   * from 1 to 10000. Validation reports one that is not as {@code
   * stratum.http-client.sliding-window-size-in-range breaks @AssertTrue}.
   *
   * @return whether every window size is in range
   */
  @AssertTrue
  public boolean isSlidingWindowSizeInRange() {
    return allInRange(
        breakerValues(ClientCircuitBreaker::slidingWindowSize),
        1,
        HttpClientConstants.MAX_BREAKER_CALLS);
  }

  /**
   * Returns whether every {@code circuit-breaker.minimum-number-of-calls}, shared or of one client,
   * is from 1 to 10000. Validation reports one that is not as {@code
   * stratum.http-client.minimum-number-of-calls-in-range breaks @AssertTrue}.
   *
   * @return whether every minimum is in range
   */
  @AssertTrue
  public boolean isMinimumNumberOfCallsInRange() {
    return allInRange(
        breakerValues(ClientCircuitBreaker::minimumNumberOfCalls),
        1,
        HttpClientConstants.MAX_BREAKER_CALLS);
  }

  /**
   * Returns whether every {@code circuit-breaker.permitted-number-of-calls-in-half-open-state},
   * shared or of one client, is from 1 to 1000. Validation reports one that is not as {@code
   * stratum.http-client.permitted-number-of-calls-in-half-open-state-in-range breaks @AssertTrue}.
   *
   * @return whether every number of trial calls is in range
   */
  @AssertTrue
  public boolean isPermittedNumberOfCallsInHalfOpenStateInRange() {
    return allInRange(
        breakerValues(ClientCircuitBreaker::permittedNumberOfCallsInHalfOpenState),
        1,
        HttpClientConstants.MAX_HALF_OPEN_CALLS);
  }

  /**
   * Returns whether every client sets {@code circuit-breaker.slow-call-rate-threshold} only
   * together with {@code slow-call-duration-threshold}, so that no team thinks it changed the
   * slow-call rule, which stays Resilience4j's own 60s without the duration. It reads the shared
   * defaults and each client's merged settings. Validation reports one that does not as {@code
   * stratum.http-client.slow-call-rate-with-duration breaks @AssertTrue}.
   *
   * @return whether every slow-call rate has its duration
   */
  @AssertTrue
  public boolean isSlowCallRateWithDuration() {
    return mergedSettings()
        .map(ClientSettings::circuitBreaker)
        .filter(Objects::nonNull)
        .allMatch(ClientCircuitBreaker::isSlowCallRateWithDuration);
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

  /**
   * Returns whether the shared defaults leave {@code interfaces} unset, since each generated
   * interface calls one client's service. Validation reports one that is set as {@code
   * stratum.http-client.defaults-without-interfaces breaks @AssertTrue}.
   *
   * @return whether the defaults name no interface
   */
  @AssertTrue
  public boolean isDefaultsWithoutInterfaces() {
    return defaults.interfaces().isEmpty();
  }

  /**
   * Returns whether the shared defaults leave {@code auth} unset, so that a secret goes only to the
   * client it is for. Validation reports one that is set as {@code
   * stratum.http-client.defaults-without-auth breaks @AssertTrue}.
   *
   * @return whether the defaults have no auth
   */
  @AssertTrue
  public boolean isDefaultsWithoutAuth() {
    return defaults.auth() == null;
  }

  /**
   * Returns whether the shared defaults leave {@code ssl-bundle} unset, so that a certificate and
   * its trust material reach only the clients that name them. Validation reports one that is set as
   * {@code stratum.http-client.defaults-without-ssl-bundle breaks @AssertTrue}.
   *
   * @return whether the defaults have no SSL bundle
   */
  @AssertTrue
  public boolean isDefaultsWithoutSslBundle() {
    return defaults.sslBundle() == null;
  }

  /**
   * Returns whether no client with an {@code ssl-bundle} uses the shared pool, whether it sets
   * {@code shared-pool: true} itself or takes it from the defaults, since the shared pool serves
   * several clients and has no bundle. Validation reports one that does as {@code
   * stratum.http-client.shared-pool-without-ssl-bundle breaks @AssertTrue}.
   *
   * @return whether no shared-pool client has a bundle
   */
  @AssertTrue
  public boolean isSharedPoolWithoutSslBundle() {
    return clients.keySet().stream()
        .map(this::clientSettings)
        .noneMatch(
            client -> Boolean.TRUE.equals(client.sharedPool()) && client.sslBundle() != null);
  }

  /**
   * Returns whether every client's {@code auth} sets exactly one kind. Validation reports one that
   * does not as {@code stratum.http-client.auth-one-kind breaks @AssertTrue}.
   *
   * @return whether every auth has one kind
   */
  @AssertTrue
  public boolean isAuthOneKind() {
    return clientAuths().allMatch(ClientAuth::hasOneKind);
  }

  /**
   * Returns whether every client's Basic credentials have a user name and a password. Validation
   * reports one that does not as {@code stratum.http-client.basic-complete breaks @AssertTrue}.
   *
   * @return whether every Basic auth is complete
   */
  @AssertTrue
  public boolean isBasicComplete() {
    return clientAuths().allMatch(ClientAuth::isBasicComplete);
  }

  /**
   * Returns whether every client sets {@code api-key-header} only together with {@code api-key}.
   * Validation reports one that does not as {@code stratum.http-client.api-key-header-with-api-key
   * breaks @AssertTrue}.
   *
   * @return whether every API key header has a key
   */
  @AssertTrue
  public boolean isApiKeyHeaderWithApiKey() {
    return clientAuths().allMatch(ClientAuth::isApiKeyHeaderWithApiKey);
  }

  /** Returns the auth of each client that sets one. */
  private Stream<ClientAuth> clientAuths() {
    return clients.values().stream().map(ClientSettings::auth).filter(Objects::nonNull);
  }

  /** Returns the shared defaults and the settings of each client. */
  private Stream<ClientSettings> allSettings() {
    return Stream.concat(Stream.of(defaults), clients.values().stream());
  }

  /** Returns the shared defaults and each client's settings merged over them. */
  private Stream<ClientSettings> mergedSettings() {
    return Stream.concat(Stream.of(defaults), clients.keySet().stream().map(this::clientSettings));
  }

  /** Returns one {@code retry} key of the shared defaults and of each client, set or not. */
  private <T> Stream<@Nullable T> retryValues(Function<ClientRetry, @Nullable T> key) {
    return allSettings().map(ClientSettings::retry).filter(Objects::nonNull).map(key);
  }

  /** Returns one {@code circuit-breaker} key of the shared defaults and of each client. */
  private <T> Stream<@Nullable T> breakerValues(Function<ClientCircuitBreaker, @Nullable T> key) {
    return allSettings().map(ClientSettings::circuitBreaker).filter(Objects::nonNull).map(key);
  }

  /** Returns whether every value that is set lies from the least to the most, both included. */
  private boolean allInRange(Stream<? extends @Nullable Number> values, double least, double most) {
    return values
        .filter(Objects::nonNull)
        .mapToDouble(Number::doubleValue)
        .allMatch(value -> value >= least && value <= most);
  }
}
