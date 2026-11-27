# Resilience: `stratum-starter-resilience`

**Status: Partly built.** The incoming part is built: a limit on how many requests each route of a Spring MVC
service runs at once. Spring MVC only for now; WebFlux is planned in
[issue #420](https://github.com/exepex/stratum/issues/420). Pausing message listeners while a circuit breaker is open
is planned in [issue #421](https://github.com/exepex/stratum/issues/421). Policies for outgoing calls, such as
retries and circuit breakers around your own methods, are **Planned**.

[← Documentation home](../README.md) · Property prefix: `stratum.resilience` · Decision record:
[ADR 0035](../adr/0035-incoming-resilience-per-route-concurrency-limits.md)

## What it does

When one endpoint gets slow, for example a report that waits on a busy database, its requests pile up. Each one
holds a server thread, a database connection and memory, until the whole service slows down and every endpoint
fails. A **concurrency limit** stops that: you say how many requests of a route may run at once, and the next one is
refused at once instead of waiting. The caller gets `503 Service Unavailable` with a `Retry-After` header, which
tells it how many seconds to wait before it sends the request again. The rest of the service keeps working.

- Every limit is **off by default**. A service that adds the starter and sets no route behaves exactly as before:
  no request is counted, refused or measured.
- A **route** is a path pattern, such as `/reports/**`, as in `stratum.web.timeout.routes`. When several routes
  match a request, the most specific one counts it.
- A refused request **never waits** and never runs; there is no queue.
- **Limits count per instance.** A limit of 10 on a service with 4 instances admits up to 40 requests in all.

The limit is a [Resilience4j](https://resilience4j.readme.io/) semaphore bulkhead per route, held for the whole
request.

## Add it

The microservice and web-ui bundles bring this starter ([Bundle starters](bundles.md)). Without a bundle, add:

```xml
<dependency>
  <groupId>io.github.exepex.stratum</groupId>
  <artifactId>stratum-starter-resilience</artifactId>
</dependency>
```

## Limit a route

Set `max-concurrent` for each route you want to protect. The pattern is the path within your application, without
`server.servlet.context-path`; write it in square brackets, as Spring Boot needs for a key with `/` or `*`.

In `application.properties`:

```properties
stratum.resilience.incoming.routes[/reports/**].max-concurrent=10
stratum.resilience.incoming.routes[/exports/{id}].max-concurrent=2
stratum.resilience.incoming.retry-after=2s
```

or in `application.yml`:

```yaml
stratum:
  resilience:
    incoming:
      routes:
        "[/reports/**]":
          max-concurrent: 10
        "[/exports/{id}]":
          max-concurrent: 2
      retry-after: 2s
```

Choose the limit from what the route's slowest dependency can take: a route whose requests each hold a database
connection should admit fewer requests than the pool has connections. Every HTTP method of the path counts against
the same limit.

## Configure in Java

Every setting on this page can also be set in Java. Write a class that implements `ResiliencePropertiesCustomizer`
and list it in your service's `src/main/resources/META-INF/spring.factories`. A customizer is not a bean. When a
setting is given in both a file and Java, Java wins.

```java
public class OrdersResilienceSettings implements ResiliencePropertiesCustomizer {

  @Override
  public ResilienceProperties customize(ResilienceProperties settings) {
    var incoming = settings.incoming().toBuilder()
        .routes(Map.of(
            "/reports/**", RouteLimit.builder().maxConcurrent(10).build(),
            "/exports/{id}", RouteLimit.builder().maxConcurrent(2).build()))
        .retryAfter(Duration.ofSeconds(2))
        .build();
    return settings.toBuilder().incoming(incoming).build();
  }
}
```

```properties
com.exepex.stratum.resilience.ResiliencePropertiesCustomizer=com.example.orders.OrdersResilienceSettings
```

The status of a refused request is a web setting: change it in a `WebPropertiesCustomizer`
([Web](web.md)).

## Properties

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `stratum.resilience.enabled` | boolean | `true` | Whether the module is on. With `false`, no route is limited and `SX-RESILIENCE-0001` gets no default status. |
| `stratum.resilience.incoming.routes.<pattern>.max-concurrent` | int, 1 to 100000 | none: no route is limited | The most requests of the route that run at once, on this instance. Required for each route. |
| `stratum.resilience.incoming.retry-after` | duration, 1s to 1h | `1s` | How long a refused caller is told to wait, in the `Retry-After` header, in whole seconds rounded up. |
| `stratum.web.error.statuses.SX-RESILIENCE-0001` | int, 400 to 599 | `503`, given by this starter | The status of a refused request. A team that wants `429` sets it here; your value always wins. The key belongs to the [web module](web.md#properties). |

A value out of range, or a pattern that is not a valid Spring path pattern (`SX-RESILIENCE-1001`), stops the
application at startup with a message that names the key ([Error codes](../guides/error-codes.md#stratum-starter-resilience)).

## What a refused caller gets

With the web MVC starter, a refused request is answered by Stratum's error handling, like any failure with a code:

```json
{
  "type": "about:blank",
  "title": "Service Unavailable",
  "status": 503,
  "detail": "The service refused the request: its route already has as many requests in progress as it admits; send it again later",
  "code": "SX-RESILIENCE-0001",
  "severity": "ERROR",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "correlationId": "checkout-777"
}
```

- The answer always carries `Retry-After: <seconds>`. A browser gets the HTML error page with the same status.
- The status is the one mapped to `SX-RESILIENCE-0001`, `503` unless you set another. It stays so with
  `spring.mvc.problemdetails.enabled=true`, with your own subclass of `ResponseEntityExceptionHandler`, and in
  [backward-compatible mode](web-compatible-mode.md), where the limits apply too.
- **Your own catch-all `@ExceptionHandler(Exception.class)` answers first**, as it does for every failure: the caller
  then gets your status and body, and only the `Retry-After` header is left.
- Without the web MVC starter, or with `stratum.web.enabled=false`, the caller gets `503` with Spring Boot's own
  error body and the `Retry-After` header; `stratum.web.error.statuses` does not apply then.

Other Stratum services that call yours honour the `503` and its `Retry-After` within their own time left
([HTTP client: retries](http-client-resilience.md)).

## What is never counted

- **Actuator, on the same port as your service:** the endpoints' base path, such as `/actuator`, with its
  discovery page and every endpoint below it, even under a route of `/**`. Your own actuator endpoints, `@Endpoint`
  beans, are never counted either: they are read from Spring Boot's list of endpoint paths. With
  `management.endpoints.web.base-path=/`, only each endpoint's own path is left out, such as `/health`, and `GET /`
  is counted like any route. With actuator on its own `management.server.port`, the limit never sees its requests.
- **Health probes on the main port:** `/livez` and `/readyz` with
  `management.endpoint.health.probes.add-additional-paths=true`, and each health group's `server:` additional path.
- **CORS preflight requests**, and the error and asynchronous dispatches of a request already counted.
- **Requests Spring Security answers**, such as a `401` or `403`: the limit runs after Spring Security.

## How a request is counted

A request holds its place for its whole life: a plain handler until it returns, an asynchronous one, such as a
`Callable`, `DeferredResult`, `CompletableFuture`, `SseEmitter` or `StreamingResponseBody`, until the server
completes it, also after a timeout or an error. When the caller disconnects, the place is held until the handler's
work ends. A request that `stratum.web.timeout` cuts off gives its place back when it is cut off; for asynchronous
routes, also set `spring.mvc.async.request-timeout` ([Response timeouts](web-timeouts.md)).

## Kinds of application

| Application | What happens |
| --- | --- |
| Spring MVC (servlet) | The routes are limited. |
| WebFlux (reactive) | Setting a route stops startup with `SX-RESILIENCE-1002`, as the limits do not work there yet ([#420](https://github.com/exepex/stratum/issues/420)). Without routes it starts. |
| No web server: a listener application, a batch job, `spring.main.web-application-type=none`, `@SpringBootTest(webEnvironment = NONE)` | The routes do nothing, with no error and no warning. |

## Watching it work

| Meter | Type | Tags | What it shows |
| --- | --- | --- | --- |
| `stratum.resilience.requests.refused` | counter | `route`: the configured pattern | Requests refused because their route was full. |
| `stratum.resilience.requests.active` | gauge | `route`: the configured pattern | Requests the route runs now. |

The meters exist only for configured routes, so their number is small. A refused request writes **no log line** of
its own while its code has a status; the [request log line](web-request-log.md) records its status. If your Java
configuration replaces the whole status map and leaves `SX-RESILIENCE-0001` out, the caller gets `500` and one
`ERROR` line says the code has no status. Alert on `stratum.resilience.requests.refused` rising: a route that refuses
often is too slow or its limit too low.

## When a call to another service fails

The HTTP client starters set up their own retries and circuit breaker, with their own keys
([ADR 0034](../adr/0034-http-client-auth-resilience-and-connections.md)). Do not put Resilience4j's `@Retry` or
`@CircuitBreaker` around a call of a `@StratumClient` interface: each of your attempts would run all of the
client's own retries, and your breaker would also count the client's open-breaker refusals (`SX-HTTPC-0003`). To
return something else when the call fails, catch the client's failures in plain Java. Here `RatesClient` is a
`@StratumClient` interface:

```java
@Service
@RequiredArgsConstructor
class ExchangeRateService {

  private final RatesClient ratesClient;
  private final RateCache rateCache;

  BigDecimal rate(String currency) {
    try {
      return ratesClient.current(currency);
    } catch (StratumException | DownstreamException failure) {
      return rateCache.lastKnown(currency);
    }
  }
}
```

`StratumException` carries the client's `SX-HTTPC-` code, such as `SX-HTTPC-0003` for an open breaker, and
`DownstreamException` is an error answer ([HTTP client: errors](http-client-errors.md)).

## Customize

- **Your own filter:** declare a bean named `stratumConcurrencyLimitFilter` of Spring Boot's type
  `FilterRegistrationBean`. Stratum's filter backs off by that name, and no route is limited unless your filter
  limits it.
- **Another status:** set `stratum.web.error.statuses.SX-RESILIENCE-0001`, such as `429`.

## Switch it on or off

The starter is on when it is on the classpath, but limits nothing until a route is set. Leaving
`stratum.resilience.enabled` unset, or empty, is the same as `true`.

```properties
stratum.resilience.enabled=false
```

```yaml
stratum:
  resilience:
    enabled: false
```

## Troubleshooting

| Symptom | Likely cause and fix |
| --- | --- |
| Many `503` with `SX-RESILIENCE-0001` | The route is full: its requests are slow or its limit is low. Look at `stratum.resilience.requests.active` and the route's latency before raising `max-concurrent`. |
| A refused request gets your own status, not `503` | A catch-all `@ExceptionHandler(Exception.class)` answers it first; see [What a refused caller gets](#what-a-refused-caller-gets). |
| Startup stops with `SX-RESILIENCE-1002` | The application is reactive; remove the routes until [#420](https://github.com/exepex/stratum/issues/420) is built. |
| A route seems unlimited | Check the pattern is the path within the application, without the context path, and that a more specific route does not match first. |
