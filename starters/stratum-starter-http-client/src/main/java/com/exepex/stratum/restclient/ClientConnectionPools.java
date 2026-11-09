package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.httpclient.ClientSettings;
import com.exepex.stratum.httpclient.HttpClientConstants;
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
 * the application.
 */
@StratumInternal
@RequiredArgsConstructor
public class ClientConnectionPools implements DisposableBean {

  /** The key of the shared pool, which no client name can be, as a name starts with a letter. */
  private static final String SHARED_POOL = "";

  /** The limit on an error answer's body, first in each client's chain, before gzip is undone. */
  private static final ErrorBodyLimit ERROR_BODY_LIMIT = new ErrorBodyLimit();

  /** The settings of the shared pool: the shared defaults under {@code defaults}. */
  private final ClientSettings sharedPoolSettings;

  /** Spring Boot's SSL bundles, read only for a client that names one. */
  private final ObjectProvider<SslBundles> sslBundles;

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
    var requestConfig =
        RequestConfig.custom()
            .setConnectionRequestTimeout(Timeout.of(Objects.requireNonNull(settings.maxWait())))
            .setResponseTimeout(Timeout.of(Objects.requireNonNull(settings.timeout())))
            .build();
    var httpClient =
        HttpClients.custom()
            .useSystemProperties()
            .setConnectionManager(poolFor(client, settings))
            .setConnectionManagerShared(true)
            .addExecInterceptorFirst(ErrorBodyLimit.NAME, ERROR_BODY_LIMIT)
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .setDefaultRequestConfig(requestConfig)
            .build();
    clients.add(httpClient);
    return withAttemptTimeouts(
        new HttpComponentsClientHttpRequestFactory(httpClient), requestConfig, settings);
  }

  /** Makes each attempt of a call through the factory end by the call's {@code total-timeout}. */
  private ClientHttpRequestFactory withAttemptTimeouts(
      HttpComponentsClientHttpRequestFactory requestFactory,
      RequestConfig requestConfig,
      ClientSettings settings) {
    requestFactory.setHttpContextFactory((method, uri) -> attemptContext(requestConfig, settings));
    return requestFactory;
  }

  /**
   * Returns the context of one attempt of a call, whose timeouts end by the call's {@code
   * total-timeout}, or none outside a call of a client's interface, which keeps the client's own.
   */
  private @Nullable HttpContext attemptContext(
      RequestConfig requestConfig, ClientSettings settings) {
    return AttemptTimeLeft.timeLeft()
        .map(timeLeft -> contextWithin(requestConfig, settings, timeLeft))
        .orElse(null);
  }

  /**
   * Returns a context whose pool wait and answer timeout are each the smaller of the client's and
   * the time left; a streamed download keeps its answer timeout, the longest gap between bytes. The
   * pool cuts the connect timeout, in {@link #connectionConfig}.
   */
  private HttpContext contextWithin(
      RequestConfig requestConfig, ClientSettings settings, Duration timeLeft) {
    var attemptConfig =
        RequestConfig.copy(requestConfig)
            .setConnectionRequestTimeout(
                within(Objects.requireNonNull(settings.maxWait()), timeLeft));
    if (!AttemptTimeLeft.keepsAnswerTimeout()) {
      attemptConfig.setResponseTimeout(
          within(Objects.requireNonNull(settings.timeout()), timeLeft));
    }
    var context = HttpClientContext.create();
    context.setRequestConfig(attemptConfig.build());
    return context;
  }

  /**
   * Returns the smaller of a timeout and the time left, at least 1ms, as Apache reads 0 as no
   * limit.
   */
  private Timeout within(Duration timeout, Duration timeLeft) {
    var smaller = timeout.compareTo(timeLeft) < 0 ? timeout : timeLeft;
    return Timeout.of(smaller.isZero() ? Duration.ofMillis(1) : smaller);
  }

  /** Returns the client's own pool, or the shared one, made on first use. */
  private PoolingHttpClientConnectionManager poolFor(String client, ClientSettings settings) {
    return Boolean.TRUE.equals(settings.sharedPool())
        ? pools.computeIfAbsent(SHARED_POOL, unused -> newPool(sharedPoolSettings))
        : pools.computeIfAbsent(client, unused -> newPool(settings));
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
            .setConnectTimeout(Timeout.of(Objects.requireNonNull(settings.connectTimeout())))
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
