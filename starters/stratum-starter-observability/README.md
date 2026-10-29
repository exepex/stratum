# Observability: `stratum-starter-observability`

**Status: Partly built.** Metrics, traces and health for every service, sent over OTLP to an
OpenTelemetry Collector, with safe defaults. A guard on metric tags, duration histograms and the
correlation ID on spans are [planned](#planned).

This page covers this module only. How Stratum works as a whole, its other modules and how
configuration works in general are in the [documentation](../../docs/README.md).

[← Documentation home](../../docs/README.md) · Property prefix: `stratum.observability`

## What it does

Observability answers three questions about a running service:

| Question | Signal | Where it goes |
| --- | --- | --- |
| How much, how fast, how many errors? | **Metrics** | Any backend your OpenTelemetry Collector sends to: Prometheus, Grafana, Datadog, New Relic and others |
| What happened to *this* request, across all services? | **Traces** | Any tracing backend behind the Collector: Tempo, Jaeger, X-Ray, Datadog and others |
| Is the service alive and ready for traffic? | **Health** | Kubernetes probes and load balancers |

Spring Boot already measures HTTP requests, JVM memory, scheduled jobs, Spring Batch jobs and more,
through [Micrometer](https://micrometer.io/) (metrics) and [OpenTelemetry](https://opentelemetry.io/)
(traces). This starter brings those libraries, sends what they record to one place, and sets the
same safe defaults in every service. It records how long things take; it never labels anything as
slow or fast. Dashboards and alerts decide that.

**OTLP** is OpenTelemetry's protocol for sending metrics and traces. An **OpenTelemetry
Collector** is a small service, usually run once per cluster or next to each pod, that receives OTLP
and forwards it to the backends your company uses. Changing backend then means changing the
Collector's configuration, not your service.

## Add it

```xml
<dependency>
  <groupId>com.exepex.stratum</groupId>
  <artifactId>stratum-starter-observability</artifactId>
</dependency>
```

The `stratum-starter-microservice`, `-web-ui` and `-batch` bundles already include it. The
`-cli` bundle does not: a command-line tool adds it on its own when it needs it. The version comes
from Stratum's parent or BOM ([installation](../../docs/getting-started/installation.md)).

The starter does nothing until you switch it on, and outside the `local` and `test` profiles it
needs your Collector's addresses. In `application.properties`:

```properties
spring.application.name=order-service
stratum.environment=prod
stratum.observability.enabled=true
management.otlp.metrics.export.url=http://otel-collector:4318/v1/metrics
management.opentelemetry.tracing.export.otlp.endpoint=http://otel-collector:4318/v1/traces
```

or in `application.yml`:

```yaml
spring:
  application:
    name: order-service
stratum:
  environment: prod
  observability:
    enabled: true
management:
  otlp:
    metrics:
      export:
        url: http://otel-collector:4318/v1/metrics
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: http://otel-collector:4318/v1/traces
```

## How it works with Spring Boot

This starter adds Spring Boot's own `spring-boot-starter-actuator` and
`spring-boot-starter-opentelemetry`, and sets a few of Spring Boot's `management.*` properties at
startup, listed [below](#properties). A value you set in a configuration file, an environment
variable, a system property or a command-line argument wins over Stratum's. Everything else, such as
`management.tracing.sampling.probability` or `management.endpoint.health.*`, works as the
[Spring Boot documentation](https://docs.spring.io/spring-boot/reference/actuator/index.html)
describes.

With the module off, the starter turns off sending metrics and traces. A bundle that brings the
starter therefore never sends anything, not even to Spring Boot's default address,
`localhost:4318`, until you switch the module on.

## What you get

| Feature | Behaviour |
| --- | --- |
| Metrics | Spring Boot's metrics (HTTP server and client, JVM, connection pools, caches, `@Scheduled` jobs, Spring Batch jobs), pushed over OTLP every minute |
| Tags on every metric | `application` from `spring.application.name`, `environment` from [`stratum.environment`](../../docs/configuration/reference.md#shared-properties), and `host` from the `HOSTNAME` environment variable, which in Kubernetes is the pod's name. Each is left out when its value is not set |
| Traces | OpenTelemetry traces. W3C `traceparent` is read from incoming requests and sent on calls made with Spring's HTTP clients. Spring Boot keeps 10% of new traces, and always follows the caller's decision, so a trace is never half kept |
| Trace resource | `service.name` from `spring.application.name` (set by Spring Boot), and `deployment.environment.name` from `stratum.environment` |
| URLs on spans | The full URL of each HTTP client call, query string included, is kept on its span; only the user info is removed. Metrics never carry the query ([below](#urls-on-spans-and-metrics)) |
| Health | `/actuator/health`, with Spring Boot's Kubernetes `liveness` and `readiness` groups |
| Safe exposure | Only `health` and `info` are reachable over HTTP |
| Startup check | Outside the `local` and `test` profiles, startup stops if an export address is missing ([below](#startup-check)) |

Pushing means a short-lived job, such as a batch run or a command-line tool, still sends its data:
Spring Boot sends the last metrics and traces when the application stops.

### Seeing one pod or all of them

Every metric carries the `host` tag, so a dashboard can show one pod alone or all pods together. If
one pod out of ten is slow, filter on `host` to see it. Outside Kubernetes, set `HOSTNAME` in the
service's environment to get the tag.

### URLs on spans and metrics

Spring records the full URL of every outgoing HTTP call on its span, and the URI template in the
`uri` tag of the span and of the `http.client.requests` metrics. Stratum keeps the query string on
the span, so you can see which values were sent, and changes two things:

| Recorded by Spring | Kept on the span | Kept in the `uri` tag |
| --- | --- | --- |
| `https://partner.com/rates?currency=EUR&page=2` | `https://partner.com/rates?currency=EUR&page=2` | `/rates` |
| `https://user:secret@partner.com:8443/rates` | `https://partner.com:8443/rates` | `/rates` |

- **User info is removed** from the span's URL, because it is a password.
- **The `uri` tag loses its query**, on spans and metrics, because each distinct value would create
  a new time series and can overload the metrics backend.

A URL that cannot be parsed is recorded as `[UNPARSABLE_URL]`. This cannot be switched off. It
applies when Spring's web classes are on the classpath, which is the only way Spring makes HTTP
client spans and metrics.

> [!WARNING]
> Never put a secret or personal data, such as a token, an IBAN or a card number, in a URL's path
> or query string. Trace backends store the URL as it is, and so do proxies and access logs on the
> way. Send such values in a header or the request body. When a call fails, Spring's error message
> also names the URL with its user info, such as
> `I/O error on GET request for "https://user:secret@partner.com/rates"`, and the span records that
> message.

### Startup check

Outside the `local` and `test` profiles, with the module on, startup stops with
[`SX-OBSERVABILITY-1001`](../../docs/guides/error-codes.md#stratum-starter-observability) if:

- metrics are sent over OTLP and `management.otlp.metrics.export.url` is not set; or
- traces are sent over OTLP and `management.opentelemetry.tracing.export.otlp.endpoint` is not set.

Whether an export is on follows Spring Boot: `management.otlp.metrics.export.enabled` decides for
metrics, or `management.defaults.metrics.export.enabled` when it is not set;
`management.tracing.export.otlp.enabled` decides for traces, or `management.tracing.export.enabled`
when it is not set. With none of them set, both exports are on. An address or switch that Spring
Boot reads from the standard OpenTelemetry environment variables, such as
`OTEL_EXPORTER_OTLP_ENDPOINT`, counts.

Without the check, metrics would go to `localhost:4318` and traces nowhere, and nobody would notice.
Both `http` and `https` addresses are accepted, because most Collectors run inside the cluster. The
check counts as local only when every active profile is `local` or `test`, or, with no active
profile, every default profile (`spring.profiles.default`) is.

## Properties

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `stratum.observability.enabled` | boolean | `false` | Switches the whole starter on. Until it is `true`, the starter sets up nothing and turns export off. |

The shared [`stratum.environment`](../../docs/configuration/reference.md#shared-properties) sets the
`environment` tag and the `deployment.environment.name` resource attribute.

Spring Boot properties this starter sets by default (your values win):

| Spring Boot property | Stratum default |
| --- | --- |
| `management.otlp.metrics.export.enabled` | `false` while the module is off |
| `management.tracing.export.enabled` | `false` while the module is off |
| `management.endpoints.web.exposure.include` | `health,info` |
| `management.metrics.tags.application` | value of `spring.application.name` |
| `management.metrics.tags.environment` | value of `stratum.environment` |
| `management.metrics.tags.host` | value of the `HOSTNAME` environment variable |
| `management.opentelemetry.resource-attributes[deployment.environment.name]` | value of `stratum.environment` |

## Examples

Keep every trace while you develop, in `application-local.properties` or `application-local.yml`.
No Collector address is needed in the `local` profile; start a Collector on your laptop if you want
to see the data:

```properties
management.tracing.sampling.probability=1.0
```

```yaml
management:
  tracing:
    sampling:
      probability: 1.0
```

Send metrics to Prometheus by scraping instead of pushing. Add `io.micrometer:micrometer-registry-prometheus`
(its version comes from Spring Boot), expose the endpoint, and turn OTLP metrics off:

```properties
management.endpoints.web.exposure.include=health,info,prometheus
management.otlp.metrics.export.enabled=false
```

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  otlp:
    metrics:
      export:
        enabled: false
```

> [!CAUTION]
> Expose only the endpoints you need, and never `*` in a shared environment. Endpoints such as
> `env`, `heapdump` and `threaddump` can reveal configuration and memory contents.

## Configure in Java

Nothing to configure in Java yet. The module's only setting is its switch, and every default it sets
is a Spring Boot property, read at startup before any bean exists. Set them in properties, YAML,
environment variables or command-line arguments.

## Customize

- **Your own metrics and spans:** use Micrometer's `MeterRegistry` or `@Observed` (which needs
  AspectJ on the classpath and `management.observations.annotations.enabled=true`).
- **Rename or drop metrics, change tags:** declare a Micrometer `MeterFilter` bean.
- **Your own URL cleaning:** declare a `TelemetryUrlFilter` bean; Stratum's then backs off.
- **Custom health check:** implement `HealthIndicator`; add it to the readiness group with
  `management.endpoint.health.group.readiness.include`.

> [!WARNING]
> Never put unbounded values (user IDs, order IDs, URLs with IDs) into metric **tags**. Each
> distinct value creates a new time series and can overload the metrics backend. Put such values in
> logs or span attributes instead.

## Switch it on or off

`stratum.observability.enabled=true` switches the starter on. Leaving it unset is the same as
`false`: metrics and traces are not sent, and Spring Boot's own actuator defaults apply.

## Planned

| Feature | What it adds |
| --- | --- |
| Tag guard | A metric tagged with an ID, such as `userId` or `trace_id`, is dropped with one warning; a service can add names to the list |
| Duration histograms | HTTP, workflow and job durations stored as histograms, so P50 and P99 are correct for one pod and for all pods |
| Correlation ID on spans | The correlation ID copied onto spans, so a trace can be found by it |
| How each app type is observed | Docs for batch, command-line, messaging (Kafka, JMS, RabbitMQ) and reactive services |
| Vendor and cloud recipes | Collector setups for Datadog, New Relic, Splunk, Dynatrace, AWS, Azure and Google Cloud |
| Profiling | The Pyroscope profiler, after a licence and overhead check |

## Troubleshooting

| Symptom | Likely cause and fix |
| --- | --- |
| Startup stops with `SX-OBSERVABILITY-1001` | Set the property the message names to your Collector's address, or turn that export off. |
| No metrics or traces arrive | `stratum.observability.enabled` is not `true`, or the Collector is not reachable at the configured address. Look for export errors in the log. |
| Traces break between two services | The caller does not use Spring's `RestClient`, `WebClient` or `RestTemplate` from Spring Boot's builder, so no `traceparent` header is sent. |
| No `host` tag | `HOSTNAME` is not set in the service's environment. |
| `/actuator/prometheus` returns 404 | Prometheus is not set up; see the [example](#examples). |
| Readiness stays `DOWN` | Open `/actuator/health/readiness` as an authorised caller to see which component fails. |
