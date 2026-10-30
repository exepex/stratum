# Web: `stratum-starter-webmvc` and `stratum-starter-webflux`

**Status: Partly built.** Error responses are built for Spring MVC, in `stratum-starter-webmvc`;
see its [README](../../starters/stratum-starter-webmvc/README.md). Everything else on this page,
and every feature of `stratum-starter-webflux`, is the agreed design and not built yet.

[← Documentation home](../README.md) · Property prefix: `stratum.web` · Decision record:
[ADR 0006](../adr/0006-separate-mvc-and-webflux-starters.md)

## What it does

Without these starters each service decides for itself what an error looks like, what a log line
of a request holds and how long a request may run. Callers then need special handling per service,
and support cannot find one request across services. With them, every service answers and logs the
same way, whether it is reactive or not.

## Pick your starter

Spring has two web stacks, and Stratum has one starter for each. Add exactly one:

| Your service | Add | It brings |
| --- | --- | --- |
| Is written the usual way, with methods that return values (most services) | `stratum-starter-webmvc` | Spring MVC on Tomcat |
| Is reactive, with methods that return `Mono` or `Flux` | `stratum-starter-webflux` | Spring WebFlux on Netty |

```xml
<dependency>
  <groupId>com.exepex.stratum</groupId>
  <artifactId>stratum-starter-webmvc</artifactId>
</dependency>
```

Do not add `spring-boot-starter-webmvc` or `spring-boot-starter-webflux` as well: the starter
already brings it. Never add both Stratum web starters to one service. With both stacks on the
classpath, Spring Boot starts Spring MVC and ignores WebFlux.

The [bundles](bundles.md) choose for you: `stratum-starter-microservice` and
`stratum-starter-web-ui` bring `stratum-starter-webmvc`, and
`stratum-starter-reactive-microservice` brings `stratum-starter-webflux`.

Both starters are the module `web`. They do nothing until you switch them on, in
`application.properties`:

```properties
stratum.web.enabled=true
```

or in `application.yml`:

```yaml
stratum:
  web:
    enabled: true
```

Both read the same `stratum.web.*` settings, so a service that moves from one stack to the other
keeps its configuration.

## What you will get

Every feature works the same on both stacks.

| Feature | Behaviour |
| --- | --- |
| Error responses (built for Spring MVC) | Every error is [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) JSON with a code, the trace ID and the correlation ID. Never a stack trace, exception message or class name |
| HTML error page (built for Spring MVC) | A browser gets an HTML page with a reference number (the trace ID) that the user can quote to support |
| Request fields in logs | Every log line written during a request carries its method, route, path, query, client IP and caller, as separate fields |
| Request log | One JSON line when each request ends, with its status and duration. Health checks and static files are left out |
| Headers in logs | The request and response headers a team names are logged as fields. `Authorization`, `Cookie`, `Set-Cookie` and `X-Api-Key` never are |
| Token payload in logs | When a team turns it on, the payload of the caller's JWT is logged. The token's header and signature never are |
| Correlation ID | Taken from the caller's `X-Correlation-Id` header when it has a safe format, otherwise the trace ID; always returned in the response header |
| Timeouts | A request that runs longer than its limit (10 seconds by default) is answered with `503` |
| Body size limit | A request body larger than the limit is refused with `413` |

Response times and error counts per API come from the
[observability starter](../../starters/stratum-starter-observability/README.md), which records
every request already.

### The error format

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Order already exists: order 42",
  "code": "ORD-0001",
  "severity": "ERROR",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "correlationId": "checkout-777"
}
```

A validation error has the status `400` and adds a `details` list, with one entry per invalid
field. The codes are listed in [Error and warning codes](../guides/error-codes.md).

### Your own error handling

- **Your own `@RestControllerAdvice` always wins.** Stratum handles only what your handlers leave,
  and never changes a body your handler returns. If your handler returns Spring's `ProblemDetail`,
  Stratum only adds `traceId` and `correlationId` to it.
- **`StratumException` with your own code:** you map each code to an HTTP status in
  `stratum.web.error.statuses`. A code with no status is answered with `500` and logged, so a
  missing mapping shows up in testing.
- **An exception annotated `@ResponseStatus`** gets that status and Stratum's error body.
- **Anything else** is answered with `500` and a fixed message; the real error goes to the log
  only.

## Properties

The settings decided so far. Each works from `.properties`, `.yml` and Java. The first two are
built for Spring MVC; the others are planned.

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `stratum.web.enabled` | boolean | `false` | Switches the module on. Until it is `true`, the starter sets up nothing of its own. |
| `stratum.web.error.statuses` | map of code to status | empty | The HTTP status of each of your error codes, such as `ORD-0001: 409`. |
| `stratum.web.correlation.accept-from-caller` | boolean | `true` | Whether a caller's `X-Correlation-Id` is used. Set it to `false` on a service that faces the internet, to always use the trace ID. |
| `stratum.web.timeout.default` | duration | `10s` | How long any request may run. |
| `stratum.web.timeout.status` | HTTP status | `503` | The status of a request that ran out of time. |
| `stratum.web.request-log.enabled` | boolean | `true` | Whether each request writes one log line when it ends. |
| `stratum.web.request-log.skip-paths` | list | `/actuator/**` and static files | Paths whose requests write no log line. |

## Configure in Java

Every setting can also be set in Java, through a `WebPropertiesCustomizer` class listed in
`META-INF/spring.factories`, as for the other modules, and Java wins. The
[web MVC starter's README](../../starters/stratum-starter-webmvc/README.md#configure-in-java) shows
an example. A single API will also be able to set its own timeout with an annotation on its method;
that is planned.

## Switch it on or off

`stratum.web.enabled=true` switches the module on. Leaving it unset is the same as `false`. With
the module off, the starter still brings its web stack, so the service runs as plain Spring Boot.
