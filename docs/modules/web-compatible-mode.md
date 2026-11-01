# Web: backward-compatible mode

**Status: Built**, in `stratum-starter-webmvc` and `stratum-starter-webflux`, when
`stratum.web.enabled=true`.

[← Web](web.md) · [Documentation home](../README.md) · Property: `stratum.web.backward-compatible`

## What it does

A service that adds Stratum keeps the API contract it has with its consumers.
`stratum.web.backward-compatible` is `true` by default, so the error bodies its consumers read
stay the same and no request is cut off or refused by a new limit. Each consumer moves to Stratum's
standard error body when it is ready, and the service switches the property to `false` once all
have moved. A new service sets `false` from the start.

## Error bodies

`stratum.web.backward-compatible` is `true` by default, so adding the starter to a running service
never changes the error body its consumers already read. An error that Spring or Spring Boot
answers, such as an unknown path, a failed validation or an unexpected exception, keeps Spring
Boot's body, as before Stratum:

```json
{ "timestamp": "2026-11-01T10:15:30.000+00:00", "status": 404, "error": "Not Found", "path": "/orders/42" }
```

Two statuses differ from a service without Stratum, so that Spring MVC and WebFlux answer the
same, correct status. On WebFlux, a failure that carries its own status, such as
`new ErrorResponseException(HttpStatus.BAD_REQUEST)`, is answered with that status, 400, where
Spring Boot alone answers 500. On Spring MVC, a status that Spring's `HttpStatus` does not list,
such as 499, is answered with that status line, where Spring Boot alone answers 500 with 499 in
the body. Check that no consumer relies on the old 500 in either case.

Stratum's standard body, described in [The error format](web.md#the-error-format), is sent instead
when:

- the consumer asks for it with `Accept: application/problem+json`, request by request, so each
  consumer moves when it is ready;
- the error is Stratum's own, which no consumer has seen before: a `StratumException` or an
  exception that implements `FailureWithErrorCode`, a timeout or a body too large;
- the service sets `stratum.web.backward-compatible=false`, which a new service does from the start.

Spring Boot's body holds what `spring.web.error.include-*` says, as before Stratum. A browser still
gets Stratum's page, with the trace ID as the reference to quote to support.

`traceId` and `correlationId` are still added to a `ProblemDetail` your own handler returns, and
every response still has the `X-Correlation-Id` header. Once every consumer sends the header, set
the property to `false`.

## Knowing when to switch

While the mode is on, the service logs one INFO line at every start that says what the mode does:

```text
INFO  BackwardCompatibleModeReport : stratum.web.backward-compatible is true: errors that Spring answers keep Spring Boot's body unless the consumer sends Accept: application/problem+json, and no time or body size limit applies unless set. The counter stratum.web.requests.without.standard.errors shows which routes still answer with Spring Boot's body; set the property to false once it stays at 0.
```

When the service has Micrometer, as the observability starter and Spring Boot's actuator bring it,
the counter `stratum.web.requests.without.standard.errors` counts each error answer that kept
Spring Boot's JSON body because the consumer did not ask for `application/problem+json`. Its
`route` tag is the route the request matched, such as `/orders/{orderId}`, or `UNKNOWN` for a path
the service has no route for. A browser's page and Stratum's own body are not counted. In
Prometheus, the routes that answered with Spring Boot's body in the last 7 days:

```text
max by (route) (max_over_time(stratum_web_requests_without_standard_errors_total[7d])) > 0
```

A route's series appears only with its first such answer, so the query checks whether a series
exists in the window; `increase()` would miss a route whose only answer started its series. A pod
that answered once keeps its series until it restarts, so the query can only err towards "not yet".
Once it returns nothing for as long as your slowest consumer takes to call each route, set
`stratum.web.backward-compatible=false`.

## Stack traces in the error body

A stack trace or an exception class in an error body is a security leak: it tells any caller which
libraries, classes and code paths the service has. When `spring.web.error.include-stacktrace` is
`always` or `on-param`, or `spring.web.error.include-exception` is `true`, Spring Boot's body
shows them, and Stratum logs one WARN line at every start while backward-compatible mode is on:

```text
WARN  BackwardCompatibleModeReport : Security leak: errors keep Spring Boot's body because stratum.web.backward-compatible is true, and spring.web.error.include-stacktrace=always with spring.web.error.include-exception=false show stack traces or exception classes to API callers. Set them to never and false.
```

Set both back to Spring Boot's defaults, `never` and `false`, to stop the leak. Stratum's standard
body never shows either, whatever these settings say.

## Time and body size limits

With nothing set, a backward-compatible service has no time limit and no body size limit, and the
WebFlux starter adds no multipart upload limit, because a request that worked before Stratum must
not start failing with `503` or `413`. With `stratum.web.backward-compatible=false`, every request
has 10 seconds until its response starts and a body of up to 256KB, and a WebFlux multipart upload
may use 10MB of disk per part and have 50 parts.

A limit the service sets always applies, in both modes: `stratum.web.timeout.default-limit`,
`stratum.web.timeout.routes`, `@ResponseTimeout`, `stratum.web.body-limit.default-limit`,
`stratum.web.body-limit.routes` and `@RequestBodyLimit`. Give each new endpoint of a
backward-compatible service its own limit, so it has one from its first release:

```java
@ResponseTimeout(2)
@GetMapping("/orders/{id}")
Order order(@PathVariable String id) {
  return orders.find(id);
}
```

## Properties

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `stratum.web.backward-compatible` | boolean | `true` | Whether errors that Spring answers keep Spring Boot's body, unless the consumer sends `Accept: application/problem+json`, and whether the time and body size limits are off unless set. |

```properties
stratum.web.backward-compatible=false
```

```yaml
stratum:
  web:
    backward-compatible: false
```

In Java, return a copy with `backwardCompatible(false)` from a `WebPropertiesCustomizer`, as
[Configure in Java](web.md#configure-in-java) shows.
