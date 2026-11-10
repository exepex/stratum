# HTTP client: `stratum-starter-http-client`

**Status: Partly built.** Calls to other services through Java interfaces marked `@StratumClient`. Each client
gets its own address, timeouts and bounded connection pool from one settings block, and Apache HttpClient 5 sends
its calls, with retries, a time limit for the whole call, a circuit breaker, its credential or token, its
[headers and interceptors](http-client-headers.md), one exception per failure ([errors](http-client-errors.md)),
one log line per failed call, its meters and its trace headers ([call log and meters](http-client-observability.md)).

[← Documentation home](../README.md) · Property prefix: `stratum.http-client` ·
Design: [ADR 0011](../adr/0011-http-client-starters.md), [ADR 0012](../adr/0012-http-client-changes-found-while-building.md),
[ADR 0013](../adr/0013-http-client-retry-and-circuit-breaker-keys.md),
[ADR 0014](../adr/0014-http-client-beans-that-retry.md),
[ADR 0015](../adr/0015-http-client-interceptor-body-limit.md),
[ADR 0016](../adr/0016-http-client-call-log-and-meters.md),
[ADR 0017](../adr/0017-http-client-response-body-limit.md)

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
| Timeouts | A connection must open within `connect-timeout` (2s). The first byte of an answer, and each byte after it, must come within `timeout` (5s). An answer whose body keeps coming is cut off when `total-timeout` runs out, except a streamed download ([Streaming](#streaming)). |
| A pool per client | At most `max-connections` (50) connections. A call that finds them all busy waits at most `max-wait` (2s), then fails. A slow service fills only its own client's pool. |
| Safe retries | A call is sent again only when that is safe ([retries and the circuit breaker](http-client-resilience.md)). Apache HttpClient 5's own retries are off. |
| A time limit per call | The whole call, retries, waits and the answer's body included, ends within `total-timeout` (8s), except a streamed download's body. A call that does not read the body, such as a method that returns `void`, gets its status and headers, and the rest of the body is dropped. |
| A circuit breaker | When most recent calls fail, the client stops calling the service for a while and fails at once. |
| Clear errors | A timeout ends with `SX-HTTPC-0001` (`504`), no answer with `SX-HTTPC-0002` (`503`), an open breaker with `SX-HTTPC-0003` (`503`), and an error answer with a `DownstreamException` holding at most 64 KB of its body ([errors](http-client-errors.md)). |
| A limit on answers | A successful answer's body is read up to `response-body-limit` (32 MB); a larger one fails with `SX-HTTPC-0006` ([errors](http-client-errors.md#the-answer-body-is-read-up-to-32-mb)). A method returning `InputStream` is not limited. |
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
| `retry.max-retries` | int | `3` | no such key | How many times a failed call may be sent again, from 0 to 9. `0` sends each call once ([retries and the circuit breaker](http-client-resilience.md)). |
| `retry.backoff-policy` | `exponential` or `fixed` | `exponential` | no such key | `exponential`: each wait is `multiplier` times the last. `fixed`: every wait is `delay`. Each wait is spread at random by up to half. |
| `retry.delay` | duration | `200ms` | no such key | The wait before the first retry. |
| `retry.multiplier` | number | `2.0` | no such key | How much each wait grows, from 1.0 to 10.0. Only with `exponential`. |
| `retry.max-delay` | duration | `2s`, or `delay` when longer | no such key | The longest single wait, at least `delay`. Only with `exponential`. |
| `total-timeout` | duration | `8s` | no such key | The time allowed for a whole call: every attempt, its wait for a connection, the waits between attempts and its answer's body, except a streamed download's. |
| `circuit-breaker.enabled` | boolean | `true` | no such key | Whether the client has a circuit breaker. |
| `circuit-breaker.failure-rate-threshold` | percent | `50` | no such key | The share of counted attempts, from 1 to 100, that must fail for the breaker to open. |
| `circuit-breaker.sliding-window-size` | int | `100` | no such key | How many of the latest attempts the failure rate is taken over, from 1 to 10000. |
| `circuit-breaker.minimum-number-of-calls` | int | `100` | no such key | How many attempts the breaker must see before it may open, from 1 to 10000. |
| `circuit-breaker.wait-duration-in-open-state` | duration | `60s` | no such key | How long every call fails at once, without being sent, while the breaker is open. |
| `circuit-breaker.permitted-number-of-calls-in-half-open-state` | int | `10` | no such key | How many trial calls go through after the open state, from 1 to 1000. |
| `circuit-breaker.slow-call-duration-threshold` | duration | unset: Resilience4j's `60s` | no such key | How long an attempt may take before it counts as slow ([the slow-call rule](http-client-resilience.md#the-circuit-breaker-group)). |
| `circuit-breaker.slow-call-rate-threshold` | percent | unset: Resilience4j's `100` | no such key | The share of slow attempts, from 1 to 100, that opens the breaker. Only with `slow-call-duration-threshold`. |
| `shared-pool` | boolean | `false` | no such key | With `true`, the client uses one pool shared by every client that sets it, instead of its own. That pool takes its size, connect timeout, idle timeout and lifetime from `defaults`; the client keeps its own `timeout` and `max-wait`. |
| `interfaces` | list of interface names | none | no such key | Generated interfaces, by full name such as `com.partner.OrdersApi`, that call this client's service with all its settings, as a `@StratumClient` interface does ([Clients Stratum did not build](#clients-stratum-did-not-build)). Not allowed under `defaults`. |
| `call-log` | `errors`, `all` or `none` | `errors` | no such key | Which calls write a log line: `errors`, one WARN per failed call; `all`, also one INFO per success; `none`, no line ([call log and meters](http-client-observability.md)). |
| `trace-headers` | boolean | `true` | no such key | With `true`, each call sends `X-Correlation-Id` from the logging context when it has none; with `false`, it sends no trace, baggage or correlation header, such as to a third party ([trace headers](http-client-observability.md#trace-headers)). |
| `mock` | boolean | `false` | no such key | Whether the client answers from files under `stratum-mocks` and from test scripts instead of calling its service, for a service not deployed yet and for tests. Logs a `WARN` at every startup ([Mocked clients](http-client-mocks.md)). |
| `auth.api-key` | text | unset | no such key | An API key sent on every call of the client, from a placeholder such as `${GEO_API_KEY}`. Not allowed under `defaults`, like every `auth` key ([Auth and mTLS](http-client-auth.md)). |
| `auth.api-key-header` | text | `X-Api-Key` | no such key | The header that carries the API key. Only with `auth.api-key`. |
| `auth.bearer` | text | unset | no such key | A fixed token sent as `Authorization: Bearer` on every call. |
| `auth.basic.username`, `auth.basic.password` | text | unset | no such key | Credentials sent with HTTP Basic authentication on every call. Both are required. |
| `auth.client-credentials` | text | unset | no such key | The `spring.security.oauth2.client.registration` whose token the service sends as itself. Needs `stratum-starter-oauth2-client`. |
| `auth.token-relay` | `true`/`false` | `false` | no such key | Sends the caller's access token, as Spring Security's resource server verified it. |
| `headers` | map of header name to value | none | no such key | Headers sent on every call, such as `[X-Partner-Id]: orders`. A client's are added to those under `defaults`; for the same name the client's value wins, and an empty value drops the default ([Headers and interceptors](http-client-headers.md)). `Authorization`, `Cookie`, `Set-Cookie`, `Proxy-Authorization` and the client's API key header are refused. |
| `interceptor-body-limit` | data size | `1MB` | no such key | The most of a request body an interceptor may read with `body()`, from `1B` to `1GB`; a larger body fails with `SX-HTTPC-0005` before it is sent ([bodies](http-client-headers.md#bodies)). `0` is refused. |
| `response-body-limit` | data size | `32MB` | no such key | The most of a successful answer's body the client reads, after gzip is undone, from `1B` to `1GB`, or `-1` for no limit. A larger body fails with `SX-HTTPC-0006`; a body the call does not read is dropped once it passes the limit. A method returning `InputStream` is not limited; a `Resource` is ([errors](http-client-errors.md#the-answer-body-is-read-up-to-32-mb)). `0` is refused. |
| `copy-headers` | list of header names | none | no such key | Headers copied from the request the service is answering onto each call made while answering it, such as `X-Tenant`. A client's list is joined with the one under `defaults`. Outside a request nothing is copied. The same headers as in `headers` are refused. |
| `ssl-bundle` | text | unset | no such key | The name of a `spring.ssl.bundle.*` bundle whose certificate the client presents (mutual TLS). Not allowed under `defaults`, nor with `shared-pool`. |
| `spring.http.clients.imperative.factory` | `http-components`, `jetty`, `reactor`, `jdk` or `simple` | `jdk` | unset: detects the engine, Apache HttpClient 5 first | The engine of the RestClients Stratum did not build. Stratum's own clients always use Apache HttpClient 5 ([Clients Stratum did not build](#clients-stratum-did-not-build)). |

Every duration must be above 0, `auth` holds exactly one kind, and no refused header is in `headers` or
`copy-headers`. A setting that breaks a rule stops startup with `SX-CORE-1003`, naming the rule, never the value; the
rules of `retry` and `circuit-breaker` are listed on
[retries and the circuit breaker](http-client-resilience.md#startup-errors).

Spring Boot's own settings of an HTTP service group, `spring.http.serviceclient.<name>.*`, do not apply to a Stratum
client: Stratum builds its client. Setting one for a Stratum client's name stops startup with `SX-HTTPC-1001`, naming
the Stratum key to use instead. This is the one exception to Stratum's rule of using Spring Boot's keys where they
exist ([ADR 0011](../adr/0011-http-client-starters.md), decision 2).

Spring Boot's global `spring.http.clients.connect-timeout`, `spring.http.clients.read-timeout` and
`spring.http.clients.redirects` apply only to the RestClients Stratum did not build. A Stratum client takes its
timeouts from `stratum.http-client.*`, and it does not follow redirects.

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
stratum.http-client.clients.payments.retry.max-retries=0
stratum.http-client.clients.payments.circuit-breaker.enabled=false
stratum.http-client.clients.reports.total-timeout=30s
```

```yaml
stratum:
  http-client:
    clients:
      payments:
        retry:
          max-retries: 0
        circuit-breaker:
          enabled: false
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

A reports service whose answers can reach 100 MB reads them with a larger limit; every other client keeps 32 MB:

```properties
stratum.http-client.clients.reports.response-body-limit=128MB
```

```yaml
stratum:
  http-client:
    clients:
      reports:
        response-body-limit: 128MB
```

> [!TIP]
> Pick a timeout from the other service's real latency: a little above its p99 is a good start. Then make sure the
> timeouts of your own callers are longer than yours.

## Retries and the circuit breaker

Every call of a client's interface runs through the client's retries, and each attempt through its circuit breaker.
Which calls are retried, what the breaker counts, both groups of keys with full examples in YAML, properties and Java,
and the rules the keys cannot express are on [HTTP client: retries and the circuit breaker](http-client-resilience.md).

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
            .responseBodyLimit(DataSize.ofMegabytes(128))
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

For what has no setting, such as a message converter, declare a `StratumClientCustomizer`
bean. Spring calls it once per client at startup, after Stratum has set the client's address and pool, with the
client's name and its `RestClient.Builder`:

```java
@Bean
StratumClientCustomizer inventoryCsv() {
  return (client, builder) -> {
    if ("inventory".equals(client)) {
      builder.configureMessageConverters(converters -> converters.addCustomConverter(new CsvConverter()));
    }
  };
}
```

For headers and for code that changes each call, use `headers`, `copy-headers` and interceptors instead
([Headers and interceptors](http-client-headers.md)): a request interceptor added here makes Spring hold every request
body in memory. The same customizer changes the client's `RestClient` bean; its `customizeRestTemplate` changes the
client's `RestTemplate` bean ([clients Stratum did not build](http-client-own-clients.md#configure-in-java)).

Spring Boot's `RestClientCustomizer` beans also apply to Stratum's clients, before Stratum's settings; a request
factory one of them sets is replaced by Stratum's.

To change how Stratum builds a client, declare a `StratumClientCustomizer`, as above. A `StratumHttpClients` bean of
your own replaces Stratum's ([Clients Stratum did not build](#clients-stratum-did-not-build)). Stratum's other beans
for the clients are internal: their types may change in any release, so they are not a way to customize.

## Streaming

A client streams a download when its method returns `InputStream` or `ResponseEntity<InputStream>`. The method
returns once the answer's headers arrive, and the body comes in as you read it. Every other return type, `byte[]` and
`Resource` included, reads the whole body into memory first, so it is limited by `response-body-limit`; a download is
not. `ResponseEntity<InputStreamResource>` is not supported by Spring as a return type. A streamed download is not cut
off by `total-timeout`: only `timeout` between bytes bounds it, so close the stream when you are done with it.

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
generated client that takes a `RestClient` or a `RestTemplate`, or a vendor's SDK with its own engine. Each can join a
Stratum client's settings, and nothing joins unless you ask.

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
`@StratumClient(interceptors = ...)`, so only a `GlobalClientInterceptor` that checks `request.client()` reaches it
([interceptors](http-client-headers.md#interceptors)).

**A generated client that takes a `RestClient` or a `RestTemplate`** injects the client's own bean,
`@Qualifier("partner") RestClient` or `RestTemplate`, with its address, timeouts, pool, credential, retries and circuit
breaker; **a client with its own engine** reads `stratumHttpClients.settings("partner")`. Both are on
[clients Stratum did not build](http-client-own-clients.md).

## How it is wired

Stratum's auto-configuration runs before Spring Boot's HTTP service client auto-configuration, because Spring Boot
sets up groups only when their registry exists, and Stratum's registration of the clients creates it. At startup,
Spring Boot first gives every group its own request factory; Stratum's set-up then replaces it, for Stratum's groups
only, with the client's Apache HttpClient 5 pool.

## Call log and meters

What a failed call writes, with and without the logging starter, what its redaction masks in the called URL, the
`call-log` and `trace-headers` keys with full examples, the trace headers and every meter are on
[HTTP client: call log and meters](http-client-observability.md).

## Errors

What a failed call throws, `DownstreamException`, a `404` read as an empty `Optional`, mapping an error answer to your
own exception, the 64 KB read of an error body, the limit on a successful answer and the startup errors are on
[HTTP client: errors](http-client-errors.md).

## Planned

These parts of [ADR 0011](../adr/0011-http-client-starters.md) are not built yet, and none of their keys is read:

- `urls` ([#308](https://github.com/exepex/stratum/issues/308));
- the retry and circuit breaker of a client with its own engine
  ([#262](https://github.com/exepex/stratum/issues/262));
- the reactive starter, `stratum-starter-reactive-http-client`, on `WebClient` ([#269](https://github.com/exepex/stratum/issues/269)).

## Troubleshooting

| Symptom | Likely cause and fix |
| --- | --- |
| Startup fails with `SX-HTTPC-1004` | The client has no `url`, or the name in the settings differs from the one in `@StratumClient`. |
| No bean of your client interface | The interface is outside your application's packages, or `stratum.http-client.enabled=false`. |
| Calls fail after exactly 5 s | The default `timeout`, for a call that is not sent again, such as a `POST`. Raise it for that client if the other service is slow for a reason. |
| `GET` calls fail after about 8 s | The default `timeout` of 5 s ran out, and the retry ran out of `total-timeout`. Raise both for that client if the other service is slow for a reason. |
| Calls fail with `SX-HTTPC-0006` | The other service's answer is larger than the client's `response-body-limit`, 32 MB by default. Raise it for that client, or return an `InputStream` to stream the answer. |
| Calls fail at once with `SX-HTTPC-0003` | The client's circuit breaker is open: most recent calls to the service failed. Look at the service, and at the `WARN` line that names the client. |
| Calls fail after 2 s with a connection request timeout | The client's pool is full: raise `max-connections`, or find what holds its connections. |
