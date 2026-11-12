package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.httpclient.ClientSettings;
import com.exepex.stratum.httpclient.HttpClientConstants;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

/**
 * The Apache HttpClient 5 connection pools of Stratum's clients: one per client, or one shared by
 * every client with {@code shared-pool: true}, which takes its connection settings from {@code
 * defaults}. Each pool holds at most {@code max-connections}, checks a connection idle for more
 * than 2 seconds before using it, closes one unused for {@code idle-timeout} and does not reuse one
 * older than {@code connection-lifetime}. A client with {@code ssl-bundle} opens its TLS
 * connections with that bundle. Apache's own retries and redirects are off. Every pool closes with
 * the application. A client's pool, and the shared one, show their size and use in the meters; a
 * token pool does not.
 */
@StratumInternal
@RequiredArgsConstructor
public class ClientConnectionPools implements DisposableBean {

  /** The key of the shared pool, which no client name can be, as a name starts with a letter. */
  private static final String SHARED_POOL = "";

  /** The limit on an error answer's body, first in each client's chain, before gzip is undone. */
  private static final ErrorBodyLimit ERROR_BODY_LIMIT = new ErrorBodyLimit();

  /**
   * The start of the key of a client's token pool, which no client name has, as names have no ':'.
   */
  private static final String TOKEN_POOL = "token:";

  /** The shortest timeout Apache keeps: it reads a shorter one as 0, which means no limit. */
  private static final Duration ONE_MILLISECOND = Duration.ofMillis(1);

  /** The connections of a client's token pool: one fetch runs at a time per registration. */
  private static final int TOKEN_POOL_CONNECTIONS = 2;

  /** The settings of the shared pool: the shared defaults under {@code defaults}. */
  private final ClientSettings sharedPoolSettings;

  /** Spring Boot's SSL bundles, read only for a client that names one. */
  private final ObjectProvider<SslBundles> sslBundles;

  /** The meters of the clients, present with Micrometer and a registry. */
  private final ObjectProvider<ClientMeters> meters;

  /** The pools made so far, by client name or {@link #SHARED_POOL}. */
  private final Map<String, PoolingHttpClientConnectionManager> pools = new ConcurrentHashMap<>();

  /** The clients made so far, which are closed before their pools. */
  private final List<CloseableHttpClient> clients = new CopyOnWriteArrayList<>();

  /** One daemon thread that closes idle and old connections in every pool. */
  private final ScheduledExecutorService connectionCloser =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon().name("stratum-http-client-pools").factory());

  /**
   * Returns the request factory of one client, on its own pool or the shared one. Opens no
   * connection.
   *
   * @param client the client's name
   * @param settings the client's settings
   * @return the request factory
   */
  public ClientHttpRequestFactory requestFactory(String client, ClientSettings settings) {
    return requestFactory(client, settings, true);
  }

  /**
   * Returns the request factory of one pool. With {@code streamsAnswers}, the pool of a client's
   * own calls, a streamed download keeps its answer timeout and each attempt notes its address;
   * without, as for a token request, the answer and its body always end by the call's {@code
   * total-timeout} and the address is not noted, so a token fetch never stands for the call.
   */
  private ClientHttpRequestFactory requestFactory(
      String poolKey, ClientSettings settings, boolean streamsAnswers) {
    var bodyDeadline = new BodyDeadline(Objects.requireNonNull(settings.timeout()), streamsAnswers);
    var requestConfig =
        RequestConfig.custom()
            .setConnectionRequestTimeout(
                atLeastOneMillisecond(Objects.requireNonNull(settings.maxWait())))
            .setResponseTimeout(atLeastOneMillisecond(Objects.requireNonNull(settings.timeout())))
            .build();
    var httpClient =
        HttpClients.custom()
            .useSystemProperties()
            .setConnectionManager(poolFor(poolKey, settings))
            .setConnectionManagerShared(true)
            .addExecInterceptorFirst(ErrorBodyLimit.NAME, ERROR_BODY_LIMIT)
            .setRequestExecutor(bodyDeadline)
            .addExecInterceptorFirst(
                ResponseBodyLimit.NAME,
                new ResponseBodyLimit(
                    poolKey,
                    Objects.requireNonNull(settings.responseBodyLimit()).toBytes(),
                    streamsAnswers))
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .setDefaultRequestConfig(requestConfig)
            .build();
    clients.add(httpClient);
    return withAttemptTimeouts(
        new HttpComponentsClientHttpRequestFactory(httpClient),
        requestConfig,
        settings,
        streamsAnswers,
        bodyDeadline);
  }

  /**
   * Returns the request factory a client's service token is fetched through: a small pool of its
   * own, with the client's timeouts, so that the fetch counts toward the call's {@code
   * total-timeout}, even when the call is a streamed download, and the JVM's default TLS, never the
   * client's {@code ssl-bundle}, which is for the other service, not the identity provider.
   *
   * @param client the client's name
   * @param settings the client's settings
   * @return the request factory
   */
  public ClientHttpRequestFactory tokenRequestFactory(String client, ClientSettings settings) {
    var tokenSettings =
        settings.toBuilder()
            .sslBundle(null)
            .sharedPool(false)
            .maxConnections(TOKEN_POOL_CONNECTIONS)
            .build();
    return requestFactory(TOKEN_POOL + client, tokenSettings, false);
  }

  /** Makes each attempt of a call through the factory end by the call's {@code total-timeout}. */
  private ClientHttpRequestFactory withAttemptTimeouts(
      HttpComponentsClientHttpRequestFactory requestFactory,
      RequestConfig requestConfig,
      ClientSettings settings,
      boolean streamsAnswers,
      BodyDeadline bodyDeadline) {
    requestFactory.setHttpContextFactory(
        (method, uri) ->
            attemptContext(uri, requestConfig, settings, streamsAnswers, bodyDeadline));
    return requestFactory;
  }

  /**
   * Returns the context of one attempt of a call, whose timeouts end by the call's {@code
   * total-timeout}, or none outside a call of a client's interface, which keeps the client's own;
   * notes the attempt's address for a client's own pool.
   */
  private @Nullable HttpContext attemptContext(
      URI uri,
      RequestConfig requestConfig,
      ClientSettings settings,
      boolean streamsAnswers,
      BodyDeadline bodyDeadline) {
    if (streamsAnswers) {
      AttemptTimeLeft.sentTo(uri);
    }
    return AttemptTimeLeft.timeLeft()
        .map(timeLeft -> contextWithin(requestConfig, settings, timeLeft, bodyDeadline))
        .orElse(null);
  }

  /**
   * Returns a context whose pool wait and answer timeout are each the smaller of the client's and
   * the time left; a streamed download through a factory that streams answers keeps its answer
   * timeout, the longest gap between bytes. The pool cuts the connect timeout, in {@link
   * #connectionConfig}.
   */
  private HttpContext contextWithin(
      RequestConfig requestConfig,
      ClientSettings settings,
      Duration timeLeft,
      BodyDeadline bodyDeadline) {
    var attemptConfig =
        RequestConfig.copy(requestConfig)
            .setConnectionRequestTimeout(
                within(Objects.requireNonNull(settings.maxWait()), timeLeft));
    if (bodyDeadline.boundsAnswer()) {
      attemptConfig.setResponseTimeout(
          within(Objects.requireNonNull(settings.timeout()), timeLeft));
    }
    var context = HttpClientContext.create();
    context.setRequestConfig(attemptConfig.build());
    return context;
  }

  /** Returns the smaller of a timeout and the time left, at least 1ms. */
  private Timeout within(Duration timeout, Duration timeLeft) {
    return atLeastOneMillisecond(timeout.compareTo(timeLeft) < 0 ? timeout : timeLeft);
  }

  /** Returns a timeout of at least 1ms, as Apache reads a time under 1ms as 0, its "no limit". */
  private Timeout atLeastOneMillisecond(Duration timeout) {
    return Timeout.of(timeout.compareTo(ONE_MILLISECOND) < 0 ? ONE_MILLISECOND : timeout);
  }

  /**
   * Returns the client's own pool, or the shared one, made on first use and shown in the meters,
   * unless it is a token pool.
   */
  private PoolingHttpClientConnectionManager poolFor(String client, ClientSettings settings) {
    return Boolean.TRUE.equals(settings.sharedPool())
        ? pools.computeIfAbsent(
            SHARED_POOL, unused -> metered(HttpClientConstants.SHARED_POOL_TAG, sharedPoolSettings))
        : pools.computeIfAbsent(client, unused -> metered(client, settings));
  }

  /** Makes a pool and shows it in the meters under its client's name, unless it is a token pool. */
  private PoolingHttpClientConnectionManager metered(String name, ClientSettings settings) {
    var pool = newPool(settings);
    if (!name.startsWith(TOKEN_POOL)) {
      meters.ifAvailable(clientMeters -> clientMeters.poolMade(name, pool));
    }
    return pool;
  }

  /** Closes every client, then every pool and the thread that tends them. */
  @Override
  public void destroy() {
    clients.forEach(httpClient -> httpClient.close(CloseMode.GRACEFUL));
    pools.values().forEach(pool -> pool.close(CloseMode.GRACEFUL));
    connectionCloser.shutdownNow();
  }

  /**
   * Makes a pool, with the client's SSL bundle if it names one, and has its idle and old
   * connections closed twice per idle timeout.
   */
  private PoolingHttpClientConnectionManager newPool(ClientSettings settings) {
    var maxConnections = Objects.requireNonNull(settings.maxConnections());
    var poolConfig =
        ConnectionConfig.custom()
            .setConnectTimeout(
                atLeastOneMillisecond(Objects.requireNonNull(settings.connectTimeout())))
            .setValidateAfterInactivity(TimeValue.of(HttpClientConstants.STALE_CHECK_AFTER))
            .setTimeToLive(TimeValue.of(Objects.requireNonNull(settings.connectionLifetime())))
            .build();
    var builder = PoolingHttpClientConnectionManagerBuilder.create();
    Optional.ofNullable(settings.sslBundle())
        .map(bundle -> new SslBundleTlsStrategy(sslBundles.getObject(), bundle))
        .ifPresent(builder::setTlsSocketStrategy);
    var pool =
        builder
            .useSystemProperties()
            .setMaxConnTotal(maxConnections)
            .setMaxConnPerRoute(maxConnections)
            .setConnectionConfigResolver(route -> connectionConfig(poolConfig))
            .build();
    var idleTimeout = Objects.requireNonNull(settings.idleTimeout());
    var period = Math.max(1, idleTimeout.toMillis() / 2);
    connectionCloser.scheduleAtFixedRate(
        () -> closeUnusedConnections(pool, idleTimeout), period, period, TimeUnit.MILLISECONDS);
    return pool;
  }

  /**
   * Returns the pool's connection settings for a new connection, with the connect timeout cut to
   * the time left of the call, as the pool connects on the caller's thread during one attempt.
   */
  private ConnectionConfig connectionConfig(ConnectionConfig poolConfig) {
    return AttemptTimeLeft.timeLeft()
        .map(
            timeLeft ->
                ConnectionConfig.copy(poolConfig)
                    .setConnectTimeout(
                        within(poolConfig.getConnectTimeout().toDuration(), timeLeft))
                    .build())
        .orElse(poolConfig);
  }

  /** Closes the pool's connections that are past their lifetime or unused for idle-timeout. */
  private void closeUnusedConnections(
      PoolingHttpClientConnectionManager pool, Duration idleTimeout) {
    pool.closeExpired();
    pool.closeIdle(TimeValue.of(idleTimeout));
  }
}
