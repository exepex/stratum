# HTTP client starter, version 4: the agreed design (2026-11-09)

This version replaces design-v3.md. It takes version 3, adds the 11-08 pool decisions, the 21 gap-review answers
(gap-review-merged.md, "Decisions") and the spike results (spike-result.md). Every line here was decided by Aashish
on a card or in the thread. Nothing is built. Scope: the MVC (blocking) starter.
The reactive starter uses the same keys and rules and is built later.

## Full YAML

```yaml
stratum:
  http-client:
    defaults:                      # all optional; the values shown are the built-in defaults
      connect-timeout: 2s
      timeout: 5s                  # per attempt; on a download: the longest gap between bytes
      total-timeout: 8s            # all attempts and waits together; below the web starter's 10s
      retries: 3                   # 0 = off, max 9
      retry-delay: 200ms           # first wait; then doubles with jitter, max 2s
      circuit-breaker: true
      max-connections: 50          # own pool per client
      max-wait: 2s                 # wait for a free pool connection, then the call is refused
      idle-timeout: 30s            # idle connections are closed after this
      connection-lifetime: 5m      # a connection is not reused after this age
      trace-headers: true          # traceparent + X-Correlation-Id
      headers: { X-Source: billing-service }

    clients:

      inventory:                   # 1. internal, plain
        url: http://inventory:8080

      orders:                      # 2. internal, load balanced, caller's token passed on
        urls: [http://orders-1:8080, http://orders-2:8080]
        auth: { token-relay: true }

      weather:                     # 3. third party, API key
        url: https://api.weather.example/v2
        auth: { api-key: ${WEATHER_API_KEY} }    # header X-Api-Key; api-key-header: X-Key to rename
        trace-headers: false

      invoices:                    # 4. service's own OAuth2 token (client credentials)
        url: http://invoice-service:8080
        auth: { client-credentials: invoices }   # name of the Spring Security registration below

      bank:                        # 5. basic auth + mTLS
        url: https://gateway.bank.example
        ssl-bundle: bank
        auth: { basic: { username: ${BANK_USER}, password: ${BANK_PASSWORD} } }
        timeout: 20s
        total-timeout: 25s
        retries: 0
        max-connections: 10

      partner:                     # 6. bearer token, custom headers, interceptor (linked in Java)
        url: https://api.partner.example
        auth: { bearer: ${PARTNER_TOKEN} }
        headers: { X-Client-Name: acme-shop, X-Api-Version: "2" }   # added to defaults.headers
        copy-headers: [X-Order-Ref]
        trace-headers: false

      payments:                    # 7. mocked while the real service is not ready
        url: http://payments:8080
        auth: { client-credentials: payments }
        mock: true                 # answers from classpath:stratum-mocks/payments/<method>.json

      partner-orders:              # 8. a generated interface (OpenAPI), no @StratumClient needed
        url: https://orders.partner.example
        interface: com.partner.OrdersApi

spring:                            # Spring's own keys, written once; Stratum only points at them
  security.oauth2.client:
    registration:
      invoices:
        provider: company-sso
        client-id: billing-service
        client-secret: ${INVOICES_CLIENT_SECRET}
        authorization-grant-type: client_credentials
        scope: invoices.write
    provider.company-sso.token-uri: https://sso.company.example/oauth2/token
  ssl.bundle.pem.bank:
    reload-on-update: true
    keystore: { certificate: file:/etc/certs/bank/client.crt, private-key: file:/etc/certs/bank/client.key }
    truststore.certificate: file:/etc/certs/bank/ca.crt
```

## Every key (same keys under `defaults` and `clients.<name>`, client wins)

| Key | Default | Typical team sets it? |
|---|---|---|
| `url` / `urls` | required (one of them) | always |
| `auth` (`token-relay`, `client-credentials`, `api-key`, `api-key-header`, `bearer`, `basic`) | none | most clients |
| `ssl-bundle` | none | mTLS only |
| `connect-timeout` | 2s | rarely |
| `timeout` | 5s | slow vendors |
| `total-timeout` | 8s | slow vendors |
| `retries` | 3 | sometimes (0 for payments) |
| `retry-delay` | 200ms | rarely |
| `circuit-breaker` | true | rarely |
| `max-connections` | 50 | busy dependencies |
| `max-wait` | 2s | rarely |
| `idle-timeout` | 30s | rarely |
| `connection-lifetime` | 5m | rarely |
| `shared-pool` | false | rarely |
| `headers` | none | some third parties |
| `copy-headers` | none | sometimes |
| `trace-headers` | true | third parties |
| `mock` | false | dev and tests |
| `interface` | none | generated clients only |

19 keys. Internal client: 1 to 2. Third party: 3 to 4. Java only: interceptors, custom back-off, custom retry or
breaker rules (Resilience4j customizer), error mapper. Planned: `auth: { token-exchange: <registration> }` (#238).

Spring Boot's own `spring.http.serviceclient.<name>.*` keys do not apply to a Stratum client: Stratum builds the
request factory, and the spike showed Boot's per-group timeout is then ignored. Startup stops (decided 2026-11-09) when a
Boot `spring.http.serviceclient.<name>.*` key is set for a name that is also a Stratum client, naming the Stratum
key to use. This reads configuration only (boot-check boundary of 11-06).

## Java

Version 3's Java is unchanged and stays the reference:
- the interface with Spring's `@GetExchange` / `@PostExchange`, `@PathVariable`, `@RequestBody`,
  `@RequestParam` and `@RequestHeader`, `Optional` = 404, under `@StratumClient(value = "orders", interceptors = OrderSigner.class)`;
- `ClientInterceptor.beforeSend` and `GlobalClientInterceptor`;
- Java settings follow ST-ENG-34: `HttpClientPropertiesCustomizer` in `META-INF/spring.factories` sets every key
  and wins over YAML. A `StratumClientCustomizer` bean is only for what has no key (custom back-off, retry or
  breaker rules, the error mapper);
- `stratumHttpClients.restClient("reports")`;
- `DownstreamException`;
- `MockClient` in stratum-test.

Streaming adds one example:

```java
@GetExchange("/reports/{id}")
InputStream downloadReport(@PathVariable String id);

try (InputStream report = reportsClient.downloadReport(id)) {   // connection back to the pool on close
  storage.save(report);
}
```

## Fixed rules (no key)

### Connections and pool
- Each client has its own Apache HttpClient 5 pool. `shared-pool: true` joins one shared pool.
- **Apache's own retry is off.** Resilience4j is the only retry layer, so `retries: 3` means at most 4 attempts.
  The spike showed Apache retries a 503 by itself when left on.
- After `max-wait` with no free connection, the call is refused with an error. No unbounded wait.
- A connection idle for more than about 2s is checked before reuse (stale check). The server's
  `Keep-Alive: timeout=` is honoured.
- `connection-lifetime` is checked only when a connection returns to or leaves the pool, never during a call.
- Redirects are never followed. Path and query values are always encoded. Gzip is accepted.
- A failed address in `urls` is skipped for a short time.
- Startup does no network work: no connection, DNS lookup or token fetch. Readiness never depends on a downstream.

### Timeouts
- Each attempt's timeout is the smaller of `timeout` and the time left under `total-timeout`. No new attempt
  starts with less than about 100ms left.
- The token request for `client-credentials` uses the client's `connect-timeout` and `timeout` and counts toward
  `total-timeout`.

### Retries (Resilience4j)
- **Retried:** connect failure, a dropped connection before any answer arrived, a read timeout (GET, PUT and
  DELETE only), 502, 503, 504 and 429.
- **Never retried:** other 4xx, 500 and 501.
- GET, PUT and DELETE follow that list. POST and PATCH are retried only when nothing was sent. A read timeout on
  POST is never retried.
- `Retry-After` on 429 or 503 is honoured when it fits in the time left. Otherwise the call fails at once.
- There is no retry while the breaker is open. A streamed upload is never retried.

### Circuit breaker (Resilience4j, one per client)
- **Counted as failure:** timeouts, I/O errors, 5xx and 429.
- **Never counted:** other 4xx, including a 404 that became `Optional.empty()`.
- When the breaker is open, the call stops with `SX-HTTPC-0003`, which the web starter answers as 503.
- A state change is logged once at WARN. Refused calls are counted, not logged one by one.

### Auth and headers
- **Token relay** sends only the verified access token from the security context, never an ID token or a raw
  header. With no caller token (`@Scheduled`, `@Async`, a listener), the call stops before sending with
  `SX-HTTPC-0004`. `token-relay` without the security starter on the classpath stops startup.
- **Client credentials:** a 401 drops the cached token, fetches a new one and retries once, POST included. A second
  401 fails and is logged once per client per minute. Spring Security already drops the token, so Stratum adds only
  the retry. A 401 on token relay goes back to the caller unchanged.
- `headers` are added to `defaults.headers`; for the same name, the client wins, and an empty value drops a
  default. `copy-headers` lists are joined. A `copy-headers` name with nothing to copy sends nothing.
- `Authorization`, `Cookie`, `Set-Cookie`, `Proxy-Authorization` and the API-key header are never accepted in
  `headers` or `copy-headers`. Tokens leave only through `auth`.
- Identity travels only inside a signed token, never as a plain header (11-06).
- No check of the target host for a `URI` method parameter (gap 1, rejected by Aashish).

### Errors
- **Codes:**
  - `SX-HTTPC-0001`: timeout, answered 504.
  - `SX-HTTPC-0002`: connect failure or downstream unavailable, answered 503.
  - `SX-HTTPC-0003`: breaker open, answered 503.
  - `SX-HTTPC-0004`: no caller token for token relay, answered 500 because it is a setup mistake of the service.
  - The pool refusal after `max-wait` uses `SX-HTTPC-0002`.
- At most 64 KB of an error body is read into `DownstreamException`; the rest is discarded. Successful answers are
  not capped (#236 parked).
- The vendor's body is never passed on to our caller.

### Streaming
- A streamed upload is never retried. An interceptor can add headers to it, but `body()` is empty.
- A download holds its connection until it is closed. The docs use `try-with-resources` everywhere.
- On a download, `timeout` is the longest gap between bytes.

### Logs, traces and metrics
- The call log line has both the real `url` (path and query values kept) and the `uri` template:
  `WARN client=orders method=GET uri=/orders/{id} url=http://orders:8080/orders/8812?status=OPEN status=503 ...`.
  The logging starter's always-on redaction masks emails, card numbers and similar values by shape, and fully masks
  query names such as `token`, `api_key` and `password`. This needs a written ST-SEC-03 exception (see the ADR).
  Without the logging starter, or with it switched off, the line carries the `uri` template only (owner, 01:01Z).
- Metric tags and span names use only the template (`/orders/{id}`), never real values.
- Pool metrics come from Micrometer's HC5 binder, tagged `client=<name>`: max, busy (leased), idle (available) and
  waiting (pending). Wait time and pool-full counts are planned (#237).
- Body debug (bodies masked, header names, live log level `stratum.http-client.<client>`, 15-minute auto-off)
  ships only together with the logging starter's timed switch. The client starter ships first without it.

### Mock
- `mock: true` answers from `classpath:stratum-mocks/<client>/<method>.json`, with a WARN at every startup.

### Startup stops on Stratum's own config mistakes
- Two auth kinds on one client, or `url` and `urls` together.
- `retries` above 9.
- A forbidden header in `headers` or `copy-headers`, or a header value with a line break.
- An `interceptors` class with no bean.
- `token-relay` without the security starter.
- A Boot `spring.http.serviceclient.<name>.*` key for a Stratum client (names the Stratum key).

## Generated and foreign clients (11-08)
1. A generated interface with Spring annotations: `clients.<name>.interface: com.partner.OrdersApi`. It gets full
   Stratum behaviour.
2. A generated client that accepts a client: pass `stratumHttpClients.restClient("<name>")`.
3. A client with its own engine (Finagle, a vendor SDK): `stratumHttpClients.settings("<name>")` for URL and
   timeouts, and `stratumHttpClients.resilience("<name>").call(...)` for retry and breaker. Auth and headers stay the
   SDK's.

Stratum never wraps, replaces or checks a foreign client. Startup checks only Stratum's own blocks, and unused
blocks are allowed.

## How it is built on Spring (verified in the spike)
- Each client is its own Spring HTTP service group (`AbstractHttpServiceRegistrar.forGroup`), configured through a
  `RestClientHttpServiceGroupConfigurer` that sets the base URL and the client's own request factory.
- Apache HC5 is built through Boot's `ClientHttpRequestFactoryBuilder.httpComponents()`. That keeps the JVM proxy
  settings and applies lifetime, stale check and retry-off through Boot's customizers.
- The retry interceptor sits outside the auth and signing interceptors, so they run again on every attempt. It
  passes the time left to Apache through the request factory's context factory and a thread-local.
- Client credentials use Spring Security's `OAuth2ClientHttpRequestInterceptor` with an
  `AuthorizedClientServiceOAuth2AuthorizedClientManager`. The token client's own `RestClient` must keep the form
  and token-response converters and the OAuth2 error handler.
- On SSL bundle reload, Boot's update handler rebuilds the client's connection manager.

## Planned (GitHub issues)
- Filed: #236 response size cap (parked), #237 pool wait timer and pool-full counter, #238 token exchange.
- **To file after the ADR:**
  - corporate egress proxy (an HTTPS target through a proxy is untested);
  - a mocked client visible in a metric or in actuator info;
  - an interceptor `afterReceive` hook;
  - breaker and pool state in actuator health;
  - a graceful shutdown test;
  - service discovery (Eureka, Consul, Kubernetes);
  - the JWT-bearer grant.

## Ready for production only after
Fault tests (slow or dead downstream, full pool, a 503 storm, a huge error body, a bad header), a load test (no
leaks, bounded memory and threads), a judge PASS and one pilot service (11-08).
