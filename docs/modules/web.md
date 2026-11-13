# Web: `stratum-starter-webmvc` and `stratum-starter-webflux`

**Status: Built.** Error responses, request fields in logs, the correlation ID, the request
log, timeouts and the body size limit are built for both stacks; the error responses are described
in the READMEs of [`stratum-starter-webmvc`](../../starters/stratum-starter-webmvc/README.md) and
[`stratum-starter-webflux`](../../starters/stratum-starter-webflux/README.md), the request log in
[Request log](web-request-log.md), timeouts in
[Response timeouts](web-timeouts.md), backward-compatible mode in
[Backward-compatible mode](web-compatible-mode.md), the body size limit in
[Request body size limit](web-body-limit.md), the answers to database failures in
[Database failures](web-database-errors.md), and the rest on this page.

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
  <groupId>io.github.exepex.stratum</groupId>
  <artifactId>stratum-starter-webmvc</artifactId>
</dependency>
```

Do not add `spring-boot-starter-webmvc` or `spring-boot-starter-webflux` as well: the starter
already brings it. Never add both Stratum web starters to one service. With both stacks on the
classpath, Spring Boot starts Spring MVC and ignores WebFlux.

The [bundles](bundles.md) choose for you: `stratum-starter-microservice` and
`stratum-starter-web-ui` bring `stratum-starter-webmvc`, and
`stratum-starter-reactive-microservice` brings `stratum-starter-webflux`.

Both starters are the module `web`. It is on as soon as either starter is on the classpath; no line
is needed. To turn it off, set in `application.properties`:

```properties
stratum.web.enabled=false
```

or in `application.yml`:

```yaml
stratum:
  web:
    enabled: false
```

Both read the same `stratum.web.*` settings, so a service that moves from one stack to the other
keeps its configuration.

## What you will get

Every feature works the same on both stacks.

| Feature | Behaviour |
| --- | --- |
| Error responses (built) | Stratum's errors are [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) JSON with a code, the trace ID and the correlation ID. Never a stack trace, exception message or class name. Errors that Spring answers keep Spring Boot's body only while `stratum.backward-compatible=true`; see [Backward-compatible mode](web-compatible-mode.md) |
| HTML error page (built) | A browser gets an HTML page with a reference number (the trace ID) that the user can quote to support |
| Request fields in logs (built) | Every log line written during a request carries its method, route, path, query, client IP and user agent, as separate fields. Device, OS, browser and the client's country and city when you turn them on |
| Request log (built) | One line when each request is answered, with its status and duration. Health checks and static files are left out |
| Headers in logs (built) | The request and response headers a team names are logged as fields. `Authorization`, `Cookie`, `Set-Cookie` and `X-Api-Key` never are |
| Token claims in logs (built) | When a team turns it on, the claims it lists from the caller's JWT, such as `sub` and `scope`, are logged. Other claims, the token's header and its signature never are |
| Correlation ID (built) | Taken from the caller's `X-Correlation-Id` header when it has a safe format, otherwise the trace ID; returned in the response header. Needs the observability starter |
| Timeouts (built) | A request whose response has not started within its limit, 10 seconds by default, or none while `stratum.backward-compatible=true`, is answered with `503`. One API can have its own limit. See [Response timeouts](web-timeouts.md) |
| Body size limit (built) | A request body larger than its limit, 256KB by default, or none while `stratum.backward-compatible=true`, is refused with `413`. One path or API can have its own limit. See [Request body size limit](web-body-limit.md) |

Response times and error counts per API come from the
[observability starter](../../starters/stratum-starter-observability/README.md), which records
every request already; [SLA dashboards](#sla-dashboards) shows how to chart them.

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

### Existing consumers keep their error body

With `stratum.backward-compatible=true`, adding the starter to a running service never changes
what its consumers see: errors that Spring answers keep Spring Boot's body, and there is no default
time or body size limit. A new service leaves it unset, at `false`. See
[Backward-compatible mode](web-compatible-mode.md).

### Your own error handling

- **Your own `@RestControllerAdvice` always wins.** Stratum handles only what your handlers leave,
  and never changes a body your handler returns. If your handler returns Spring's `ProblemDetail`,
  Stratum only adds `traceId` and `correlationId` to it.
- **`StratumException` with your own code:** you map each code to an HTTP status in
  `stratum.web.error.statuses`. A code with no status is answered with `500` and logged, so a
  missing mapping shows up in testing. A Stratum starter can give its own codes a default status
  there, such as the HTTP client's `SX-HTTPC-0001: 504`; your value wins. Your existing exceptions can implement
  [`FailureWithErrorCode`](../guides/error-codes.md#existing-exceptions-a-migration-path) to be
  answered the same way while you move them to `StratumException`. That changes what their
  consumers get, even in backward-compatible mode; see
  [Marking an existing exception](web-compatible-mode.md#marking-an-existing-exception).
- **An exception annotated `@ResponseStatus`** gets that status, with Spring Boot's body in
  [backward-compatible mode](web-compatible-mode.md) and Stratum's body once it is off.
- **A database failure** that is a timeout, a wait for a connection, a lock timeout or a deadlock
  is answered with `503` and `SX-WEB-1010`; a duplicate key or a row changed meanwhile with `409`
  and `SX-WEB-1011`. See [Database failures](web-database-errors.md).
- **Anything else** is answered with `500`. Stratum's body has a fixed message, and the real error
  goes to the log only.

## Request fields in logs

With the module on, every log line written while a request runs carries these fields, in
SLF4J's MDC (the logging context, which the logging starter writes as JSON fields):

| Field | Example | Where it comes from |
| --- | --- | --- |
| `httpMethod` | `GET` | The request |
| `httpRoute` | `/orders/{orderId}` | The route Spring matched, once it has matched one |
| `httpPath` | `/orders/42` | The request, as the client encoded it |
| `httpQuery` | `expand=lines` | The request, without the `?`. Parameter names are decoded, such as `%74oken` to `token`, so redaction finds them; values stay as sent |
| `clientIp` | `203.0.113.7` | The client's address; see [the client IP](#the-client-ip) |
| `userAgent` | `Mozilla/5.0 (...)` | The `User-Agent` header, as sent |
| `deviceClass`, `deviceName` | `Phone`, `Google Pixel 7` | Only with `stratum.web.user-agent.parse=true` |
| `osName`, `osVersion` | `Android`, `14.0.0` | Only with `stratum.web.user-agent.parse=true` |
| `browserName`, `browserVersion` | `Chrome`, `120` | Only with `stratum.web.user-agent.parse=true` |
| `clientCountry`, `clientCity` | `NL`, `Amsterdam` | Only with `stratum.web.client-location.database` set |

Nothing is ever made up: a value the request does not have, such as a query or a user agent, is
left out. Control characters, such as line breaks, are replaced with spaces, and each value is cut
to `stratum.web.request-fields.max-value-length` characters (512 by default), so a client cannot
forge a log line or flood the logs. With the logging starter on, its redaction masks secrets in
these fields as in any other, such as `password=...` in a query. Never put secrets in a URL; send
them in a header or the body.

The fields follow the request's work to other threads: `@Async` methods and Spring's executors on
Spring MVC, every Reactor operator on WebFlux, as described in
[log fields across threads](../guides/context-across-threads.md). On WebFlux this needs Reactor's
automatic context propagation, which the logging and observability starters turn on. When each
Spring MVC request ends, the module cleans the server thread's logging context
([Web MVC](../../starters/stratum-starter-webmvc/README.md#a-clean-log-context-after-each-request)).
Caller fields, such as the calling service and user, are planned for the security starter, which
reads them from a verified token only ([issue #307](https://github.com/exepex/stratum/issues/307)).

### The client IP

Behind a load balancer, the request's own address is the load balancer's. Stratum then reads the
`X-Forwarded-For` header from right to left, and the first address that is not one of your trusted
proxies is the client's. A request that does not come from a trusted proxy is taken as it arrives,
so a client cannot claim another address by sending the header itself.

The trusted proxies are `stratum.web.client-ip.trusted-proxies`, which defaults to the private and
loopback ranges, where load balancers inside your own network live. If your service sits behind a
CDN or a load balancer with a public address, add its ranges; until then the client IP is that
proxy's address, which is wrong but safe. Platform teams can set it for every service at once, with
an environment variable such as
`STRATUM_WEB_CLIENTIP_TRUSTEDPROXIES=10.0.0.0/8,203.0.113.0/24` or a company customizer.

On Spring MVC with `server.forward-headers-strategy=native`, which Spring Boot turns on by itself on
Kubernetes, Tomcat reads `X-Forwarded-For` first, trusting its own list,
`server.tomcat.remoteip.internal-proxies`. That list defaults to the private and loopback ranges
and also trusts the link-local (`169.254.0.0/16`, `fe80::/10`) and carrier-grade NAT
(`100.64.0.0/10`) ranges. Set the two lists to match. WebFlux always uses the connection's own
address, so only Stratum's list applies there.

The client IP is personal data. It is logged because support and security need it, so keep your
log retention and access rules in line with that.

### Device, OS and browser

`stratum.web.user-agent.parse=true` reads the device, operating system and browser from the
`User-Agent` header and the browser's client hints, with the
[Yauaa](https://yauaa.basjes.nl/) library, which Stratum brings. It is off by default, because
loading its rules takes about 110 MB of memory and 3 seconds of startup; with it off, nothing is
loaded. Each different user agent is parsed once and kept, up to
`stratum.web.user-agent.cache-size` of them.

Browsers such as Chrome send only a short user agent. With
`stratum.web.user-agent.request-client-hints=true` as well, every response asks the browser, with
the `Accept-CH` header, for its device model and full OS version, which it then sends on later
requests over HTTPS.

### Country and city

Set `stratum.web.client-location.database` to a MaxMind GeoIP2 or GeoLite2 City or Country
database file (`.mmdb`) to log `clientCountry` (the ISO code, such as `NL`) and `clientCity` (in
English). Stratum never ships or downloads the database: your team gets it from MaxMind under its
licence and keeps it up to date. A private address, or one the database does not know, logs no
location. A file that cannot be read stops startup with `SX-WEB-1006`. Without the database,
Elasticsearch, Datadog and the OpenTelemetry Collector can add the location from `clientIp` in the
log pipeline instead.

## Correlation ID

Each request gets one correlation ID, which every log line and error body carries and which goes
back to the caller in the `X-Correlation-Id` response header. It is, in this order:

1. the caller's `X-Correlation-Id` header;
2. the `correlationId` the caller sent in the tracing baggage;
3. the trace ID.

A caller's value counts only if it is 1 to 128 letters, digits, `-`, `_`, `.` or `:`; any other value
is ignored, so a caller cannot forge a log line. A baggage value some tracing libraries
percent-encode, such as `checkout%3A777`, is checked once decoded. Before tracing starts, Stratum
writes the chosen caller's ID into the request's `baggage` header, on both stacks, so tracing
carries it to every log line, to work on other threads, to the error body and to every service the
request calls. On a service that faces
the internet, set `stratum.web.correlation.accept-from-caller=false`: the header and the caller's
baggage are then ignored, and the ID is always the trace ID. The ID never identifies the caller:
identity comes only from a verified token.

The correlation ID needs tracing, which the
[observability starter](observability-traces.md#correlation-id)
brings. Without that starter, requests get no correlation ID.

## Request log

Each request writes one line at `INFO`, through the logger `com.exepex.stratum.web.request-log`, once
it is answered. [Request log](web-request-log.md) says what the line holds, how to add headers and
token claims, and how to stop it with `stratum.web.request-log.enabled=false`.

## SLA dashboards

Response times and error rates per API need nothing from this module. The
[observability starter](../../starters/stratum-starter-observability/README.md#timings) already
records every request in the `http.server.requests` timer, as a histogram, so your metrics backend
can work out any percentile. The timer has these tags:

| Tag | Holds | Example |
| --- | --- | --- |
| `method` | The HTTP method | `GET` |
| `uri` | The route template, never the real path | `/orders/{orderId}` |
| `status` | The response status | `503` |
| `outcome` | The status class, such as `SUCCESS`, `CLIENT_ERROR` or `SERVER_ERROR` | `SERVER_ERROR` |
| `exception` | The simple class name of the exception the request failed with, unless your own handler answered it, or `none` | `TimeoutException` |
| `error.code` | The [error code](../guides/error-codes.md) it failed with, or its first cause's, else `none`; its span carries it too ([traces](observability-traces.md#error-codes-on-requests)) | `ORD-0001` |
| `application`, `environment`, `host` | The service, its environment and the pod, added by the observability starter | `orders`, `prod`, `orders-7d9f-x2k` |

Any dashboard tool builds the usual SLA panels from it:

| Panel | Filter | Group by | Calculation |
| --- | --- | --- | --- |
| Requests per second | `application` | `method`, `uri` | Rate of the count |
| Error rate | `application` | `method`, `uri` | Rate of the count with `outcome=SERVER_ERROR`, divided by the rate of all |
| P99 response time, all pods | `application` | `method`, `uri` | 99th percentile of the histograms of all pods added together |
| P99 response time, one pod | `application`, `host` | `method`, `uri` | 99th percentile of that pod's histogram |
| Availability over 30 days | `application` | none | 1 minus the share of requests with `outcome=SERVER_ERROR` |

Leave out `uri` values under `/actuator` so health checks do not lift the numbers. A `4xx` answer
is the caller's mistake, so most SLAs count only `SERVER_ERROR` as a failure.

### PromQL examples

These use the names Prometheus gives the timer when it scrapes the service, as set up in the
observability starter's
[Prometheus example](../../starters/stratum-starter-observability/README.md#examples). That example
turns histograms off to keep the scrape small, so keep them for this one timer, or the P99 query
finds no `_bucket` series:

```properties
management.metrics.distribution.percentiles-histogram.http.server.requests=true
```

```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        "[http.server.requests]": true
```

Through an OpenTelemetry Collector the names can differ; check them in your backend first.

Requests per second, per API:

```promql
sum by (method, uri) (rate(http_server_requests_seconds_count{application="orders", uri!~"/actuator.*"}[5m]))
```

Error rate, per API. An API that has never failed has no `SERVER_ERROR` series, so the `or` part
gives it 0 instead of leaving it off the panel:

```promql
  (
      sum by (method, uri) (rate(http_server_requests_seconds_count{application="orders", uri!~"/actuator.*", outcome="SERVER_ERROR"}[5m]))
    or
      sum by (method, uri) (rate(http_server_requests_seconds_count{application="orders", uri!~"/actuator.*"}[5m])) * 0
  )
/ sum by (method, uri) (rate(http_server_requests_seconds_count{application="orders", uri!~"/actuator.*"}[5m]))
```

P99 response time, per API, all pods together; add `host` to the `by` list to see each pod:

```promql
histogram_quantile(0.99, sum by (method, uri, le) (rate(http_server_requests_seconds_bucket{application="orders", uri!~"/actuator.*"}[5m])))
```

Availability over 30 days, where `or vector(0)` counts no failures as 0 failures:

```promql
1 - (
    (sum(increase(http_server_requests_seconds_count{application="orders", uri!~"/actuator.*", outcome="SERVER_ERROR"}[30d])) or vector(0))
  / sum(increase(http_server_requests_seconds_count{application="orders", uri!~"/actuator.*"}[30d]))
)
```

To find the requests behind a bad number, search the [request log](#request-log) for the same
route with `httpStatus` 500 or above, or with a large `durationMs`; each line carries the trace ID.

## Properties

Every key the web starters read or give a default, on both stacks. This is the one list; the starter
READMEs and the other web pages link here. Each `stratum.web.*` key works from `.properties`, `.yml`
and Java, and your IDE completes it. For a Spring Boot key, Stratum only sets a default, and a value
you set wins. The web starters also read `stratum.backward-compatible`, which every module shares, so
it is listed in [Configuration reference](../configuration/reference.md) and explained in
[Backward-compatible mode](web-compatible-mode.md).

| Property | Type | Stratum's default | Spring Boot's default | Description |
| --- | --- | --- | --- | --- |
| `stratum.web.enabled` | boolean | `true` | no such key | Whether the module is on. With `false`, the starter sets up nothing of its own. |
| `stratum.web.error.statuses` | map of code to status | empty | no such key | The HTTP status of each of your error codes, such as `ORD-0001: 409`. It also changes the status of a Stratum code, such as `SX-WEB-1011`, whose default is `409`; see [Database failures](web-database-errors.md). |
| `stratum.web.correlation.accept-from-caller` | boolean | `true` | no such key | Whether a caller's `X-Correlation-Id`, or the `correlationId` in its baggage, is used. Set it to `false` on a service that faces the internet, to always use the trace ID. |
| `stratum.web.client-ip.trusted-proxies` | list of address ranges | `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `127.0.0.0/8`, `::1/128`, `fc00::/7` | no such key | The load balancers and proxies whose `X-Forwarded-For` header is trusted. An empty list trusts none. |
| `stratum.web.user-agent.parse` | boolean | `false` | no such key | Whether device, OS and browser are read from the user agent. Adds about 110 MB of memory and 3 seconds of startup. |
| `stratum.web.user-agent.cache-size` | integer, 1 to 1000000 | `10000` | no such key | How many different user agents are kept already parsed. |
| `stratum.web.user-agent.request-client-hints` | boolean | `false` | no such key | Whether responses ask browsers for their device model and full OS version. Only used when `parse` is `true`. |
| `stratum.web.client-location.database` | file path | none | no such key | Your MaxMind City or Country `.mmdb` file. Unset, no country or city is logged. |
| `stratum.web.request-fields.max-value-length` | integer, 16 to 8192 | `512` | no such key | The most characters a request field keeps. |
| `stratum.web.timeout.default-limit` | duration | `10s`, or none while `stratum.backward-compatible` is `true` | no such key | How long any request may take until its response starts. `0` means no limit. |
| `stratum.web.timeout.routes` | map of path pattern to duration | empty | no such key | The limit of the requests whose path matches a pattern, such as `/reports/**`. The most specific pattern wins. |
| `stratum.web.timeout.status` | HTTP status, `400` to `599` | `503` | no such key | The status a request that ran out of time is answered with. |
| `stratum.web.body-limit.default-limit` | data size | `256KB`, or none while `stratum.backward-compatible` is `true` | no such key | How large any request's body may be. `0` means no limit. |
| `stratum.web.body-limit.routes` | map of path pattern to data size | empty | no such key | The limit of the bodies of the requests whose path matches a pattern, such as `/uploads/**`. The most specific pattern wins. |
| `stratum.web.request-log.enabled` | boolean | `true` | no such key | Whether each request writes one log line when it is answered. |
| `stratum.web.request-log.skip-paths` | list of path patterns | `/actuator/**` | no such key | Paths whose requests write no log line. Replaces the default; static files are always skipped. A pattern that is not valid stops startup with `SX-WEB-1009`. |
| `stratum.web.request-log.request-headers` | map of header to field name | empty | no such key | The request headers written in the line, and the field each is written as. |
| `stratum.web.request-log.response-headers` | map of header to field name | empty | no such key | The response headers written in the line, and the field each is written as. |
| `stratum.web.request-log.denied-headers` | list of header names | empty | no such key | Headers never written, on top of `Authorization`, `Cookie`, `Set-Cookie` and `X-Api-Key`. |
| `stratum.web.request-log.token-payload` | boolean | `false` | no such key | Whether the line carries the listed claims of the caller's bearer JWT. |
| `stratum.web.request-log.token-claims` | list of claim names | `sub`, `azp`, `client_id`, `scope` | no such key | The claims written when `token-payload` is `true`. Replaces the default. |
| `spring.reactor.context-propagation` | `limited` or `auto` | `auto`, on WebFlux only, in both modes | `limited` | With `auto`, Reactor carries the request's trace ID into every operator, so error bodies, error pages and log lines have it, as on Spring MVC. |
| `spring.webflux.multipart.max-disk-usage-per-part` | data size | `10MB`, or none while `stratum.backward-compatible` is `true`; WebFlux only | `-1B`, no limit | The disk each part of a multipart upload may use. |
| `spring.webflux.multipart.max-parts` | integer | `50`, or none while `stratum.backward-compatible` is `true`; WebFlux only | `-1`, no limit | The most parts a multipart upload may have. |

For a service on the internet, behind a CDN, with the device fields on and a request field kept to 256
characters, in `application.properties`:

```properties
stratum.web.correlation.accept-from-caller=false
stratum.web.client-ip.trusted-proxies=10.0.0.0/8,203.0.113.0/24
stratum.web.user-agent.parse=true
stratum.web.user-agent.cache-size=5000
stratum.web.user-agent.request-client-hints=true
stratum.web.client-location.database=/data/geoip/GeoLite2-City.mmdb
stratum.web.request-fields.max-value-length=256
```

or in `application.yml`:

```yaml
stratum:
  web:
    correlation:
      accept-from-caller: false
    client-ip:
      trusted-proxies:
        - 10.0.0.0/8
        - 203.0.113.0/24
    user-agent:
      parse: true
      cache-size: 5000
      request-client-hints: true
    client-location:
      database: /data/geoip/GeoLite2-City.mmdb
    request-fields:
      max-value-length: 256
```

## Configure in Java

Every setting can also be set in Java, through a `WebPropertiesCustomizer` class listed in
`META-INF/spring.factories`, as for the other modules, and Java wins. The same settings as above,
with a tenant header in the request log:

```java
public class OrdersWebSettings implements WebPropertiesCustomizer {

  @Override
  public WebProperties customize(WebProperties properties) {
    return properties.toBuilder()
        .correlation(properties.correlation().toBuilder().acceptFromCaller(false).build())
        .clientIp(
            properties.clientIp().toBuilder()
                .trustedProxies(List.of("10.0.0.0/8", "203.0.113.0/24"))
                .build())
        .userAgent(
            properties.userAgent().toBuilder()
                .parse(true)
                .cacheSize(5000)
                .requestClientHints(true)
                .build())
        .requestFields(properties.requestFields().toBuilder().maxValueLength(256).build())
        .requestLog(
            properties.requestLog().toBuilder()
                .requestHeaders(Map.of("X-Tenant-Id", "tenantId"))
                .build())
        .clientLocation(
            WebProperties.ClientLocationSettings.builder()
                .database(Path.of("/data/geoip/GeoLite2-City.mmdb"))
                .build())
        .build();
  }
}
```

The [web MVC starter's README](../../starters/stratum-starter-webmvc/README.md#configure-in-java)
shows how to list the class, which is the same for WebFlux. A single API can also set its own
timeout with `@ResponseTimeout` on its method, and its own body size limit with `@RequestBodyLimit`;
see [Response timeouts](web-timeouts.md) and [Request body size limit](web-body-limit.md).

## Switch it on or off

The module is on when its starter is on the classpath. Leaving `stratum.web.enabled` unset, or
empty, is the same as `true`. `stratum.web.enabled=false` turns it off. With the module off, the
starter still brings its web stack, so the service runs as plain Spring Boot.
