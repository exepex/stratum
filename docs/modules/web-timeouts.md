# Web: response timeouts

**Status: Built**, in `stratum-starter-webmvc` and `stratum-starter-webflux`, when
`stratum.web.enabled=true`.

[← Web](web.md) · [Documentation home](../README.md) · Property prefix: `stratum.web.timeout`

## What it does

Every request has a time limit, 10 seconds unless you set another. A request whose response has not
started when its time is up is answered with `503` and the code `SX-WEB-1007`, and the service logs
one WARN line with the limit. The caller gets an answer it can act on, and a slow call can no longer
hold a server thread, or a caller, for minutes.

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
- `status` is any status from `400` to `599`. `503` tells callers the service is too busy now and
  that a retry later can work; some gateways expect `504`.

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `stratum.web.timeout.default-limit` | duration | `10s` | How long any request may take until its response starts. `0` means no limit. |
| `stratum.web.timeout.routes` | map of path pattern to duration | empty | The limit of the requests whose path matches a pattern, such as `/reports/**`. The most specific pattern wins. |
| `stratum.web.timeout.status` | HTTP status, `400` to `599` | `503` | The status a request that ran out of time is answered with. |

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
started yet, so it would be answered with `503`. Give each upload API its own limit, or none:

```java
@ResponseTimeout(0)
@PostMapping("/documents")
DocumentId upload(InputStream body) {
  return documents.store(body);
}
```

or by path, `stratum.web.timeout.routes[/uploads/**]=0`. A download, or any response that streams
while it works, needs nothing: once its response has started, it is never cut off.

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
- A call that ignores interrupts runs on until it ends, and the caller then gets what your handler
  answers. Socket reads on ordinary platform threads ignore interrupts, so a database query waiting
  on its socket usually runs on. Give every remote call its own timeout, shorter than the
  request's, such as a query timeout on the database and a read timeout on the HTTP client.
- When your code catches the failure the interrupt caused and answers the request itself, such as
  from your own `@ExceptionHandler`, your answer is sent.
- For a handler that returns a `Callable`, a `DeferredResult` or an `SseEmitter`, the limit ends
  when the handler method returns. Spring's `spring.mvc.async.request-timeout` limits the rest.

The thread is never left interrupted: Stratum clears the interrupt before the server reuses it.

**WebFlux** cancels the request's work when the time is up, so reactive database and HTTP calls stop.
Blocking work you moved to another scheduler, such as `Schedulers.boundedElastic()`, runs on to its
end. A `TimeoutException` of your own, such as from `Mono.timeout`, is answered as the failure it
is, `500`, and not as a time limit.

On both stacks, work already done stays done. A request that saved an order before its time ran out
has saved it, and its caller sees `503`. Make the requests a caller retries idempotent.

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
