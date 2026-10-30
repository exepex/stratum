# Web MVC: `stratum-starter-webmvc`

**Status: Partly built.** Every error a Spring MVC service sends is the same
[RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) JSON, with a code, the trace ID
and the correlation ID, and never a stack trace or an exception message. Browsers get an HTML page
with a reference to quote to support. Request fields in logs, the request log, timeouts and the body
size limit are [planned](#planned).

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

The trace and correlation IDs in error bodies come from the
[observability starter](../stratum-starter-observability/README.md). Without it, the bodies have no
IDs, and everything else works the same.

## What you get

### The error body

Every error is sent with the media type `application/problem+json`:

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
| `@Valid` on a body or form, or a constraint on a parameter | `400` | `SX-WEB-1001` |
| A body that is not valid JSON, or a parameter of the wrong type | `400` | `SX-WEB-1002` |
| A path nothing answers | `404` | `SX-WEB-1003` |
| Any other status, such as `405` or an exception annotated `@ResponseStatus` | that status | `SX-WEB-1004` |
| Any other exception | `500` | `SX-WEB-1005`, with a fixed message |

The codes, with how to fix each, are in [Error and warning codes](../../docs/guides/error-codes.md#stratum-starter-webmvc-and-stratum-starter-webflux).

### What is logged

- A `StratumException` with a `4xx` status: one WARN line with its code and message.
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

Your handlers always win. Stratum's handler runs after every `@RestControllerAdvice` of yours, and
answers only what yours leave:

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
- **Your handler handles `StratumException` itself:** yours is used, and the mapping in
  `stratum.web.error.statuses` is not.

Your advice runs before Stratum's whether it has an `@Order` or not: Stratum's has the lowest
precedence, and with equal orders your beans come first.

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
| `stratum.web.error.statuses` | map of code to status | empty | The HTTP status, from `400` to `599`, of each of your error codes. A code with no status is answered with `500` and logged. |

Your IDE completes both and shows their descriptions.

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
| `ErrorResponseFactory` | How every error body is made and how a `StratumException` is logged |
| `ErrorAttributes` | The body of failures outside your controllers, such as `404`; yours gets Spring Boot's error controller and HTML page as before |
| A `View` named `error` | The HTML error page |
| `StratumExceptionHandler` or `ErrorBodyAdvice` | The handler of `StratumException`, or the advice that adds the IDs and the media type |
| A bean named `stratumRequestIdsFilter` | The filter that keeps each request's IDs for its error page, which runs after tracing has ended |

## Switch it on or off

`stratum.web.enabled=true` switches the module on. Leaving it unset is the same as `false`: the
service runs on plain Spring MVC, with Spring Boot's own error handling.

Shutdown is graceful, as in Spring Boot (`server.shutdown=graceful`): requests already running get up
to 30 seconds to finish. Stratum does not change it; set `spring.lifecycle.timeout-per-shutdown-phase`
to change the wait.

## Planned

| Feature | What it adds |
| --- | --- |
| Request fields in logs | Method, route, path, query, client IP and caller on every log line of a request |
| Request log | One JSON line when each request ends, with its status and duration |
| Correlation ID from the caller | The caller's `X-Correlation-Id`, when its format is safe, returned in the response header |
| Timeouts | A request that runs longer than its limit, 10 seconds by default, is answered with `503` |
| Body size limit | A request body larger than the limit is refused with `413` |

The agreed design of each is in [Web](../../docs/modules/web.md).

## Troubleshooting

| What you see | Why, and the fix |
| --- | --- |
| Your code is answered with `500`, and the log says it has no status | Add the code to `stratum.web.error.statuses`. |
| The body has no `traceId` | Tracing is off. Add the observability starter and set `stratum.observability.enabled=true`. |
| A failure gets Spring Boot's default body | The module is off, or the service declares its own `ErrorAttributes` bean. |
| Your handler is not used for an exception | Another advice handles it first. Advice runs in `@Order` order; Stratum's runs last. |
| A bad `@RequestParam` gets `500` instead of `400` | The controller class has `@Validated`, which makes Spring check parameters its old way, as in plain Spring Boot. Remove `@Validated` from the class; Spring MVC checks constraints on parameters without it. |
| Spring's own failures, such as an invalid body, get a body without `code`, and with the request path as `instance` | The service sets `spring.mvc.problemdetails.enabled=true`, so Spring's own handler answers them before Stratum's. Remove the property, which is off by default, to get Stratum's body. |
