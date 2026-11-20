package com.exepex.stratum.httpclient;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Builder;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.unit.DataSize;

/**
 * The settings of one client, or of every client under {@code defaults}. A setting a client leaves
 * unset comes from {@code defaults}, and one {@code defaults} leaves unset from Stratum. The
 * descriptions of the components are what IDEs show, so they are plain sentences.
 *
 * @param url The address of the other service, such as http://inventory:8080. Required. Set it for
 *     each client; it is not allowed under defaults.
 * @param connectTimeout The time allowed to open a connection, such as 2s. A value below 1ms is
 *     used as 1ms. Defaults to 2s.
 * @param readTimeout The longest wait for the first byte of an answer and between two of its bytes,
 *     such as 5s or 20s for a slow report. A slow answer whose bytes keep coming takes as long as
 *     it needs. A value below 1ms is used as 1ms. Defaults to 5s.
 * @param maxConnections The most connections the client keeps open at once, from 1 to 10000. A call
 *     that finds them all busy waits for one. Defaults to 50.
 * @param maxWait How long a call waits for a free connection before it fails, such as 2s or 500ms.
 *     A value below 1ms is used as 1ms. Defaults to 2s.
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
 *     whose trust material it checks the other service with (mutual TLS), such as bank. None by
 *     default. Not allowed under defaults, nor with shared-pool.
 * @param retry When a failed call is sent again, and how long the client waits first.
 * @param totalTimeout The time allowed for a whole call, retries, waits and the answer's head and
 *     body included, except a streamed download's body, such as 8s. Defaults to 8s.
 * @param circuitBreaker When calls stop for a while, failing at once, after many of them fail.
 * @param mock Whether the client answers from files under stratum-mocks and from test scripts
 *     instead of calling the other service, for a service that is not deployed yet and for tests.
 *     Logs a warning at every startup. Defaults to false.
 * @param interfaces The full names of generated interfaces, such as com.partner.OrdersApi, that
 *     call this client's service with all of its settings, as an interface marked @StratumClient
 *     does. Only for a client; not allowed under defaults. None by default.
 * @param headers Headers sent on every call, by name, such as X-Partner-Id: orders. A client's are
 *     added to those under defaults; for the same name, compared without case, the client's value
 *     wins, and an empty value drops the default and sends nothing. Authorization, Cookie,
 *     Set-Cookie, Proxy-Authorization and the client's API key header are refused. None by default.
 * @param copyHeaders The names of headers copied from the request the service is answering, such as
 *     X-Tenant, onto every call made while answering it. A name the request does not have, or a
 *     call made outside a request, sends nothing. A client's names are joined with those under
 *     defaults. The same headers as in headers are refused. None by default.
 * @param interceptorBodyLimit The most of a request body an interceptor may read with body(), such
 *     as 1MB or 8MB, from 1B to 1GB. A call whose body is larger fails before it is sent, with
 *     SX-HTTPC-0005. Reading a body holds up to about three times this much memory while the call
 *     runs. 0 is refused. Defaults to 1MB.
 * @param callLog Which calls write a log line: errors, one WARN per failed call after its retries;
 *     all, also one INFO per successful call; or none. Defaults to errors.
 * @param responseBodyLimit The most of a successful answer's body the client reads, such as 32MB,
 *     from 1B to 1GB, or -1 for no limit. Reading a larger body fails with SX-HTTPC-0006; a body
 *     the call does not read is dropped once it passes the limit. A method that returns an
 *     InputStream is not limited. Defaults to 32MB.
 * @param traceHeaders Whether calls carry the correlation ID: true sends X-Correlation-Id when the
 *     call has none, beside the trace headers Spring Boot's tracing sends; false sends no trace,
 *     baggage or correlation header, such as to a third party. Defaults to true.
 * @param errors The service's own error code for each error status, from 400 to 599, that the other
 *     service may answer, such as 409: ORD-0409. A call of the client's interface answered with
 *     that status throws a DownstreamException whose error code is this one; any other status keeps
 *     SX-HTTPC-0007 or SX-HTTPC-0009. A code is not empty, has no whitespace and does not start
 *     with SX-. A client's codes are added to those under defaults; for the same status the
 *     client's wins. None by default.
 * @param operations The settings of each operation of the client, by the name a method carries in
 *     its @StratumOperation, such as list-orders, which win over the client's own. A name that no
 *     method of the client's interfaces carries stops startup. Only for a client; not allowed under
 *     defaults. None by default.
 */
@Builder(toBuilder = true)
public record ClientSettings(
    @Nullable String url,
    @Nullable Duration connectTimeout,
    @Nullable Duration readTimeout,
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
    Map<String, String> headers,
    List<String> copyHeaders,
    @Nullable DataSize interceptorBodyLimit,
    @Nullable CallLog callLog,
    @Nullable DataSize responseBodyLimit,
    @Nullable Boolean traceHeaders,
    Map<
            @Min(HttpClientConstants.MIN_ERROR_STATUS) @Max(HttpClientConstants.MAX_ERROR_STATUS)
            Integer,
            @Pattern(regexp = HttpClientConstants.ERROR_CODE_FORMAT) String>
        errors,
    Map<
            @Pattern(regexp = HttpClientConstants.OPERATION_NAME_FORMAT) String,
            @Valid OperationSettings>
        operations) {

  /**
   * Keeps the interfaces, headers, copied headers, error codes and operations fixed, empty when
   * none is named.
   */
  public ClientSettings {
    interfaces = interfaces == null ? List.of() : List.copyOf(interfaces);
    headers = headers == null ? Map.of() : Map.copyOf(headers);
    copyHeaders = copyHeaders == null ? List.of() : List.copyOf(copyHeaders);
    errors = errors == null ? Map.of() : Map.copyOf(errors);
    operations = operations == null ? Map.of() : Map.copyOf(operations);
  }

  /** Stratum's default for every setting but the address. */
  static final ClientSettings STRATUM_DEFAULTS =
      ClientSettings.builder()
          .connectTimeout(HttpClientConstants.DEFAULT_CONNECT_TIMEOUT)
          .readTimeout(HttpClientConstants.DEFAULT_READ_TIMEOUT)
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
          .interceptorBodyLimit(HttpClientConstants.DEFAULT_INTERCEPTOR_BODY_LIMIT)
          .callLog(CallLog.ERRORS)
          .responseBodyLimit(HttpClientConstants.DEFAULT_RESPONSE_BODY_LIMIT)
          .traceHeaders(true)
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
        .readTimeout(either(readTimeout, fallback.readTimeout))
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
        .headers(headersOver(fallback.headers))
        .copyHeaders(copyHeadersAfter(fallback.copyHeaders))
        .interceptorBodyLimit(either(interceptorBodyLimit, fallback.interceptorBodyLimit))
        .callLog(either(callLog, fallback.callLog))
        .responseBodyLimit(either(responseBodyLimit, fallback.responseBodyLimit))
        .traceHeaders(either(traceHeaders, fallback.traceHeaders))
        .errors(mapOver(errors, fallback.errors))
        // An operation belongs to one client, so the fallback's are never merged in.
        .operations(operations)
        .build();
  }

  /**
   * Returns the error codes of one operation's calls: the operation's own over the client's, for
   * the same status the operation's.
   *
   * @param operation the operation's name, or {@code null} for a call of a method with none
   * @return the error code of each mapped status
   */
  public Map<Integer, String> errorsOf(@Nullable String operation) {
    return Optional.ofNullable(operation)
        .map(operations::get)
        .map(own -> mapOver(own.errors(), errors))
        .orElse(errors);
  }

  /**
   * Shows every setting, but only the names of {@code headers}, never their values, since a fixed
   * header may hold personal data, such as an email address in {@code From}. {@code auth} shows its
   * kind only.
   *
   * @return a text without header values
   */
  @Override
  public String toString() {
    return Stream.of(
            "url=" + url,
            "connectTimeout=" + connectTimeout,
            "readTimeout=" + readTimeout,
            "maxConnections=" + maxConnections,
            "maxWait=" + maxWait,
            "idleTimeout=" + idleTimeout,
            "connectionLifetime=" + connectionLifetime,
            "sharedPool=" + sharedPool,
            "auth=" + auth,
            "sslBundle=" + sslBundle,
            "retry=" + retry,
            "totalTimeout=" + totalTimeout,
            "circuitBreaker=" + circuitBreaker,
            "mock=" + mock,
            "interfaces=" + interfaces,
            "headers=" + new TreeSet<>(headers.keySet()),
            "copyHeaders=" + copyHeaders,
            "interceptorBodyLimit=" + interceptorBodyLimit,
            "responseBodyLimit=" + responseBodyLimit,
            "traceHeaders=" + traceHeaders,
            "callLog=" + callLog,
            "errors=" + new TreeMap<>(errors),
            "operations=" + new TreeSet<>(operations.keySet()))
        .collect(Collectors.joining(", ", "ClientSettings[", "]"));
  }

  /**
   * Returns whether {@code interceptor-body-limit}, when set, is from 1 byte to 1 gigabyte.
   * Validation reports one that is not as {@code stratum.http-client.defaults
   * .interceptor-body-limit-in-range} or {@code stratum.http-client.clients[*]
   * .interceptor-body-limit-in-range breaks @AssertTrue}, without the value.
   *
   * @return whether the limit is in range
   */
  @AssertTrue
  public boolean isInterceptorBodyLimitInRange() {
    return interceptorBodyLimit == null
        || interceptorBodyLimit.toBytes() >= 1
            && interceptorBodyLimit.toBytes()
                <= HttpClientConstants.MAX_INTERCEPTOR_BODY_LIMIT.toBytes();
  }

  /**
   * Returns whether {@code response-body-limit}, when set, is -1, for no limit, or from 1 byte to 1
   * gigabyte. Validation reports one that is not as {@code stratum.http-client.defaults
   * .response-body-limit-in-range} or {@code stratum.http-client.clients[*]
   * .response-body-limit-in-range breaks @AssertTrue}, without the value.
   *
   * @return whether the limit is in range
   */
  @AssertTrue
  public boolean isResponseBodyLimitInRange() {
    return responseBodyLimit == null
        || responseBodyLimit.equals(HttpClientConstants.NO_RESPONSE_BODY_LIMIT)
        || responseBodyLimit.toBytes() >= 1
            && responseBodyLimit.toBytes() <= HttpClientConstants.MAX_RESPONSE_BODY_LIMIT.toBytes();
  }

  /**
   * Returns whether no header in {@code headers} or {@code copy-headers} is one Stratum refuses,
   * compared without case: one that carries a user's identity or the client's API key. A header
   * with an empty value sends nothing, so it is not checked.
   *
   * @return whether every header name is allowed
   */
  boolean isHeadersAllowed() {
    var refused = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    refused.addAll(HttpClientConstants.REFUSED_HEADERS);
    Optional.ofNullable(auth).map(ClientAuth::apiKeyHeader).ifPresent(refused::add);
    var sentHeaders =
        headers.entrySet().stream()
            .filter(header -> !header.getValue().isEmpty())
            .map(Map.Entry::getKey);
    return Stream.concat(sentHeaders, copyHeaders.stream()).noneMatch(refused::contains);
  }

  /**
   * Returns these headers added to the fallback's: for the same name, compared without case, this
   * value wins. An empty value is kept, so that it still drops the default when merged settings are
   * merged again, such as after a Java customizer stored them back; it sends nothing.
   */
  private Map<String, String> headersOver(Map<String, String> fallback) {
    var merged = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
    merged.putAll(fallback);
    // Removed first, so that the name keeps this side's spelling.
    headers.keySet().forEach(merged::remove);
    merged.putAll(headers);
    return Map.copyOf(merged);
  }

  /** Returns the entries of the first map added to the second's, the first winning on a key. */
  private <K, V> Map<K, V> mapOver(Map<K, V> winning, Map<K, V> fallback) {
    var merged = new HashMap<>(fallback);
    merged.putAll(winning);
    return Map.copyOf(merged);
  }

  /** Returns the fallback's copied headers, then these, each name once, compared without case. */
  private List<String> copyHeadersAfter(List<String> fallback) {
    var names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    return Stream.concat(fallback.stream(), copyHeaders.stream()).filter(names::add).toList();
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
                connectTimeout,
                readTimeout,
                maxWait,
                idleTimeout,
                connectionLifetime,
                totalTimeout),
            Stream.ofNullable(retry).flatMap(ClientRetry::durations),
            Stream.ofNullable(circuitBreaker).flatMap(ClientCircuitBreaker::durations))
        .flatMap(Function.identity())
        .filter(Objects::nonNull);
  }
}
