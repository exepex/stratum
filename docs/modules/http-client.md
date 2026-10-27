# HTTP client: `stratum-starter-http-client`

**Status: Planned.** Calls to other services through declarative Java interfaces, with timeouts,
tracing, correlation IDs, tokens, logging and resilience set up for you.

[← Documentation home](../README.md) · Property prefix: `stratum.http-client`

## What it does

The most common production incident in microservices is a call that waits forever on a slow
downstream service. Close behind are lost trace headers and missing tokens. This starter makes the
safe setup the default: you describe the remote API as an interface, give its URL, and every call
is protected.

It builds on Spring Framework's **HTTP interface clients** and Spring Boot's HTTP service client
groups, backed by `RestClient`.

## Add it

```xml
<dependency>
  <groupId>com.example.stratum</groupId>
  <artifactId>stratum-starter-http-client</artifactId>
</dependency>
```

## Use it in three steps

**1. Describe the remote API:**

```java
public interface InventoryClient {

  @GetExchange("/stock/{sku}")
  Stock stockFor(@PathVariable String sku);

  @PostExchange("/reservations")
  Reservation reserve(@RequestBody ReservationRequest request);

  record Stock(String sku, int available) {}
}
```

**2. Register it in a named group:**

```java
@Configuration
@ImportHttpServices(group = "inventory", types = InventoryClient.class)
class HttpClientsConfig {}
```

**3. Configure the group by the same name:**

```yaml
stratum:
  http-client:
    clients:
      inventory:
        base-url: https://inventory.internal
```

```properties
stratum.http-client.clients.inventory.base-url=https://inventory.internal
```

Inject `InventoryClient` anywhere and call its methods like any Java object.

## What you get on every call

| Feature | Behaviour |
| --- | --- |
| Timeouts | Connect `2s` and read `5s` unless you set others; no call can hang forever |
| Tracing | `traceparent` header sent; a client span and `http.client.requests` metric per call |
| Correlation ID | The current correlation ID is sent in `X-Correlation-Id` |
| Tokens | Client credentials or token relay, per client (see [OAuth2 client](oauth2-client.md)) |
| Error mapping | `4xx` and `5xx` answers become typed exceptions; timeouts and connection failures become `DownstreamUnavailableException`, which the web starter turns into `503`/`504` |
| Logging | One `DEBUG` line per call (method, URL without query, status, duration); bodies never logged unless you ask |
| Resilience | Retry and circuit breaker per client when `stratum-starter-resilience` is present |
| Connection pooling | Pooled, bounded connections per client |

### Errors your code can catch

| Exception | When |
| --- | --- |
| `HttpClientErrorException` (Spring), e.g. `.NotFound`, `.Conflict` | The downstream answered `4xx` |
| `HttpServerErrorException` (Spring) | The downstream answered `5xx` |
| `DownstreamUnavailableException` (Stratum) | Timeout, connection refused, DNS failure, or circuit breaker open |

```java
try {
  return inventory.stockFor(sku);
} catch (HttpClientErrorException.NotFound e) {
  throw new UnknownProductException(sku);
}
```

Let the rest propagate: the web starter turns them into the right Problem Details response.

## Properties

### Defaults for every client

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `stratum.http-client.enabled` | boolean | `true` | Switches the whole starter on or off. |
| `stratum.http-client.defaults.connect-timeout` | duration | `2s` | Time allowed to open a connection. |
| `stratum.http-client.defaults.read-timeout` | duration | `5s` | Time allowed to wait for the response. |
| `stratum.http-client.defaults.max-connections` | int | `50` | Connection pool size per client. |
| `stratum.http-client.defaults.propagate-headers` | list | `X-Correlation-Id` | Incoming headers copied onto outgoing calls. |
| `stratum.http-client.defaults.log-bodies` | boolean | `false` | Logs request and response bodies at `DEBUG` (redacted). Never in production. |
| `stratum.http-client.defaults.resilience-policy` | string | `default` | Resilience policy used when a client sets none. |

### Per client: `stratum.http-client.clients.<name>.*`

`<name>` is the group name from `@ImportHttpServices(group = ...)`.

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `base-url` | URL | **required** | Address of the downstream service. |
| `connect-timeout` | duration | from `defaults` | Overrides the default for this client. |
| `read-timeout` | duration | from `defaults` | Overrides the default for this client. |
| `max-connections` | int | from `defaults` | Overrides the default for this client. |
| `headers` | map | *(empty)* | Fixed headers sent on every call. Not for secrets. |
| `oauth2-registration` | string | *(none)* | Sends a client credentials token from this registration. |
| `token-relay` | boolean | `false` | Sends the incoming request's token on. |
| `resilience-policy` | string | from `defaults` | Name of a policy under `stratum.resilience.policies`; `none` to switch off. |

## Examples

Two downstream services, one slow and secured:

```yaml
stratum:
  http-client:
    defaults:
      read-timeout: 3s
    clients:
      inventory:
        base-url: https://inventory.internal
      payments:
        base-url: https://payments.internal
        read-timeout: 10s
        oauth2-registration: payments
        resilience-policy: payments
        headers:
          X-Api-Version: "2"
```

```properties
stratum.http-client.defaults.read-timeout=3s
stratum.http-client.clients.inventory.base-url=https://inventory.internal
stratum.http-client.clients.payments.base-url=https://payments.internal
stratum.http-client.clients.payments.read-timeout=10s
stratum.http-client.clients.payments.oauth2-registration=payments
stratum.http-client.clients.payments.resilience-policy=payments
stratum.http-client.clients.payments.headers.X-Api-Version=2
```

> [!TIP]
> Pick timeouts from the downstream's real latency: a read timeout a little above its p99 latency
> is a good start. Then make sure your own callers' timeouts are longer than yours.

## Configure in Java

**Planned.** You will also be able to set every property on this page in Java code, through this module's
customizer or builder, with the same names and defaults. This section will show how once that API is designed.

## Customize

- **All clients:** declare a `RestClientCustomizer` bean.
- **One group:** declare a `StratumHttpClientCustomizer` bean and check the group name:

  ```java
  @Bean
  StratumHttpClientCustomizer paymentsSigning(RequestSigner signer) {
    return (group, builder) -> {
      if (group.equals("payments")) {
        builder.requestInterceptor(signer);
      }
    };
  }
  ```

- **Plain `RestClient` without an interface:** inject `RestClient.Builder`. It carries Stratum's
  tracing and correlation settings; set the base URL and timeouts yourself.

## Switch it off

`stratum.http-client.enabled=false`. Spring Boot's own HTTP client support stays, without Stratum's
timeouts, headers or error mapping.

## Troubleshooting

| Symptom | Likely cause and fix |
| --- | --- |
| Startup fails: `base-url must not be null` | The client name in properties does not match the `group` name in `@ImportHttpServices`. |
| `DownstreamUnavailableException` after exactly 5 s | The default read timeout. Raise `read-timeout` for that client if the downstream is legitimately slow. |
| No `Authorization` header on calls | Neither `oauth2-registration` nor `token-relay` is set for the client, or the OAuth2 client starter is missing. |
| Trace is broken at this call | The call was made with a `RestClient` built by `RestClient.create()` instead of the injected builder. |
