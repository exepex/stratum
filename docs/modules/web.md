# Web: `stratum-starter-webmvc` and `stratum-starter-webflux`

**Status: Partly built.** Error responses, request fields in logs and the correlation ID are built
for both stacks; the error responses are described in the READMEs of
[`stratum-starter-webmvc`](../../starters/stratum-starter-webmvc/README.md) and
[`stratum-starter-webflux`](../../starters/stratum-starter-webflux/README.md), and the request
fields and correlation ID on this page. The request log, timeouts and the body size limit are the
agreed design and not built yet.

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
| Error responses (built) | Every error is [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) JSON with a code, the trace ID and the correlation ID. Never a stack trace, exception message or class name |
| HTML error page (built) | A browser gets an HTML page with a reference number (the trace ID) that the user can quote to support |
| Request fields in logs (built) | Every log line written during a request carries its method, route, path, query, client IP and user agent, as separate fields. Device, OS, browser and the client's country and city when you turn them on |
| Request log | One JSON line when each request ends, with its status and duration. Health checks and static files are left out |
| Headers in logs | The request and response headers a team names are logged as fields. `Authorization`, `Cookie`, `Set-Cookie` and `X-Api-Key` never are |
| Token payload in logs | When a team turns it on, the payload of the caller's JWT is logged. The token's header and signature never are |
| Correlation ID (built) | Taken from the caller's `X-Correlation-Id` header when it has a safe format, otherwise the trace ID; returned in the response header. Needs the observability starter |
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
automatic context propagation, which the logging and observability starters turn on. Caller
fields, such as the calling service and user, are planned for the security starter, which reads
them from a verified token only.

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
`server.tomcat.remoteip.internal-proxies`, which also defaults to the private and loopback ranges.
If you change Stratum's list, set Tomcat's to match. WebFlux always uses the connection's own
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
is ignored, so a caller cannot forge a log line. When the ID comes from the header, Stratum puts it
in the tracing baggage, so every service the request calls gets it too. On a service that faces
the internet, set `stratum.web.correlation.accept-from-caller=false`: the header and the caller's
baggage are then ignored, and the ID is always the trace ID. The ID never identifies the caller:
identity comes only from a verified token.

The correlation ID needs tracing, which the
[observability starter](../../starters/stratum-starter-observability/README.md#correlation-id)
brings. Without that starter, requests get no correlation ID.

## Properties

Each works from `.properties`, `.yml` and Java, and your IDE completes it. The built ones work on
both stacks; the others are planned.

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `stratum.web.enabled` | boolean | `false` | Switches the module on. Until it is `true`, the starter sets up nothing of its own. |
| `stratum.web.error.statuses` | map of code to status | empty | The HTTP status of each of your error codes, such as `ORD-0001: 409`. |
| `stratum.web.correlation.accept-from-caller` | boolean | `true` | Whether a caller's `X-Correlation-Id`, or the `correlationId` in its baggage, is used. Set it to `false` on a service that faces the internet, to always use the trace ID. |
| `stratum.web.client-ip.trusted-proxies` | list of address ranges | `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `127.0.0.0/8`, `::1/128`, `fc00::/7` | The load balancers and proxies whose `X-Forwarded-For` header is trusted. An empty list trusts none. |
| `stratum.web.user-agent.parse` | boolean | `false` | Whether device, OS and browser are read from the user agent. Adds about 110 MB of memory and 3 seconds of startup. |
| `stratum.web.user-agent.cache-size` | integer, 1 to 1000000 | `10000` | How many different user agents are kept already parsed. |
| `stratum.web.user-agent.request-client-hints` | boolean | `false` | Whether responses ask browsers for their device model and full OS version. Only used when `parse` is `true`. |
| `stratum.web.client-location.database` | file path | none | Your MaxMind City or Country `.mmdb` file. Unset, no country or city is logged. |
| `stratum.web.request-fields.max-value-length` | integer, 16 to 8192 | `512` | The most characters a request field keeps. |
| `stratum.web.timeout.default` (planned) | duration | `10s` | How long any request may run. |
| `stratum.web.timeout.status` (planned) | HTTP status | `503` | The status of a request that ran out of time. |
| `stratum.web.request-log.enabled` (planned) | boolean | `true` | Whether each request writes one log line when it ends. |
| `stratum.web.request-log.skip-paths` (planned) | list | `/actuator/**` and static files | Paths whose requests write no log line. |

For a service behind a CDN, with the device fields on, in `application.properties`:

```properties
stratum.web.client-ip.trusted-proxies=10.0.0.0/8,203.0.113.0/24
stratum.web.user-agent.parse=true
stratum.web.client-location.database=/data/geoip/GeoLite2-City.mmdb
```

or in `application.yml`:

```yaml
stratum:
  web:
    client-ip:
      trusted-proxies:
        - 10.0.0.0/8
        - 203.0.113.0/24
    user-agent:
      parse: true
    client-location:
      database: /data/geoip/GeoLite2-City.mmdb
```

## Configure in Java

Every setting can also be set in Java, through a `WebPropertiesCustomizer` class listed in
`META-INF/spring.factories`, as for the other modules, and Java wins. The same settings as above:

```java
public class OrdersWebSettings implements WebPropertiesCustomizer {

  @Override
  public WebProperties customize(WebProperties properties) {
    return properties.toBuilder()
        .clientIp(
            properties.clientIp().toBuilder()
                .trustedProxies(List.of("10.0.0.0/8", "203.0.113.0/24"))
                .build())
        .userAgent(properties.userAgent().toBuilder().parse(true).build())
        .clientLocation(
            WebProperties.ClientLocationSettings.builder()
                .database(Path.of("/data/geoip/GeoLite2-City.mmdb"))
                .build())
        .build();
  }
}
```

The [web MVC starter's README](../../starters/stratum-starter-webmvc/README.md#configure-in-java)
shows how to list the class, which is the same for WebFlux. A single API will also be able to set
its own timeout with an annotation on its method; that is planned.

## Switch it on or off

`stratum.web.enabled=true` switches the module on. Leaving it unset is the same as `false`. With
the module off, the starter still brings its web stack, so the service runs as plain Spring Boot.
