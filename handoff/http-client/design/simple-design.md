# Simplified HTTP client design (round 2, 2026-11-07)

Three architects surveyed JVM (Spring, Micronaut, Quarkus/MicroProfile, Helidon, Ktor, Retrofit/OkHttp, Armeria,
Finagle), .NET/Go/Rust (IHttpClientFactory + standard resilience handler, Aspire, Refit, Flurl, resty,
go-retryablehttp, reqwest-middleware, tower) and JS/Python/mesh (axios, ky, got, NestJS http-client, httpx,
urllib3 Retry, Envoy, Istio, Linkerd, gRPC service config, Dapr). Names below are proposals; nothing is built.

## The one idea all three agree on
Every setting has a production-safe default (like .NET's standard resilience handler and Istio's mesh defaults).
A team sets only what is different. A typical client block has 1 to 3 keys.

## What a team writes

```yaml
stratum:
  http-client:
    defaults:                  # optional; these are the built-in values
      connect-timeout: 2s
      timeout: 5s              # per attempt
      retry: 2                 # extra attempts; 0 = off; max 9
      circuit-breaker: true
    clients:
      inventory:                         # internal service
        url: http://inventory:8080
        auth: token-relay
      orders:                            # internal, load balanced over several addresses
        urls: [http://orders-1:8080, http://orders-2:8080]
        auth: client-credentials         # uses spring.security.oauth2.client.registration.orders
      weather:                           # third party with an API key
        url: https://api.weather.example/v2
        auth: { api-key: ${WEATHER_API_KEY} }   # header X-Api-Key unless api-key-header is set
      bank:                              # third party with mTLS and basic
        url: https://gateway.bank.example
        ssl-bundle: bank                 # Spring Boot's own spring.ssl.bundle.*
        auth: { basic: { username: ${BANK_USER}, password: ${BANK_PASSWORD} } }
        timeout: 20s
```

```java
@StratumClient(value = "orders", interceptors = OrderSigner.class)
public interface OrderClient {
  @Post("/orders") Order createOrder(NewOrder order);
}

@StratumClientInterceptor
class OrderSigner implements ClientInterceptor {
  public void beforeSend(OutgoingRequest request) {
    request.header("X-Signature", signer.sign(request.body()));
  }
}
```
Use: `Order order = orderClient.createOrder(newOrder);`

Key count: internal 1-2, third party 2-3, OAuth2 2 (+ Spring Security's registration keys the team writes anyway).
Nothing special: `url` only.

## What changed from the earlier list, and why it stays production grade

| Before | Now | Why |
|---|---|---|
| `base-url` + `lb://name` + `addresses` | `url` or `urls: [...]` (list = load balanced) | One idea instead of two (Micronaut) |
| `auth.type` + fields | `auth: token-relay`, `auth: client-credentials`, `auth: { api-key: ... }`, `auth: { basic: ... }`, `auth: { bearer: ... }` | The type is the word you write; two types at once stops startup |
| `auth.type: mtls` | `ssl-bundle: <name>` | mTLS is transport, and Boot already owns SSL bundles |
| OAuth2 registration name | defaults to the client name | One less key (Quarkus) |
| retry block (max, backoff kind, delays) | `retry: <number>` | Fixed safe policy: idempotent methods (GET, HEAD, OPTIONS, PUT, DELETE), connect failures, 502/503/504, 429/503 with `Retry-After`, exponential backoff with jitter. Custom backoff only in Java |
| circuit-breaker block | `circuit-breaker: true/false` | Tuning through Resilience4j's own `resilience4j.circuitbreaker.instances.<client>` keys |
| `pool.*`, `pool.shared` | no keys | Own bounded pool per client always; shared pool only from Java |
| `read-timeout` | `timeout` (per attempt) | Connect timeout stays a default |
| `send-trace-context` | dropped (2 of 3) | Trace IDs carry no personal data; add a switch when a real vendor needs it |
| redirects, error mapping, debug keys | no keys | Redirects always off; errors always mapped; debug is a log level |

Unchanged: `headers` (fixed) and `copy-headers` (names) stay optional; interceptors linked by class from the
interface; tokens only through `auth`; POST retried only with `@Retryable` on that method.

## Not settled / not verified
- Binding `auth` as either a word or a block needs a small converter in Spring Boot's binder (not verified).
- Two architects again advise Spring's own `@PostExchange`, `@PathVariable`, `@RequestBody` instead of Stratum's
  `@Post` with inferred parameters: explicit, documented, known to IDEs; inference is ambiguous for a GET with a
  query object.
- Retry budget (retries capped at ~20% of calls, Envoy/Istio/Finagle): proposed default, not in Resilience4j; needs go.
- Total deadline across retries: one architect wants a `total-timeout`; worst case without it is about
  (retry + 1) x timeout.
