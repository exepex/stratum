# Web MVC: `stratum-starter-webmvc`

**Status: Built.** Every error a Spring MVC service sends is the same
[RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) JSON, with a code, the trace ID
and the correlation ID, and never a stack trace or an exception message. Browsers get an HTML page
with a reference to quote to support. Every log line of a request carries its method, route, path,
client IP and correlation ID, and each request writes one log line when it is answered. A request
that runs out of time is answered with `503`, and one whose body is too large with `413`.

This page covers this starter only. The design both web starters share is in
[Web](../../docs/modules/web.md), and the reactive starter in
[WebFlux](../stratum-starter-webflux/README.md). How Stratum works as a whole is in the
[documentation](../../docs/README.md).

[← Documentation home](../../docs/README.md) · Property prefix: `stratum.web`

## What it does

Without it, each service decides what its errors look like. One sends Spring Boot's default JSON,
another a home-made shape, and a third leaks an exception message. Callers need special handling
per service, and support cannot find the failed request in the logs. With it, every error from
every service has the same body and carries the trace ID, so one search finds the request.

You keep writing controllers and exception handlers the usual way. Stratum only answers what your
own handlers leave.

## Add it

```xml
<dependency>
  <groupId>com.exepex.stratum</groupId>
  <artifactId>stratum-starter-webmvc</artifactId>
</dependency>
```

It brings Spring MVC on Tomcat, so do not add `spring-boot-starter-webmvc` as well. A reactive
service uses `stratum-starter-webflux` instead; never add both. The `stratum-starter-microservice`
and `stratum-starter-web-ui` bundles already include it. The version comes from Stratum's parent or
BOM ([installation](../../docs/getting-started/installation.md)).

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
[observability starter](../stratum-starter-observability/README.md). Without it, there are no IDs,
and everything else works the same.

## What you get

### The error body

With `stratum.web.backward-compatible`, `true` by default, an error that Spring answers keeps
Spring Boot's body unless the consumer sends `Accept: application/problem+json`, so existing
consumers see no change. Errors with a code, timeouts and bodies too large always get the body
below. See [Existing consumers keep their error body](../../docs/modules/web-compatible-mode.md).

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

A failed validation adds `details`, one entry per invalid field:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "The request has invalid fields",
  "code": "SX-WEB-1001",
  "severity": "ERROR",
  "details": [
    { "field": "quantity", "message": "must be greater than or equal to 1" },
    { "field": "account", "message": "is not valid" }
  ],
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "correlationId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

A validation message that quotes the rejected value, through `${validatedValue}` in the
annotation or in `ValidationMessages.properties`, is replaced with `is not valid`, so an IBAN or a
password typed into the wrong field never comes back in the body. A map key in a field path, which
the caller chose, is shown as `[*]`, as in `quantitiesByCustomer[*]`; list positions stay. Stratum's
body never holds a stack trace, an exception message, a class name or the request path,
whatever `spring.web.error.include-*` says.

### What each failure gets

| The request fails with | Status | `code` |
| --- | --- | --- |
| Your `StratumException`, its code mapped in `stratum.web.error.statuses` | the mapped status | your code, such as `ORD-0001` |
| Your `StratumException`, its code not mapped | `500` | your code |
| Your exception that implements [`FailureWithErrorCode`](../../docs/guides/error-codes.md#existing-exceptions-a-migration-path) | as a `StratumException` with its code | your code |
| `@Valid` on a body or form, or a constraint on a parameter | `400` | `SX-WEB-1001` |
| A body that is not valid JSON, or a parameter of the wrong type | `400` | `SX-WEB-1002` |
| A path nothing answers | `404` | `SX-WEB-1003` |
| Any other status, such as `405` or an exception annotated `@ResponseStatus` | that status | `SX-WEB-1004` |
| Any other exception | `500` | `SX-WEB-1005`, with a fixed message |
| A request whose response had not started within its [time limit](#timeouts) | `stratum.web.timeout.status`, `503` by default | `SX-WEB-1007` |
| A request whose body is larger than its [limit](#body-size-limit) | `413` | `SX-WEB-1008` |

The codes, with how to fix each, are in [Error and warning codes](../../docs/guides/error-codes.md#stratum-starter-webmvc-and-stratum-starter-webflux).

### What is logged

- A `StratumException`, or an exception that implements `FailureWithErrorCode`, with a `4xx`
  status: one WARN line with its code and message.
- A `StratumException` with a `5xx` status: one ERROR line with its stack trace. When its code has
  no status in `stratum.web.error.statuses`, the line says so, so a missing mapping shows up in
  testing.
- Any other exception: Spring and Tomcat log it at ERROR with its stack trace, as without Stratum.
- `400`, `404` and the other failures Spring answers itself are not logged, as in Spring Boot. To
  log Spring's reason, set `spring.mvc.log-resolved-exception=true`.

### The HTML error page

A browser, or any caller whose `Accept` header names `text/html`, gets a short page with the status
and a reference, which is the trace ID. That includes your `StratumException`, with its mapped
status. A user can quote it, and support finds the request in the logs:

```text
Not Found
The request could not be completed (status 404).
Reference: 4bf92f3577b34da6a3ce929d0e0e4736. Quote it to support so they can find what went wrong.
```

Your own error templates, such as `templates/error.html` or `templates/error/404.html`, win, and so
does a bean named `error`. `spring.web.error.whitelabel.enabled=false` turns the page off, as it
turns off Spring Boot's.

## Your own error handling

Your handlers always win. Stratum's resolver runs right after every `@ExceptionHandler` of yours
and answers only a failure with an error code that they leave. It runs before Spring MVC's
resolver for `@ResponseStatus`, so a code mapped in `stratum.web.error.statuses` wins over the
annotation, as on WebFlux:

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

Your advice runs before Stratum's whether it has an `@Order` or not: Stratum's answer is a
`HandlerExceptionResolver` with the lowest precedence, which Spring MVC asks only after every
`@ExceptionHandler`.

## Request fields and the correlation ID

Every log line written while a request runs carries its `httpMethod`, `httpRoute`, `httpPath`,
`httpQuery`, `clientIp` and `userAgent`, on every thread the request's work runs on, such as an
`@Async` method. Device, OS and browser fields, and the client's country and city, are off until you
turn them on. The caller's `X-Correlation-Id` is used when its format is safe, sent back in the
response header and passed on to the services this one calls; when there is none, the trace ID is
the correlation ID. Each field, the client IP behind proxies and the settings are described in
[Web](../../docs/modules/web.md#request-fields-in-logs).

Each request also writes one log line when it is answered, such as
`GET /orders/{orderId} answered 200 in 12 ms`, with `httpStatus` and `durationMs` as fields. The
actuator's paths and static files write none. The headers you name, and the claims you list from the
caller's token when you turn it on, can be added to it; credentials never are. See
[Web](../../docs/modules/web.md#request-log).

Response times and error rates per API come from the observability starter's metrics. The SLA
dashboard recipe, with PromQL examples, is in [Web](../../docs/modules/web.md#sla-dashboards).

## Timeouts

Each request has 10 seconds until its response starts, unless you set another limit; while
`stratum.web.backward-compatible` is `true`, the default, it has no limit unless you set one. A request that
runs out of time is answered with `503` and `SX-WEB-1007`, and the service logs a WARN line. A
response that has started, such as a stream, is never cut off. Change the limits in
`application.properties`:

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
Report report(@PathVariable String reportId) {
  return reports.build(reportId);
}
```

When the time is up, Stratum interrupts the request's thread. A blocking call that honours
interrupts, such as the JDK's `HttpClient` or a socket on a virtual thread, stops, and the caller
gets `503`. A call that ignores interrupts, such as a database query waiting on its socket on a
platform thread, runs on, and the caller gets `503` when it ends; give each remote call a timeout
of its own. For a `Callable` or `DeferredResult`, the limit ends when the handler method returns.
An upload API answers only once it has read the whole body, so give it
`@ResponseTimeout(0)` or a long limit; see [Uploads](../../docs/modules/web-timeouts.md#uploads).
The details, with `0` for no limit and the status, are in [Response timeouts](../../docs/modules/web-timeouts.md).

## Body size limit

Each request body may have 256KB, unless you set another limit; while
`stratum.web.backward-compatible` is `true`, the default, it has no limit unless you set one. A larger body is answered with
`413` and `SX-WEB-1008`, and the service logs a WARN line. A body sent without `Content-Length` is
counted as it arrives and stopped at the limit, before it is held in memory. Raise the limit for
uploads by path, in `application.properties`:

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
DocumentId upload(InputStream body) {
  return documents.store(body);
}
```

Spring Boot's own multipart limits, 1MB per file and 10MB per upload, apply as well, so a
multipart upload API also raises `spring.servlet.multipart.max-file-size` and `max-request-size`.
Forms and multipart uploads are checked by their `Content-Length` only. The details, with `0` for no limit, are in
[Request body size limit](../../docs/modules/web-body-limit.md).

## Map your codes to statuses

Throw a `StratumException` with your own [error code](../../docs/guides/error-codes.md#using-codes-in-your-service),
and map the code to its HTTP status. Write each code as it is, capitals and dashes included.

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
throw new StratumException(OrderErrorCode.DUPLICATE_ORDER, "order " + orderId);
```

A status must be an error, from `400` to `599`. Any other value stops startup with `SX-CORE-1003`,
which names the property but not the code or the value.

## Properties

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `stratum.web.enabled` | boolean | `false` | Switches the module on. Until it is `true`, the starter only brings Spring MVC. |
| `stratum.web.backward-compatible` | boolean | `true` | Whether errors that Spring answers keep Spring Boot's body unless the consumer sends `Accept: application/problem+json`. Set it to `false` to give every error the body above. |
| `stratum.web.error.statuses` | map of code to status | empty | The HTTP status, from `400` to `599`, of each of your error codes. A code with no status is answered with `500` and logged. |
| `stratum.web.correlation.accept-from-caller` | boolean | `true` | Whether a caller's correlation ID is used. Set it to `false` on a service that faces the internet. |
| `stratum.web.client-ip.trusted-proxies` | list of address ranges | the private ranges | The load balancers and proxies whose `X-Forwarded-For` header is trusted. |
| `stratum.web.user-agent.parse` | boolean | `false` | Whether device, OS and browser are read from the user agent. |
| `stratum.web.user-agent.cache-size` | integer | `10000` | How many different user agents are kept already parsed. |
| `stratum.web.user-agent.request-client-hints` | boolean | `false` | Whether responses ask browsers for their device model and full OS version. |
| `stratum.web.client-location.database` | file path | none | Your MaxMind `.mmdb` file, for the client's country and city. |
| `stratum.web.request-fields.max-value-length` | integer | `512` | The most characters a request field keeps. |
| `stratum.web.request-log.enabled` | boolean | `true` | Whether each request writes one log line when it is answered. |
| `stratum.web.request-log.skip-paths` | list of path patterns | `/actuator/**` | Paths whose requests write no log line. |
| `stratum.web.request-log.request-headers` | map of header to field name | empty | The request headers written in the line, as fields. |
| `stratum.web.request-log.response-headers` | map of header to field name | empty | The response headers written in the line, as fields. |
| `stratum.web.request-log.denied-headers` | list of header names | empty | Headers never written, on top of the credential headers. |
| `stratum.web.request-log.token-payload` | boolean | `false` | Whether the line carries the listed claims of the caller's bearer JWT. |
| `stratum.web.request-log.token-claims` | list of claim names | `sub`, `azp`, `client_id`, `scope` | The claims written when `token-payload` is `true`. |
| `stratum.web.timeout.default-limit` | duration | none, or `10s` when `backward-compatible` is `false` | How long any request may take until its response starts. `0` means no limit. |
| `stratum.web.timeout.routes` | map of path pattern to duration | empty | The limit of the requests whose path matches a pattern, such as `/reports/**`. |
| `stratum.web.timeout.status` | HTTP status | `503` | The status a request that ran out of time is answered with. |
| `stratum.web.body-limit.default-limit` | data size | none, or `256KB` when `backward-compatible` is `false` | How large any request's body may be. `0` means no limit. |
| `stratum.web.body-limit.routes` | map of path pattern to data size | empty | The limit of the bodies of the requests whose path matches a pattern, such as `/uploads/**`. |

Your IDE completes each and shows its description. The full list, with the allowed values, is in
[Web](../../docs/modules/web.md#properties).

## Configure in Java

The settings can also be set in Java, and Java wins over the files. The class receives the
settings from your files, with the defaults for anything unset:

```java
package com.acme.orders.config;

public class OrdersWebSettings implements WebPropertiesCustomizer {

  @Override
  public WebProperties customize(WebProperties properties) {
    return properties.toBuilder()
        .error(
            properties.error().toBuilder()
                .statuses(Map.of(OrderErrorCode.DUPLICATE_ORDER.getCode(), 409))
                .build())
        .build();
  }
}
```

List the class in `src/main/resources/META-INF/spring.factories`:

```properties
com.exepex.stratum.web.WebPropertiesCustomizer=com.acme.orders.config.OrdersWebSettings
```

It needs a public no-argument constructor. Declared as a `@Bean` instead, it stops startup with
`SX-CORE-1004`. To set the switch in Java, see
[default properties](../../docs/configuration/overriding-defaults.md#switches-shared-properties-and-spring-boot-properties-in-java).

## Customize

Each bean below is Stratum's only until you declare your own:

| Your bean | What it replaces |
| --- | --- |
| `WebProperties` | The web settings every bean above uses, worked out once from `stratum.web.*` and your `WebPropertiesCustomizer` classes |
| `ErrorResponseFactory` | How every error body is made and how a `StratumException` is logged |
| `ErrorAttributes` | The body of failures outside your controllers, such as `404`; yours gets Spring Boot's error controller and HTML page as before |
| A `View` named `error` | The HTML error page |
| `FailureWithErrorCodeResolver` or `ErrorBodyAdvice` | The answer to `StratumException` and `FailureWithErrorCode`, or the advice that adds the IDs and the media type |
| A bean named `stratumRequestIdsFilter` | The filter that keeps each request's IDs for its error page, which runs after tracing has ended |
| A bean named `stratumRequestFieldsFilter` or `stratumCorrelationIdFilter` | The filter that puts the request fields in the logging context, or the one that returns the correlation ID in the response header |
| A bean named `stratumCorrelationIdBaggageFilter` | The filter that writes a caller's correlation ID into the request's baggage before tracing reads it |
| A bean named `stratumRequestLogFilter` or `RequestLogWriter` | The filter that writes each request's log line, or what the line holds |
| `RequestRouteInterceptor` | What puts the matched route in the logging context |
| `RequestBodyLimits`, a bean named `stratumRequestBodyLimitFilter`, or `RequestBodyLimitInterceptor` | How each request's body size limit is chosen, the filter that counts the body, or what applies `@RequestBodyLimit` and checks `Content-Length` |
| `ResponseTimeouts`, a bean named `stratumResponseTimeoutFilter`, or `ResponseTimeoutInterceptor` | How each request's time limit is chosen, the filter that answers a request that ran out of time, or what applies `@ResponseTimeout` |
| `RequestFieldsReader`, `CorrelationIdChooser` or `RequestFieldsPropagation` | How the fields are read, how the correlation ID is chosen, or how the fields follow the request across threads |

Stratum's error auto-configuration is ordered before Spring Boot's `ErrorMvcAutoConfiguration`, because its
error attributes and HTML error page replace Spring Boot's. Your own beans, and those of a company framework whose
auto-configuration declares no order, are still registered first and win.

## Switch it on or off

`stratum.web.enabled=true` switches the module on. Leaving it unset is the same as `false`: the
service runs on plain Spring MVC, with Spring Boot's own error handling.

Shutdown is graceful, as in Spring Boot (`server.shutdown=graceful`): requests already running get up
to 30 seconds to finish. Stratum does not change it; set `spring.lifecycle.timeout-per-shutdown-phase`
to change the wait.

## Troubleshooting

| What you see | Why, and the fix |
| --- | --- |
| Your code is answered with `500`, and the log says it has no status | Add the code to `stratum.web.error.statuses`. |
| A request runs far past its time limit before it is answered with `503` | A call in it ignores interrupts, such as a query waiting on its socket. Set a timeout on that call, such as the database's query timeout. |
| The body has no `traceId` | Tracing is off. Add the observability starter and set `stratum.observability.enabled=true`. |
| A failure gets Spring Boot's default body | The module is off, or the service declares its own `ErrorAttributes` bean. |
| Your handler is not used for an exception | Another advice handles it first. Advice runs in `@Order` order; Stratum's runs last. |
| A bad `@RequestParam` gets `500` instead of `400` | The controller class has `@Validated`, which makes Spring check parameters its old way, as in plain Spring Boot. Remove `@Validated` from the class; Spring MVC checks constraints on parameters without it. |
| Spring's own failures, such as an invalid body, get a body without `code`, and with the request path as `instance` | The service sets `spring.mvc.problemdetails.enabled=true`, so Spring's own handler answers them before Stratum's. Remove the property, which is off by default, to get Stratum's body. |
