# HTTP client: `stratum-starter-http-client`

**Status: Partly built.** Calls to other services through Java interfaces marked `@StratumClient`. Each client
gets its own address, timeouts and bounded connection pool from one settings block, and Apache HttpClient 5 sends
its calls, with retries, a time limit for the whole call and a circuit breaker. Tokens, headers, error mapping, the
call log and pool metrics are planned ([Planned](#planned)).

[← Documentation home](../README.md) · Property prefix: `stratum.http-client` ·
Design: [ADR 0011](../adr/0011-http-client-starters.md), [ADR 0012](../adr/0012-http-client-changes-found-while-building.md)

## What it does

The most common production incident in microservices is a call that waits too long on a slow service. Close behind
are pools that grow without bound and connections that a load balancer already closed. This starter makes the safe
setup the default: you describe the other service's API as an interface, give its address, and every call has a
timeout, a bounded pool and a wait for a free connection.

It builds on Spring's **HTTP interface clients**: you write an interface with Spring's own `@GetExchange`,
`@PostExchange`, `@PathVariable` and `@RequestBody`, and Spring makes the client. Stratum finds the interface, gives
it the settings of its own block and sends its calls through Apache HttpClient 5, which, unlike Java's own client,
has a connection pool per client.

## Add it

```xml
<dependency>
  <groupId>com.exepex.stratum</groupId>
  <artifactId>stratum-starter-http-client</artifactId>
</dependency>
```

The starter is on as soon as it is on the classpath. To turn it off, set in `application.properties`:

```properties
stratum.http-client.enabled=false
```

or in `application.yml`:

```yaml
stratum:
  http-client:
    enabled: false
```

When it is off, Stratum finds no `@StratumClient` interface, makes no client and changes no Spring Boot setting.

The `stratum-starter-microservice` bundle includes it. The reactive bundle does not, until the reactive HTTP client
starter is built ([Bundles](bundles.md)).

## Use it

**1. Describe the other service's API** as an interface in your application's packages, the ones Spring Boot scans
from your `@SpringBootApplication` class:

```java
@StratumClient("inventory")
public interface InventoryClient {

  @GetExchange("/stock/{sku}")
  Stock stockFor(@PathVariable String sku);

  @PostExchange("/reservations")
  Reservation reserve(@RequestBody ReservationRequest request);
}
```

The name in `@StratumClient` is the client's name in the settings: lowercase letters, digits and hyphens, starting
with a letter, such as `inventory` or `payments-v2`. Two interfaces with the same name are one client: they share
its settings and its pool.

**2. Give the client its address:**

```properties
stratum.http-client.clients.inventory.url=http://inventory:8080
```

```yaml
stratum:
  http-client:
    clients:
      inventory:
        url: http://inventory:8080
```

**3. Inject the interface** anywhere and call its methods like any Java object:

```java
@Service
@RequiredArgsConstructor
class ReservationService {

  private final InventoryClient inventory;

  boolean inStock(String sku) {
    return inventory.stockFor(sku).available() > 0;
  }
}
```

A method returns the value itself, such as `Stock`. A method that returns `Mono` or `Flux` stops startup with
`SX-HTTPC-1002`: this starter is blocking, and the reactive one is planned.

Do not put personal data, such as an e-mail address or a customer name, in a path or query value: addresses end up
in proxy and server logs that Stratum does not control. Send it in the request body instead.

## What every client gets

| What | Behaviour |
| --- | --- |
| Timeouts | A connection must open within `connect-timeout` (2s) and the answer to each call must come within `timeout` (5s). |
| A pool per client | At most `max-connections` (50) connections. A call that finds them all busy waits at most `max-wait` (2s), then fails. A slow service fills only its own client's pool. |
| Safe retries | A call is sent again only when that is safe ([Retries and the circuit breaker](#retries-and-the-circuit-breaker)). Apache HttpClient 5's own retries are off. |
| A time limit per call | The whole call, retries and waits included, ends within `total-timeout` (8s). |
| A circuit breaker | When most recent calls fail, the client stops calling the service for a while and fails at once. |
| No redirects | A `3xx` answer is returned as it is and never followed. |
| Gzip | Answers compressed with gzip are accepted and unpacked. |
| Fresh connections | A connection unused for `idle-timeout` (30s) is closed. One unused for more than about 2 seconds is checked before it is used again, so one the server or a load balancer closed meanwhile is replaced. A server's `Keep-Alive: timeout` is honoured. A connection older than `connection-lifetime` (5m) is not used again, so calls follow a change of the service's address. |
| Nothing at startup | Startup opens no connection, so the application starts and is ready while a downstream service is down. |
| Clean shutdown | Every pool closes its connections when the application stops. |

A call runs on the thread that makes it, so the logging and tracing context stays with it.

## Properties

Every setting can be set for all clients under `stratum.http-client.defaults.*`, and for one client under
`stratum.http-client.clients.<name>.*`, where `<name>` is the name in `@StratumClient`. A client's own value wins over
the shared default, which wins over Stratum's.

| Property | Type | Stratum default | Spring Boot default | Description |
| --- | --- | --- | --- | --- |
| `stratum.http-client.enabled` | boolean | `true` | no such key | Whether the starter is on. With `false`, it sets up nothing. |
| `url` | URL | **required** per client | no such key | The address of the other service, such as `http://inventory:8080`. Not allowed under `defaults`, since each client calls its own service. |
| `connect-timeout` | duration | `2s` | no such key | The time allowed to open a connection. |
| `timeout` | duration | `5s` | no such key | The time allowed for the answer to one call. |
| `max-connections` | int | `50` | no such key | The most connections the client keeps open at once, from 1 to 10000. |
| `max-wait` | duration | `2s` | no such key | How long a call waits for a free connection before it fails. |
| `idle-timeout` | duration | `30s` | no such key | How long a connection may stay unused before it is closed. |
| `connection-lifetime` | duration | `5m` | no such key | How old a connection may get before it is closed instead of used again. |
| `retries` | int | `3` | no such key | How many times a failed call may be sent again, from 0 to 9. `0` sends each call once. |
| `retry-delay` | duration | `200ms` | no such key | The wait before the first retry. Each later wait doubles, spread at random by up to half, and is at most 2s, or `retry-delay` when that is longer. |
| `total-timeout` | duration | `8s` | no such key | The time allowed for a whole call: every attempt, its wait for a connection and the waits between attempts. |
| `circuit-breaker` | boolean | `true` | no such key | Whether the client has a circuit breaker. |
| `shared-pool` | boolean | `false` | no such key | With `true`, the client uses one pool shared by every client that sets it, instead of its own. That pool takes its size, connect timeout, idle timeout and lifetime from `defaults`; the client keeps its own `timeout` and `max-wait`. |
| `interfaces` | list of interface names | none | no such key | Generated interfaces, by full name such as `com.partner.OrdersApi`, that call this client's service with all its settings, as a `@StratumClient` interface does ([Clients Stratum did not build](#clients-stratum-did-not-build)). Not allowed under `defaults`. |
| `auth.api-key` | text | unset | no such key | An API key sent on every call of the client, from a placeholder such as `${GEO_API_KEY}`. Not allowed under `defaults`, like every `auth` key ([Auth and mTLS](http-client-auth.md)). |
| `auth.api-key-header` | text | `X-Api-Key` | no such key | The header that carries the API key. Only with `auth.api-key`. |
| `auth.bearer` | text | unset | no such key | A fixed token sent as `Authorization: Bearer` on every call. |
| `auth.basic.username`, `auth.basic.password` | text | unset | no such key | Credentials sent with HTTP Basic authentication on every call. Both are required. |
| `ssl-bundle` | text | unset | no such key | The name of a `spring.ssl.bundle.*` bundle whose certificate the client presents (mutual TLS). Not allowed under `defaults`, nor with `shared-pool`. |
| `spring.http.clients.imperative.factory` | `http-components`, `jetty`, `reactor`, `jdk` or `simple` | `jdk` | unset: detects the engine, Apache HttpClient 5 first | The engine of the RestClients Stratum did not build. Stratum's own clients always use Apache HttpClient 5 ([Clients Stratum did not build](#clients-stratum-did-not-build)). |

Every duration must be above 0, and `auth` holds exactly one kind. A setting that breaks a rule stops startup with
`SX-CORE-1003`, naming the rule, never the value.

Spring Boot's own settings of an HTTP service group, `spring.http.serviceclient.<name>.*`, do not apply to a Stratum
client: Stratum builds its client. Setting one for a Stratum client's name stops startup with `SX-HTTPC-1001`, naming
the Stratum key to use instead. This is the one exception to Stratum's rule of using Spring Boot's keys where they
exist ([ADR 0011](../adr/0011-http-client-starters.md), decision 2).

## Examples

A fast service with the defaults, and a slow report service with a longer timeout and a smaller pool:

```properties
stratum.http-client.defaults.timeout=3s
stratum.http-client.clients.inventory.url=http://inventory:8080
stratum.http-client.clients.reports.url=http://reports:8080
stratum.http-client.clients.reports.timeout=20s
stratum.http-client.clients.reports.max-connections=10
```

```yaml
stratum:
  http-client:
    defaults:
      timeout: 3s
    clients:
      inventory:
        url: http://inventory:8080
      reports:
        url: http://reports:8080
        timeout: 20s
        max-connections: 10
```

A payments service whose calls must never be sent twice, with a breaker of its own off, and more time for the
reports service's whole call:

```properties
stratum.http-client.clients.payments.retries=0
stratum.http-client.clients.payments.circuit-breaker=false
stratum.http-client.clients.reports.total-timeout=30s
```

```yaml
stratum:
  http-client:
    clients:
      payments:
        retries: 0
        circuit-breaker: false
      reports:
        total-timeout: 30s
```

Behind a load balancer that drops connections unused for 20 seconds, close them first:

```properties
stratum.http-client.defaults.idle-timeout=15s
```

```yaml
stratum:
  http-client:
    defaults:
      idle-timeout: 15s
```

> [!TIP]
> Pick a timeout from the other service's real latency: a little above its p99 is a good start. Then make sure the
> timeouts of your own callers are longer than yours.

## Retries and the circuit breaker

Every call of a client's interface runs through the client's retries, and each attempt through its circuit breaker.
The rules are fixed, and safe for any service:

| A failed attempt | Sent again? |
| --- | --- |
| The connection could not be opened (refused, connect timeout, unknown host) | Yes, for every method: nothing was sent. |
| A `502`, `503`, `504` or `429` answer | Only a `GET`, `HEAD`, `OPTIONS`, `PUT` or `DELETE`. |
| No answer: a read timeout or a dropped connection | Only a `GET`, `HEAD`, `OPTIONS`, `PUT` or `DELETE`. |
| The client's pool is full | Never: more calls would add to the load. |
| A request body streamed from a `Resource` | Never: it cannot be sent twice. |
| Any other answer, such as `500` or `404` | Never. |

`POST`, `PATCH` and any other method are sent again only when the connection could not be opened.

A `503` or `429` with a `Retry-After` of whole seconds or an HTTP date waits that long instead of the back-off. A
call is sent again only while the wait still leaves at least 100ms before `total-timeout`; otherwise the last answer
or failure is the call's result. Each attempt's connection wait, connect timeout and `timeout` are cut to the time
left. A method that returns an `InputStream`, a streamed download, keeps its full `timeout`: `total-timeout` bounds
it only until its answer's headers arrive, and reading the stream after that is bounded only by `timeout` between
bytes.

The circuit breaker counts an attempt with no answer, a full pool included, and an answer of `5xx` or `429`. Other
`4xx` answers mean the service is up, so they never count. With Resilience4j's defaults, it opens once at least 100
calls were seen and half of the last 100 failed. Then for 60 seconds every call fails at once, without being sent, with
Resilience4j's `CallNotPermittedException`; the planned error mapping turns it into a `503`. After that, 10 trial
calls decide whether it closes. Each change of its state is logged once at `WARN`, such as `The circuit breaker of
HTTP client inventory went from CLOSED to OPEN`.

A service with stricter needs changes the rules of one client in its `StratumClientCustomizer`, through Resilience4j's
builders. Stratum's rules are already set on them:

```java
@Bean
StratumClientCustomizer paymentsResilience() {
  return new StratumClientCustomizer() {
    @Override
    public void customize(String client, RestClient.Builder builder) {
      // The client itself stays as Stratum sets it.
    }

    @Override
    public void customizeRetry(String client, RetryConfig.Builder<ClientAttempt> retry) {
      if ("payments".equals(client)) {
        retry.maxAttempts(1);
      }
    }

    @Override
    public void customizeCircuitBreaker(String client, CircuitBreakerConfig.Builder breaker) {
      if ("payments".equals(client)) {
        breaker.slidingWindowSize(20).minimumNumberOfCalls(20);
      }
    }
  };
}
```

A rule given to `retry.retryOnResult` decides on each `ClientAttempt`: its method, its failure, the time left and
the next wait.

## Configure in Java

Every `stratum.http-client.*` setting can also be set in Java, through an `HttpClientPropertiesCustomizer` class, and
Java wins over the files:

```java
public class ReportClientSettings implements HttpClientPropertiesCustomizer {

  @Override
  public HttpClientProperties customize(HttpClientProperties properties) {
    var reports =
        properties.clientSettings("reports").toBuilder()
            .timeout(Duration.ofSeconds(20))
            .maxConnections(10)
            .build();
    var clients = new HashMap<>(properties.clients());
    clients.put("reports", reports);
    return properties.toBuilder().clients(clients).build();
  }
}
```

The customizer is not a bean: list the class in the service's `META-INF/spring.factories`. A customizer declared as
a bean stops startup with `SX-CORE-1004`.

```properties
com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer=com.acme.orders.ReportClientSettings
```

## Customize

For what has no setting, such as a request interceptor or a message converter, declare a `StratumClientCustomizer`
bean. Spring calls it once per client at startup, after Stratum has set the client's address and pool, with the
client's name and its `RestClient.Builder`:

```java
@Bean
StratumClientCustomizer inventoryTenantHeader() {
  return (client, builder) -> {
    if ("inventory".equals(client)) {
      builder.defaultHeader("X-Tenant", "retail");
    }
  };
}
```

Do not put secrets in a header this way: use the [`auth` settings](http-client-auth.md).

Spring Boot's `RestClientCustomizer` beans also apply to Stratum's clients, before Stratum's settings; a request
factory one of them sets is replaced by Stratum's.

To change how Stratum builds a client, declare a `StratumClientCustomizer`, as above. A `StratumHttpClients` bean of
your own replaces Stratum's ([Clients Stratum did not build](#clients-stratum-did-not-build)). Stratum's other beans
for the clients are internal: their types may change in any release, so they are not a way to customize.

## Clients Stratum did not build

Stratum never changes a client it did not build. A `RestClient` you build from Spring Boot's `RestClient.Builder`,
or a group you register with `@ImportHttpServices`, keeps Spring Boot's behaviour, with no Stratum timeout or pool.

This starter puts Apache HttpClient 5 on the classpath, which Spring Boot would otherwise pick first for those
clients. So that they keep Java's own client, as without Stratum, Stratum gives
`spring.http.clients.imperative.factory` the default `jdk`. Spring Boot's own default is to detect the engine, with
Apache HttpClient 5 first. Your value wins:

```properties
spring.http.clients.imperative.factory=http-components
```

```yaml
spring:
  http:
    clients:
      imperative:
        factory: http-components
```

Teams also call other services through clients they did not write: an interface an OpenAPI generator wrote, a
generated client that takes a `RestClient`, or a vendor's SDK with its own engine. Each can join a Stratum client's
settings, and nothing joins unless you ask.

**A generated interface** with Spring's `@GetExchange` and `@PostExchange`, but no `@StratumClient`, joins a client
through the client's `interfaces` setting. It then gets everything a `@StratumClient` interface gets, and you inject
it the same way:

```properties
stratum.http-client.clients.partner.url=https://orders.partner.example
stratum.http-client.clients.partner.interfaces[0]=com.partner.OrdersApi
stratum.http-client.clients.partner.interfaces[1]=com.partner.CustomersApi
```

```yaml
stratum:
  http-client:
    clients:
      partner:
        url: https://orders.partner.example
        interfaces:
          - com.partner.OrdersApi
          - com.partner.CustomersApi
```

A name that is not an interface on the classpath stops startup with `SX-HTTPC-1007`; an interface marked
`@StratumClient` for another client stops it with `SX-HTTPC-1008`. A generated interface cannot carry
`@StratumClient(interceptors = ...)`, so the planned per-client interceptors do not reach it; a
`StratumClientCustomizer` does, by the client's name.

**A generated client that takes a `RestClient`** gets the client's own one from the `StratumHttpClients` bean, with
its address, timeouts, pool and your customizers. It shares the pool with the client's interfaces:

```java
@Bean
PartnerOrdersClient partnerOrders(StratumHttpClients stratumHttpClients) {
  // PartnerOrdersClient stands for the generated class; its constructor takes a RestClient.
  return new PartnerOrdersClient(stratumHttpClients.restClient("partner"));
}
```

That `RestClient` sends each call once. It does not retry, because retrying inside a `RestClient` would make Spring
hold every request body in memory. Wrapping a call in the planned `stratumHttpClients.resilience("partner")` will
give it the client's retry and circuit breaker. A name with no `url` fails at the first `restClient` call, with
`SX-HTTPC-1004`.

**A client with its own engine,** such as a vendor SDK, reads the client's settings, its own merged with
`defaults`, from `stratumHttpClients.settings("partner")`, for example `settings.url()` and `settings.timeout()`.

## How it is wired

Stratum's auto-configuration runs before Spring Boot's HTTP service client auto-configuration, because Spring Boot
sets up groups only when their registry exists, and Stratum's registration of the clients creates it. At startup,
Spring Boot first gives every group its own request factory; Stratum's set-up then replaces it, for Stratum's groups
only, with the client's Apache HttpClient 5 pool.

## Startup errors

| Code | When |
| --- | --- |
| `SX-HTTPC-1001` | A `spring.http.serviceclient.<name>.*` key is set for a Stratum client. |
| `SX-HTTPC-1002` | A client method returns `Mono` or `Flux`. |
| `SX-HTTPC-1003` | A client name is not lowercase letters, digits and hyphens starting with a letter. |
| `SX-HTTPC-1004` | A client has no `url`. |
| `SX-HTTPC-1007` | A client's `interfaces` names a type that is not an interface on the classpath. |
| `SX-HTTPC-1008` | An interface marked `@StratumClient` for one client is named in another client's `interfaces`. |

Each one, with its fix, is in [Error codes](../guides/error-codes.md#stratum-starter-http-client).

## Planned

These parts of [ADR 0011](../adr/0011-http-client-starters.md) are not built yet, and none of their keys is read:

- tokens: `auth.client-credentials` and `auth.token-relay`;
- `headers` and `copy-headers`;
- error mapping to `DownstreamException` and the `SX-HTTPC-0001` to `0004` codes;
- one log line per call and the pool metrics;
- streaming uploads and downloads, `mock: true` and `urls`;
- `stratumHttpClients.resilience(name)`, the retry and circuit breaker for a call through a client Stratum did not
  build;
- `ClientInterceptor` and `GlobalClientInterceptor`;
- the reactive starter, `stratum-starter-reactive-http-client`, on `WebClient`.

## Troubleshooting

| Symptom | Likely cause and fix |
| --- | --- |
| Startup fails with `SX-HTTPC-1004` | The client has no `url`, or the name in the settings differs from the one in `@StratumClient`. |
| No bean of your client interface | The interface is outside your application's packages, or `stratum.http-client.enabled=false`. |
| Calls fail after exactly 5 s | The default `timeout`, for a call that is not sent again, such as a `POST`. Raise it for that client if the other service is slow for a reason. |
| `GET` calls fail after about 8 s | The default `timeout` of 5 s ran out, and the retry ran out of `total-timeout`. Raise both for that client if the other service is slow for a reason. |
| Calls fail at once with `CallNotPermittedException` | The client's circuit breaker is open: most recent calls to the service failed. Look at the service, and at the `WARN` line that names the client. |
| Calls fail after 2 s with a connection request timeout | The client's pool is full: raise `max-connections`, or find what holds its connections. |
