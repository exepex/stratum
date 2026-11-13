# Web: response timeouts

**Status: Built**, in `stratum-starter-webmvc` and `stratum-starter-webflux`, unless
`stratum.web.enabled=false`.

[← Web](web.md) · [Documentation home](../README.md) · Property prefix: `stratum.web.timeout`

## What it does

By default, every request has a time limit, 10 seconds unless you set another. While
`stratum.backward-compatible` is `true`, there is no limit unless you set one; see
[Backward-compatible mode](web-compatible-mode.md#time-and-body-size-limits). Once a limit applies,
a request whose response has not started when its time is up is answered with `503` and the code
`SX-WEB-1007`, and the service logs one WARN line with the limit. The caller gets an answer it can
act on, and a slow call can no longer hold a server thread, or a caller, for minutes.

The limit counts from when the request reaches the service, so the time Spring Security takes counts
too, until the first byte of the response is sent. A response that has started, such as a stream or
a large download, is never cut off.

## Set the limits

In `application.properties`:

```properties
stratum.web.timeout.default-limit=5s
stratum.web.timeout.routes[/reports/**]=60s
stratum.web.timeout.routes[/internal/exports/*]=0
stratum.web.timeout.status=504
```

or in `application.yml`:

```yaml
stratum:
  web:
    timeout:
      default-limit: 5s
      routes:
        "[/reports/**]": 60s
        "[/internal/exports/*]": 0
      status: 504
```

- A limit is a duration, such as `500ms`, `5s` or `2m`. `0` means no limit.
- `routes` maps a Spring path pattern to its limit. Write each pattern in square brackets, as above,
  so Spring Boot keeps its `/` and `*`. When several patterns match a path, the most specific wins.
  A pattern that is not valid, such as `/api/{id`, stops startup with `SX-WEB-1009`, which names the
  key and the pattern.
- `status` is any status from `400` to `599`. `503` tells callers the service is too busy now and
  that a retry later can work; some gateways expect `504`.
- Behind a gateway or load balancer, keep every limit, route and `@ResponseTimeout` limits included,
  below the gateway's own timeout. With a 30s gateway and a 10s limit, the caller gets Stratum's
  `503` with a trace ID to quote; with a limit above 30s, the gateway answers first with its own
  `504`, without Stratum's error body or trace ID.

Each key, with its type and default, is listed in [Web → Properties](web.md#properties).

## Give one API its own limit

`@ResponseTimeout` on a controller method wins over the routes and the default, whether it gives
the API more time or less:

```java
@ResponseTimeout(60)
@GetMapping("/reports/{reportId}")
Report report(@PathVariable String reportId) {
  return reports.build(reportId);
}

@ResponseTimeout(value = 800, unit = TimeUnit.MILLISECONDS)
@GetMapping("/prices/{sku}")
Price price(@PathVariable String sku) {
  return prices.current(sku);
}
```

The unit is seconds unless you set another. `@ResponseTimeout(0)` means no limit. The annotation
works on `@RequestMapping` methods. A WebFlux router function has no method to annotate, so it
uses the routes and the default.

So the limit of a request is, in order: its method's `@ResponseTimeout`, then the most specific
route that matches its path, then the default.

## Uploads

An upload API usually answers only once it has read the whole request body, so a large upload,
such as a file or a binary stream, can take longer than the default limit. Its response has not
started yet, so it fails with `503`: on WebFlux at the limit, and on Spring MVC at the limit or,
when its read ignores the interrupt, once the read ends. Give each upload API its own limit, or
none:

```java
@ResponseTimeout(0)
@PostMapping("/documents")
DocumentId upload(InputStream body) {
  return documents.store(body);
}
```

or by path, `stratum.web.timeout.routes[/uploads/**]=0`. A download, or any response that streams
while it works, needs nothing: once its response has started, it is never cut off. An upload larger
than 256KB also needs a larger [body size limit](web-body-limit.md#uploads-of-5-to-20-mb).

## Configure in Java

As every setting, the limits can be set in a `WebPropertiesCustomizer`, and Java wins over the files:

```java
public class OrdersWebSettings implements WebPropertiesCustomizer {

  @Override
  public WebProperties customize(WebProperties properties) {
    return properties.toBuilder()
        .timeout(
            properties.timeout().toBuilder()
                .defaultLimit(Duration.ofSeconds(5))
                .routes(Map.of("/reports/**", Duration.ofSeconds(60)))
                .build())
        .build();
  }
}
```

List the class in `META-INF/spring.factories`, as the
[web MVC starter's README](../../starters/stratum-starter-webmvc/README.md#configure-in-java) shows.

## How each stack stops the work

**Spring MVC** answers each request on its own thread, and Java cannot stop a thread from outside.
When the time is up, Stratum interrupts the request's thread, as Tomcat's stuck thread valve does:

- A blocking call that honours interrupts stops at once and throws, and the caller gets `503`. That
  includes `Thread.sleep`, waits on locks and queues, the JDK's `HttpClient`, and socket reads and
  writes on virtual threads.
- A call that ignores interrupts runs on until it ends, and the caller then gets `503`, as late as
  that. Socket reads on ordinary platform threads ignore interrupts, so a database query waiting
  on its socket usually runs on. Give every remote call its own timeout, shorter than the
  request's, such as a query timeout on the database and a read timeout on the HTTP client.
- Whatever your code answers or throws after the time is up, from the handler, from your own
  `@ExceptionHandler` or from a filter such as Spring Security's or your own, the caller gets `503`,
  as on WebFlux. Only a response that started in time is sent as it is.
- For a handler that returns a `Callable`, a `DeferredResult` or an `SseEmitter`, the limit ends
  when the handler method returns. Spring's `spring.mvc.async.request-timeout` limits the rest.

Stratum clears the interrupt before anything is written, because the server closes the connection
when an interrupted thread writes to it, and before the server reuses the thread.

**WebFlux** cancels the request's work when the time is up, so reactive database and HTTP calls stop.
Blocking work you moved to another scheduler, such as `Schedulers.boundedElastic()`, runs on to its
end. A `TimeoutException` of your own, such as from `Mono.timeout`, is answered as the failure it
is, `500`, and not as a time limit.

On both stacks, work already done stays done. A request that saved an order before its time ran out
has saved it, and its caller sees `503`. Make the requests a caller retries idempotent.

## Read the time left

To fit your own remote calls within the request's limit, inject `RequestTimeLeft`, from
`stratum-core`. It tells how long the request whose work runs on the calling thread may still run,
counted live: a method's `@ResponseTimeout` is seen as soon as it applies.

```java
@Service
@RequiredArgsConstructor
public class StockService {

  private static final Duration STOCK_TIMEOUT = Duration.ofSeconds(2);

  private final RequestTimeLeft requestTimeLeft;
  private final StockClient stockClient;

  public Stock stockOf(String sku) {
    var left = requestTimeLeft.timeLeft().orElse(STOCK_TIMEOUT);
    if (left.toMillis() < 1) {
      throw new IllegalStateException("No time is left to look up the stock");
    }
    var timeout = left.compareTo(STOCK_TIMEOUT) < 0 ? left : STOCK_TIMEOUT;
    return stockClient.stockOf(sku, timeout);
  }
}
```

Check that at least a millisecond is left before you pass the time to a client. Once the limit has
passed, the time left is zero or less, and clients disagree on what such a timeout means: the JDK's
`HttpClient` rejects it with an `IllegalArgumentException`, and `HttpURLConnection` takes a timeout
of `0` milliseconds as no timeout at all. A time left under one millisecond becomes `0` when a client
counts it in milliseconds. Keep the call's own timeout as an upper bound, as `STOCK_TIMEOUT` is here.

`timeLeft()` returns:

- the time left, while the request runs;
- zero or less, once the limit has passed and until the request ends;
- empty, which means **no limit**, when there is no limit to read: a route or method with a limit of
  `0`, a request that has ended, and any work that is not part of a web request, such as a Kafka
  listener, a batch job or a scheduled task.

Empty never means that no time is left. Where the deadline does not reach, `timeLeft()` says there
is no limit, so keep each remote call's own timeout as well.

The deadline reaches these places, and no others:

| Where the code runs | What it reads |
|---|---|
| The request's own thread, on Spring MVC | The time left |
| A WebFlux request's operators, on whatever thread runs them | The time left, with `spring.reactor.context-propagation=auto`, which the web module sets on WebFlux |
| A blocking call moved to `Schedulers.boundedElastic()` | The time left with `auto`. With Spring Boot's `limited`, only `handle` and `tap` read it; a `Mono.fromCallable` there reads no limit |
| `@Async` methods and the executor beans of your service | The time left, while the request runs, when the executors carry the context: the [logging](../../starters/stratum-starter-logging/README.md) and [observability](../../starters/stratum-starter-observability/README.md) starters turn that on. With a web starter alone, no limit, as such work has no logging context either |
| A pool that is not a bean, `CompletableFuture`'s common pool, or a thread you start | No limit |
| A `Callable` or `DeferredResult` handler | No limit once the handler method has returned, because the limit ends then; a `Callable` that starts before that can read the time left for that moment |
| Any of these, once the request has ended | No limit. On WebFlux the request ends when its response starts. On Spring MVC it ends when the handler returns, so a handler that starts its response itself, with `flushBuffer()` or by writing to the output stream, still reads its time left until it returns, and that can go below zero |

To read the time left in your own way, declare a bean of type `RequestTimeLeft`; Stratum's backs off.

## What the caller and the logs see

The caller gets the [error body](../../starters/stratum-starter-webmvc/README.md#the-error-body) every
error gets, with the trace ID to quote to support:

```json
{
  "type": "about:blank",
  "title": "Service Unavailable",
  "status": 503,
  "detail": "The request ran out of time",
  "code": "SX-WEB-1007",
  "severity": "ERROR",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "correlationId": "checkout-777"
}
```

The service logs one WARN line, `The request ran out of time: its response had not started after
PT10S`, with the request fields, and the [request log](web.md#request-log) line has the status. The
observability starter counts the request with its status, so the
[SLA dashboards](web.md#sla-dashboards) show it as a server error.
