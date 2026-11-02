# WebFlux: `stratum-starter-webflux`

**Status: Built.** Every error a reactive service sends can be the same
[RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) JSON, with a code, the trace ID
and the correlation ID, and never a stack trace or an exception message. Browsers get an HTML page
with a reference to quote to support. Every log line of a request carries its method, route, path,
client IP and correlation ID, and each request writes one log line when it is answered. A request
can be given a time limit, answered with `503`, and a body size limit, answered with `413`.

By default, `stratum.web.backward-compatible` is `true`, so a running service keeps its contract
with its consumers: errors that Spring answers keep Spring Boot's body, and there is no default
time or body size limit. A new service sets it to `false` from the start. See
[Backward-compatible mode](../../docs/modules/web-compatible-mode.md).

This page covers this starter only. The design both web starters share, and the Spring MVC starter,
are in [Web](../../docs/modules/web.md). How Stratum works as a whole is in the
[documentation](../../docs/README.md).

[← Documentation home](../../docs/README.md) · Property prefix: `stratum.web`

## What it does

Without it, each service decides what its errors look like. One sends Spring Boot's default JSON,
another a home-made shape, and a third leaks an exception message. Callers need special handling
per service, and support cannot find the failed request in the logs. With it, every error from
every service has the same body and carries the trace ID, so one search finds the request.

The body is the same as the [Spring MVC starter's](../stratum-starter-webmvc/README.md), so a
caller cannot tell a reactive service from one that is not. You keep writing controllers and
exception handlers the usual way. Stratum only answers what your own handlers leave.

## Add it

```xml
<dependency>
  <groupId>com.exepex.stratum</groupId>
  <artifactId>stratum-starter-webflux</artifactId>
</dependency>
```

It brings Spring WebFlux on Netty, so do not add `spring-boot-starter-webflux` as well. A service
that is not reactive uses `stratum-starter-webmvc` instead; never add both. The
`stratum-starter-reactive-microservice` bundle already includes it. The version comes from
Stratum's parent or BOM ([installation](../../docs/getting-started/installation.md)).

The starter does nothing of its own until you switch it on, in `application.properties`:

```properties
stratum.web.enabled=true
```

or in `application.yml`:

```yaml
stratum:
  web:
    enabled: true
```

The trace and correlation IDs in error bodies and logs come from the
[observability starter](../stratum-starter-observability/README.md), which also turns on Reactor's
context propagation, so the IDs follow the request across threads. Without it, there are no
IDs, and everything else works the same.

## What you get

### The error body

With `stratum.web.backward-compatible`, `true` by default, an error that Spring answers keeps
Spring Boot's body unless the consumer sends `Accept: application/problem+json`, so existing
consumers see no change. Errors with a code, timeouts and bodies too large always get the body
below. See [Backward-compatible mode](../../docs/modules/web-compatible-mode.md).

Stratum's body is sent with the media type `application/problem+json`:

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Order already exists: order 42",
  "code": "ORD-0001",
  "severity": "ERROR",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "correlationId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

A failed validation adds `details`, one entry per invalid field, with the same rules as the Spring
MVC starter: a message that quotes the rejected value is replaced with `is not valid`, and a map
key in a field path is shown as `[*]`
([example](../stratum-starter-webmvc/README.md#the-error-body)). Stratum's body never holds a stack
trace, an exception message, a class name or the request path, whatever `spring.web.error.include-*`
says.

It makes no difference whether your code throws or returns `Mono.error(...)`, or whether the
failure comes from a controller or a `WebFilter`.

### What each failure gets

| The request fails with | Status | `code` |
| --- | --- | --- |
| Your `StratumException`, its code mapped in `stratum.web.error.statuses` | the mapped status | your code, such as `ORD-0001` |
| Your `StratumException`, its code not mapped | `500` | your code |
| Your exception that implements [`FailureWithErrorCode`](../../docs/guides/error-codes.md#existing-exceptions-a-migration-path) | as a `StratumException` with its code | your code |
| `@Valid` on a body, or a constraint on a parameter | `400` | `SX-WEB-1001` |
| A body that is not valid JSON, a parameter of the wrong type, or a multipart upload Spring cannot read, such as one with too many parts | `400` | `SX-WEB-1002` |
| A path nothing answers | `404` | `SX-WEB-1003` |
| Any other status, such as `405`, a `ResponseStatusException` or an exception annotated `@ResponseStatus` | that status | `SX-WEB-1004` |
| Any other exception | `500` | `SX-WEB-1005`, with a fixed message |
| A request whose response had not started within its [time limit](#timeouts) | `stratum.web.timeout.status`, `503` by default | `SX-WEB-1007` |
| A request whose body is larger than its [limit](#body-size-limit), or a multipart part over Spring's disk limit | `413` | `SX-WEB-1008` |

The codes, with how to fix each, are in [Error and warning codes](../../docs/guides/error-codes.md#stratum-starter-webmvc-and-stratum-starter-webflux).

### What is logged

- A `StratumException`, or an exception that implements `FailureWithErrorCode`, with a `4xx`
  status: one WARN line with its code and message.
- A `StratumException` with a `5xx` status: one ERROR line with its stack trace. When its code has
  no status in `stratum.web.error.statuses`, the line says so, so a missing mapping shows up in
  testing. Spring Boot's own line for the same failure is left out, so it is logged once.
- Any other exception answered with `500`: Spring Boot logs it at ERROR with its stack trace, as
  without Stratum.
- Other failures, such as `400` and `404`, are not logged, as in Spring Boot.

### The HTML error page

A browser, or any caller whose `Accept` header names `text/html`, gets a short page with the status
and a reference, which is the trace ID. That includes your `StratumException`, with its mapped
status:

```text
Not Found
The request could not be completed (status 404).
Reference: 4bf92f3577b34da6a3ce929d0e0e4736. Quote it to support so they can find what went wrong.
```

Your own error templates, such as `templates/error/404.html` or `templates/error/4xx.html`, and
static pages such as `static/error/404.html`, win, as in Spring Boot.
`spring.web.error.whitelabel.enabled=false` turns Stratum's page off, as it turns off Spring Boot's.
A browser then gets the JSON error body, with the same status an API caller gets.

## Your own error handling

Your handlers always win. Stratum answers only what your `@RestControllerAdvice` classes and
`@ExceptionHandler` methods leave:

```java
@RestControllerAdvice
class OrderErrorHandlers {

  @ExceptionHandler(RefundRefusedException.class)
  ProblemDetail refundRefused() {
    return ProblemDetail.forStatusAndDetail(HttpStatus.PAYMENT_REQUIRED, "The refund was refused");
  }
}
```

- **Your handler returns a `ProblemDetail`:** it is sent as you made it, and Stratum adds only
  `traceId` and `correlationId` when it does not have them. As in plain Spring, Spring sets its
  `instance` to the request path when you leave it empty; set it yourself if your paths can hold
  personal data.
- **Your handler returns any other body:** it is sent untouched.
- **Your handler handles `StratumException`, or your exception that implements
  `FailureWithErrorCode`, itself:** yours is used, and the mapping in `stratum.web.error.statuses`
  is not.
- **Your own `ErrorWebExceptionHandler` or `ErrorAttributes` bean:** it replaces Stratum's, as it
  replaces Spring Boot's ([customize](#customize)).

## Request fields and the correlation ID

Every log line written while a request runs carries its `httpMethod`, `httpRoute`, `httpPath`,
`httpQuery`, `clientIp` and `userAgent`, after every thread switch, such as `publishOn`. Device, OS
and browser fields, and the client's country and city, are off until you turn them on. The caller's
`X-Correlation-Id` is used when its format is safe, sent back in the response header and passed on
to the services this one calls; when there is none, the trace ID is the correlation ID. Each field,
the client IP behind proxies and the settings are described in
[Web](../../docs/modules/web.md#request-fields-in-logs).

Each request also writes one log line when it is answered, such as
`GET /orders/{orderId} answered 200 in 12 ms`, with `httpStatus` and `durationMs` as fields. The
actuator's paths and static files write none. The headers you name, and the claims you list from the
caller's token when you turn it on, can be added to it; credentials never are. See
[Web](../../docs/modules/web.md#request-log).

Response times and error rates per API come from the observability starter's metrics. The SLA
dashboard recipe, with PromQL examples, is in [Web](../../docs/modules/web.md#sla-dashboards).

## Timeouts

While `stratum.web.backward-compatible` is `true`, the default, a request has no time limit unless
you set one. With `false`, each request has 10 seconds until its response starts, unless you set
another limit. A request that runs out of time is answered with `503` and `SX-WEB-1007`, and the
service logs a WARN line. A response that has started, such as a stream, is never cut off. Change
the limits in `application.properties`:

```properties
stratum.web.timeout.default-limit=5s
stratum.web.timeout.routes[/reports/**]=60s
```

or in `application.yml`:

```yaml
stratum:
  web:
    timeout:
      default-limit: 5s
      routes:
        "[/reports/**]": 60s
```

or for one API, which wins over both:

```java
@ResponseTimeout(60)
@GetMapping("/reports/{reportId}")
Mono<Report> report(@PathVariable String reportId) {
  return reports.build(reportId);
}
```

When the time is up, Stratum cancels the request's work, so reactive database and HTTP calls stop.
Blocking work moved to another scheduler runs on to its end. A `TimeoutException` of your own, such
as from `Mono.timeout`, is answered as the failure it is. An upload API answers only once it has
read the whole body, so give it `@ResponseTimeout(0)` or a long limit; see
[Uploads](../../docs/modules/web-timeouts.md#uploads). The details, with `0` for no limit and the
status, are in [Response timeouts](../../docs/modules/web-timeouts.md).

## Body size limit

While `stratum.web.backward-compatible` is `true`, the default, a request body has no size limit
unless you set one. With `false`, each request body may have 256KB, unless you set another limit. A
larger body is answered with `413` and `SX-WEB-1008`, and the service logs a WARN line. A body sent
without `Content-Length` is counted as it arrives and stopped at the limit, before it is held in
memory. Raise the limit for uploads by path, in `application.properties`:

```properties
stratum.web.body-limit.routes[/uploads/**]=20MB
```

or in `application.yml`:

```yaml
stratum:
  web:
    body-limit:
      routes:
        "[/uploads/**]": 20MB
```

or for one API, with `@RequestBodyLimit`, in megabytes unless you set another unit:

```java
@RequestBodyLimit(20)
@ResponseTimeout(0)
@PostMapping("/documents")
Mono<DocumentId> upload(@RequestBody Flux<DataBuffer> body) {
  return documents.store(body);
}
```

Spring's own limit of 256KB for a body decoded in memory, `spring.http.codecs.max-in-memory-size`,
applies as well, so read a large upload as a `Flux<DataBuffer>`. Forms and multipart uploads are
checked by their `Content-Length` only. Spring Boot sets no limit on a multipart upload's disk use or
parts, so with the module on and `backward-compatible` set to `false` this starter gives each part
`10MB` of disk and an upload `50` parts; set
`spring.webflux.multipart.max-disk-usage-per-part` or `max-parts` to change them. The details, with
`0` for no limit, are in
[Request body size limit](../../docs/modules/web-body-limit.md).

## Map your codes to statuses

Throw a `StratumException` with your own [error code](../../docs/guides/error-codes.md#using-codes-in-your-service),
or return it in `Mono.error(...)`, and map the code to its HTTP status. Write each code as it is,
capitals and dashes included.

In `application.properties`:

```properties
stratum.web.error.statuses.ORD-0001=409
stratum.web.error.statuses.ORD-0002=503
```

In `application.yml`:

```yaml
stratum:
  web:
    error:
      statuses:
        ORD-0001: 409
        ORD-0002: 503
```

```java
return Mono.error(new StratumException(OrderErrorCode.DUPLICATE_ORDER, "order " + orderId));
```

A status must be an error, from `400` to `599`. Any other value stops startup with `SX-CORE-1003`,
which names the property but not the code or the value.

## Properties

Every `stratum.web.*` key, and the Spring Boot keys this starter gives a default, are listed once,
for both web starters, in [Web → Properties](../../docs/modules/web.md#properties). Your IDE
completes each and shows its description.

## Configure in Java

The settings can also be set in Java, and Java wins over the files: write a class that implements
`WebPropertiesCustomizer` and list it in `src/main/resources/META-INF/spring.factories`. The
[Spring MVC starter's README](../stratum-starter-webmvc/README.md#configure-in-java) shows the
class, which is the same for both stacks. Declared as a `@Bean` instead, it stops startup with
`SX-CORE-1004`.

## Customize

Each bean below is Stratum's only until you declare your own:

| Your bean | What it replaces |
| --- | --- |
| `WebProperties` | The web settings every bean above uses, worked out from `stratum.web.*` and your `WebPropertiesCustomizer` classes |
| `ErrorResponseFactory` | How every error body is made and how a `StratumException` is logged |
| `ErrorAttributes` | The body of failures outside your exception handlers; yours gets Spring Boot's error handler with its plain JSON and HTML page, as before |
| `ErrorWebExceptionHandler` | All of the error handling outside your exception handlers, Stratum's and Spring Boot's |
| `ProblemDetailIdsInterceptor` | What adds the IDs to the `ProblemDetail` bodies your handlers return |
| `RequestFieldsWebFilter` or `CorrelationIdWebFilter` | The filter that puts the request fields in the logging context, or the one that returns the correlation ID in the response header |
| `RequestRouteListener` | What puts the matched route in the logging context before your handler runs |
| `ResponseTimeouts` or `ResponseTimeoutWebFilter` | How each request's time limit is chosen, or the filter that answers a request that ran out of time and applies `@ResponseTimeout` |
| `RequestBodyLimits` or `RequestBodyLimitWebFilter` | How each request's body size limit is chosen, or the filter that counts the body, applies `@RequestBodyLimit` and checks `Content-Length` |
| `HandlerStartAdapter` | What tells the route listener and the timeout and body limit filters that your handler is about to run |
| `CorrelationIdBaggageDecorator` | What writes a caller's correlation ID into the request's baggage before tracing reads it |
| `RequestLogWebFilter` or `RequestLogWriter` | The filter that writes each request's log line, or what the line holds |
| `RequestFieldsReader`, `CorrelationIdChooser` or `RequestFieldsPropagation` | How the fields are read, how the correlation ID is chosen, or how the fields follow the request across threads |

Stratum's error auto-configuration is ordered before Spring Boot's `ErrorWebFluxAutoConfiguration`, because its
error attributes and error handler replace Spring Boot's. Your own beans, and those of a company framework whose
auto-configuration declares no order, are still registered first and win.

## Switch it on or off

`stratum.web.enabled=true` switches the module on. Leaving it unset is the same as `false`: the
service runs on plain Spring WebFlux, with Spring Boot's own error handling.

Shutdown is graceful, as in Spring Boot (`server.shutdown=graceful`): requests already running get up
to 30 seconds to finish. Stratum does not change it; set `spring.lifecycle.timeout-per-shutdown-phase`
to change the wait.

## Troubleshooting

| What you see | Why, and the fix |
| --- | --- |
| Your code is answered with `500`, and the log says it has no status | Add the code to `stratum.web.error.statuses`. |
| The body has no `traceId` | Tracing is off: add the observability starter and set `stratum.observability.enabled=true`. Or the service sets `spring.reactor.context-propagation=limited`, so the error handler runs without the request's trace ID: remove it to get Stratum's default, `auto`. |
| A failure gets Spring Boot's default body | The module is off, or the service declares its own `ErrorAttributes` or `ErrorWebExceptionHandler` bean. |
| Your handler is not used for an exception | Another advice handles it first. Advice runs in `@Order` order. |
| Spring's own failures, such as an invalid body, get a body without `code`, and with the request path as `instance` | The service sets `spring.webflux.problemdetails.enabled=true`, so Spring's own handler answers them before Stratum's. Remove the property, which is off by default, to get Stratum's body. |
