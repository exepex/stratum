# HTTP client starter, version 3: simple but complete (2026-11-07)

> Replaced by design-v4.md (2026-11-09).

Round 3: three architects judged round 2 (`simple-design.md`) for "too much" and "too little" and each wrote a full
version. This file merges them. Names are proposals; nothing is built. Earlier decisions kept: one Stratum block per
client (09:03Z), own pool per client (11:24Z), two starters, Resilience4j, debug as a live log level.

## What changed from round 2

| Round 2 | Version 3 | Architects |
|---|---|---|
| no pool keys | `max-connections` (default 50), `shared-pool` | 3 of 3 restored |
| mock clients missing | `mock: true` + answer files, WARN at every startup | 3 of 3 |
| trace switch dropped | `trace-headers: false` for vendors that must not get internal IDs | 2 of 3 |
| `retry: N` only | `retries` (default 3, your number) + optional `retry-delay` | 2 of 3 restored back-off |
| no total limit | `total-timeout` (default 20s) caps all attempts together | production need |
| `auth` word-or-block (needs a custom converter) | one small block per auth kind, plain Spring binding, IDE hints work | 2 of 3 |
| Stratum's `@Post` + guessed parameters | Spring's own `@PostExchange`, `@PathVariable`, `@RequestBody` | 3 of 3 |
| `@StratumClientInterceptor` + `interceptors=` | only `interceptors=` on `@StratumClient`; interceptor is a plain bean | 2 of 3 |

## Full YAML

```yaml
stratum:
  http-client:
    defaults:                    # all optional; the values shown are the built-in defaults
      connect-timeout: 2s
      timeout: 5s                # per attempt
      total-timeout: 20s         # all attempts and waits together
      retries: 3                 # 0 = off, max 9; idempotent methods and brief failures only
      retry-delay: 200ms         # first wait; then doubles with jitter, max 2s
      circuit-breaker: true
      max-connections: 50        # own pool per client
      trace-headers: true        # traceparent + X-Correlation-Id

    clients:

      inventory:                 # 1. internal, plain
        url: http://inventory:8080

      orders:                    # 2. internal, load balanced, caller's token passed on
        urls: [http://orders-1:8080, http://orders-2:8080]
        auth: { token-relay: true }

      weather:                   # 3. third party, API key
        url: https://api.weather.example/v2
        auth: { api-key: ${WEATHER_API_KEY} }    # header X-Api-Key; api-key-header: X-Key to rename
        trace-headers: false

      invoices:                  # 4. service's own OAuth2 token (client credentials)
        url: http://invoice-service:8080
        auth: { client-credentials: invoices }   # name of the Spring Security registration below

      bank:                      # 5. basic auth + mTLS
        url: https://gateway.bank.example
        ssl-bundle: bank                          # Spring Boot's spring.ssl.bundle below
        auth: { basic: { username: ${BANK_USER}, password: ${BANK_PASSWORD} } }
        timeout: 20s
        retries: 0
        max-connections: 10

      partner:                   # 6. bearer token, custom headers, interceptor (linked in Java)
        url: https://api.partner.example
        auth: { bearer: ${PARTNER_TOKEN} }
        headers: { X-Client-Name: acme-shop, X-Api-Version: "2" }
        copy-headers: [X-Order-Ref]
        trace-headers: false

      payments:                  # 7. mocked while the real service is not ready
        url: http://payments:8080
        auth: { client-credentials: payments }
        mock: true               # answers from classpath:stratum-mocks/payments/<method>.json

spring:                          # Spring's own keys, written once, Stratum only points at them
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
    keystore: { certificate: file:/etc/certs/bank/client.crt, private-key: file:/etc/certs/bank/client.key }
    truststore.certificate: file:/etc/certs/bank/ca.crt
```

Startup stops on Stratum's own config mistakes: two auth kinds on one client, `url` and `urls` together,
`retries` above 9, `Authorization`/`Cookie`/`Set-Cookie`/`Proxy-Authorization`/API-key header in `headers` or
`copy-headers`, a header value with a line break, an `interceptors` class with no bean.

Fixed, no key: redirects never followed; path and query values always encoded; error mapping; gzip; a failed address
in `urls` is skipped for a short time; debug through the log level.

## Java: the interface

```java
@StratumClient(value = "orders", interceptors = OrderSigner.class)
public interface OrderClient {

  @PostExchange("/orders")
  Order createOrder(@RequestBody NewOrder order);

  @GetExchange("/orders/{id}")
  Optional<Order> findOrder(@PathVariable String id);            // 404 -> empty

  @GetExchange("/orders")
  List<Order> searchOrders(@RequestParam String status, @RequestHeader("X-Locale") String locale);
}
```
Reactive starter: same annotations, `Mono<Order>` / `Flux<Order>` return types.
A POST is retried only when the connection failed before anything was sent.

## Java: interceptor

```java
@Component
class OrderSigner implements ClientInterceptor {               // one interface for blocking and reactive
  private final Signer signer;
  OrderSigner(Signer signer) { this.signer = signer; }

  @Override
  public void beforeSend(OutgoingRequest request) {           // client, method, uri, headers, body, incoming headers
    request.header("X-Signature", signer.sign(request.body()));
    request.header("X-Request-Id", UUID.randomUUID().toString());
  }
}

@Component
class SourceHeader implements GlobalClientInterceptor {        // runs for every client
  @Override
  public void beforeSend(OutgoingRequest request) { request.header("X-Source", "billing-service"); }
}
```
Runs once per attempt, after auth, so a signature is fresh on every retry.

## Java: a client set up only in code (wins over YAML)

```java
@Bean
StratumClientCustomizer reportsClient() {
  return StratumClientCustomizer.forClient("reports", client -> client
      .url("https://reports.internal")
      .timeout(Duration.ofSeconds(30))
      .retries(1)
      .backoff(Backoff.fixed(Duration.ofMillis(500)))           // any back-off kind, Java only
      .auth(Auth.clientCredentials("billing")));
}
```
Ad-hoc call with the same settings: `stratumHttpClients.restClient("reports")`.

## Java: errors at the call site

```java
try {
  return orderClient.createOrder(newOrder);
} catch (DownstreamException failure) {        // status, client, endpoint, code, traceId, body (size-capped)
  if (failure.status() == 409) {
    OrderConflict conflict = failure.errorBody(OrderConflict.class);   // vendor's own format as a record
    return orderClient.findOrder(conflict.existingId()).orElseThrow();
  }
  throw failure;   // Stratum or problem+json bodies already read into failure.problem()
}
```
If not caught, the web starter answers our caller with Stratum's error body: timeout 504, unavailable 503,
other downstream failure 502. The vendor's body is never passed on.

## Java: test with the mock

```java
@SpringBootTest(properties = "stratum.http-client.clients.payments.mock=true")
class CheckoutServiceIntegrationTest {
  @Autowired CheckoutService checkout;
  @Autowired MockClient<PaymentClient> payments;               // stratum-test

  @Test
  void chargesOnce() {
    payments.on("createPayment").respond(new Payment("p-1", "OK"));
    checkout.checkout(cart());
    assertThat(payments.calls("createPayment")).hasSize(1);
  }

  @Test
  void slowAnswerTimesOut() {
    payments.on("createPayment").delay(Duration.ofSeconds(6)).respond(new Payment("p-2", "OK"));
    assertThatThrownBy(() -> checkout.checkout(cart())).hasFieldOrPropertyWithValue("code", "SX-HTTPC-0001");
  }
}
```
In a running app the mock answers from `stratum-mocks/payments/createPayment.json`.

## One-line use

```java
Stock stock       = inventoryClient.getStock(sku);
Order order       = orderClient.createOrder(newOrder);
Forecast forecast = weatherClient.getForecast("Pune");
Invoice invoice   = invoiceClient.createInvoice(order);
Transfer transfer = bankClient.transfer(payout);
Quote quote       = partnerClient.getQuote(request);
Payment payment   = paymentClient.createPayment(charge);   // mock now, real later, no code change
```

## Debug during an incident
Set logger `stratum.http-client.<client>` (or `stratum.http-client` for all) to DEBUG through the logging starter's
timed switch: bodies (masked) and header names are logged, no line cap, and it turns itself off after 15 minutes
unless a longer time is given.

## Every key (same keys under `defaults` and `clients.<name>`)

| Key | Default | Typical team sets it? |
|---|---|---|
| `url` / `urls` | required (one of them) | always |
| `auth` (`token-relay`, `client-credentials`, `api-key`, `api-key-header`, `bearer`, `basic`) | none | most clients |
| `ssl-bundle` | none | mTLS only |
| `connect-timeout` | 2s | rarely |
| `timeout` | 5s | slow vendors |
| `total-timeout` | 20s | rarely |
| `retries` | 3 | sometimes (0 for payments) |
| `retry-delay` | 200ms | rarely |
| `circuit-breaker` | true | rarely |
| `max-connections` | 50 | busy dependencies |
| `shared-pool` | false | rarely |
| `headers` | none | some third parties |
| `copy-headers` | none | sometimes |
| `trace-headers` | true | third parties |
| `mock` | false | dev and tests |

15 keys. Internal client: 1 to 2. Third party: 3 to 4. Java only: interceptors, custom back-off, error mapper.

## Not verified
- Registering each `@StratumClient` as its own Spring HTTP service group through `AbstractHttpServiceRegistrar`.
- One `ClientInterceptor` adapted to both `ClientHttpRequestInterceptor` and `ExchangeFilterFunction`.
- Token relay from the security context; Spring Security's manager for client credentials without a user.
- Per-client Apache HttpClient 5 and Reactor Netty pools combining with Spring's group settings.
- That the logging starter's timed DEBUG switch exists as planned (logging PR 4 is not built).

## Generated and foreign clients (proposal, 2026-11-08)
1. Generated interface with Spring annotations: `clients.<name>.interface: com.partner.OrdersApi` (no `@StratumClient` needed); full Stratum behaviour.
2. Generated client that accepts a client: pass `stratumHttpClients.restClient("<name>")` / `webClient("<name>")`.
3. Own engine (Finagle, vendor SDK): `stratumHttpClients.settings("<name>")` for url/timeouts and
   `stratumHttpClients.resilience("<name>").call(...)` for retry + breaker; auth/headers stay the SDK's.
Startup checks only Stratum's own blocks; unused blocks allowed. Cases 2-3 are a documented recipe.

## Production-readiness gaps (2026-11-08, MVC starter)
Add as fixed defaults: connection max age ~5m + idle eviction; response size cap ~10 MB; streaming bodies;
MVC pool metrics. Ready only after: fault tests (slow/dead downstream, full pool, 503 storm, huge body, bad header),
load test (no leaks, bounded memory/threads), judge PASS, one pilot service.
- Decided 2026-11-08 23:44Z (Aashish): connection lifetime + idle connections released, yes; streaming for all
  uploads/downloads, yes; per-client pool metrics (max, busy, idle, waiting), yes; response size cap PARKED as an
  optional per-endpoint feature, issue #236. Open: "core pool" (Apache HC5 has no minimum pool size; see thread).
- Decided 2026-11-08 23:48Z (Aashish): no core pool size; `max-connections` (50) and `idle-timeout` (30s), both per
  client and under defaults. Pool metrics per client: max, busy, idle, waiting, plus (agreed 23:49Z) time waiting for a
  connection and count of calls refused because the pool was full. Proposed: Micrometer's own Apache HC5 pool binder
  names (not verified), tagged with the client name.
