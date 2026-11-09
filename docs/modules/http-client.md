# HTTP client: `stratum-starter-http-client`

**Status: Partly built.** Calls to other services through Java interfaces marked `@StratumClient`. Each client
gets its own address, timeouts and bounded connection pool from one settings block, and Apache HttpClient 5 sends
its calls. Tokens, retries and the circuit breaker, headers, error mapping, the call log and pool metrics are
planned ([Planned](#planned)).

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
| Timeouts | A connection must open within `connect-timeout` (2s). The first byte of an answer, and each byte after it, must come within `timeout` (5s). A slow answer whose bytes keep coming is not cut off; a limit on the whole call comes with the planned `total-timeout`. |
| A pool per client | At most `max-connections` (50) connections. A call that finds them all busy waits at most `max-wait` (2s), then fails. A slow service fills only its own client's pool. |
| No silent resends | Apache HttpClient 5's own retries are off, so a call is never sent twice behind your back. Retries come with the planned resilience settings. |
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
| `timeout` | duration | `5s` | no such key | The longest wait for the first byte of an answer and between two of its bytes. It does not limit the whole call. |
| `max-connections` | int | `50` | no such key | The most connections the client keeps open at once, from 1 to 10000. |
| `max-wait` | duration | `2s` | no such key | How long a call waits for a free connection before it fails. |
| `idle-timeout` | duration | `30s` | no such key | How long a connection may stay unused before it is closed. |
| `connection-lifetime` | duration | `5m` | no such key | How old a connection may get before it is closed instead of used again. |
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

## Streaming

A client streams a download when its method returns `InputStream` or `ResponseEntity<InputStream>`. The method
returns once the answer's headers arrive, and the body comes in as you read it. Every other return type, `byte[]` and
`Resource` included, reads the whole body into memory first. `ResponseEntity<InputStreamResource>` is not supported
by Spring as a return type.

A client streams an upload when its `@RequestBody` is a `StreamingHttpOutputMessage.Body` or an `InputStreamResource`.
The body is sent in chunks as it is written, never held in memory.

```java
@StratumClient("documents")
public interface DocumentsClient {

  @GetExchange("/reports/{id}")
  InputStream report(@PathVariable String id);

  @PostExchange("/archive")
  void archive(@RequestBody StreamingHttpOutputMessage.Body document);
}

try (InputStream report = documents.report("2026-q3")) {
  report.transferTo(file);
}
documents.archive(out -> Files.copy(path, out));
```

Close every download, as `try-with-resources` does above. An open download holds one of the client's connections until
it is closed; when all of them are held, the next call fails after `max-wait`. Closing a download before its end reads
the rest of it first, so that the connection can be used again: the thread and the connection stay busy until the
download ends, and a pause in it longer than `timeout` ends the close with `SocketTimeoutException`.

`timeout` is the longest wait for the next byte, not for the whole download, so a large file that keeps arriving is
read in full. A download that stops for longer than `timeout` fails with `SocketTimeoutException` as you read it.

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
- resilience: `retries`, `retry-delay`, `total-timeout` and the circuit breaker;
- `headers` and `copy-headers`;
- error mapping to `DownstreamException` and the `SX-HTTPC-0001` to `0004` codes;
- one log line per call and the pool metrics;
- `mock: true` and `urls`;
- `stratumHttpClients.resilience(name)`, the retry and circuit breaker for a call through a client Stratum did not
  build;
- `ClientInterceptor` and `GlobalClientInterceptor`;
- the reactive starter, `stratum-starter-reactive-http-client`, on `WebClient`.

## Troubleshooting

| Symptom | Likely cause and fix |
| --- | --- |
| Startup fails with `SX-HTTPC-1004` | The client has no `url`, or the name in the settings differs from the one in `@StratumClient`. |
| No bean of your client interface | The interface is outside your application's packages, or `stratum.http-client.enabled=false`. |
| Calls fail after exactly 5 s | The default `timeout`. Raise it for that client if the other service is slow for a reason. |
| Calls fail after 2 s with a connection request timeout | The client's pool is full: raise `max-connections`, or find what holds its connections. |
