# Observability: `stratum-starter-observability`

**Status: Planned.** Metrics, distributed tracing and health checks, set up the same way in every
service so dashboards and alerts work across the whole platform.

[← Documentation home](../README.md) · Property prefix: `stratum.observability`

## What it does

Observability answers three questions about a running service:

| Question | Signal | Tooling (typical) |
| --- | --- | --- |
| How much, how fast, how many errors? | **Metrics** | Prometheus, Grafana |
| What happened to *this* request, across all services? | **Traces** | Jaeger, Tempo, Zipkin, any OpenTelemetry backend |
| Is the service alive and ready for traffic? | **Health checks** | Kubernetes probes, load balancers |

This starter wires Spring Boot Actuator, Micrometer and OpenTelemetry so all three work with no
code, and uses the same metric names, tags and endpoints in every service.

## Add it

```xml
<dependency>
  <groupId>com.example.stratum</groupId>
  <artifactId>stratum-starter-observability</artifactId>
</dependency>
```

## What you get

| Feature | Behaviour |
| --- | --- |
| Metrics | JVM, HTTP server and client, database pool, cache and Kafka metrics, with common tags `application` and `environment` |
| Metrics endpoint | `/actuator/prometheus` for Prometheus to scrape |
| Tracing | OpenTelemetry tracing; trace context (W3C `traceparent`) is read from incoming requests and sent on outgoing HTTP calls and Kafka messages |
| Trace export | OTLP over HTTP to an OpenTelemetry Collector |
| Health | `/actuator/health` with liveness and readiness groups for Kubernetes |
| Info | `/actuator/info` with build and Git details (when the parent POM generated them) |
| Safe exposure | Only `health`, `info` and `prometheus` are exposed over HTTP; health details are shown only to authorised callers |

### Health endpoints for Kubernetes

| Endpoint | Meaning | Use as |
| --- | --- | --- |
| `/actuator/health/liveness` | The process works; restart it if this fails | `livenessProbe` |
| `/actuator/health/readiness` | The service can take traffic (database, required dependencies up) | `readinessProbe` |

```yaml
# deployment.yaml (excerpt)
livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: 8080 }
readinessProbe:
  httpGet: { path: /actuator/health/readiness, port: 8080 }
```

### Adding your own metrics and spans

Use Micrometer's `@Observed` to get a timer metric **and** a span from one annotation:

```java
@Service
class PricingService {

  @Observed(name = "pricing.quote", contextualName = "quote-price")
  Price quote(Basket basket) { ... }
}
```

Or count something directly:

```java
@Component
class OrderMetrics {

  private final Counter placed;

  OrderMetrics(MeterRegistry registry) {
    this.placed = Counter.builder("orders.placed").register(registry);
  }

  void orderPlaced() { placed.increment(); }
}
```

> [!WARNING]
> Never put unbounded values (user IDs, order IDs, URLs with IDs) into metric **tags**. Each
> distinct value creates a new time series and can overload the metrics system. Put such values in
> logs or span attributes instead.

## Properties

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `stratum.observability.enabled` | boolean | `true` | Switches the whole starter on or off. |
| `stratum.observability.metrics.enabled` | boolean | `true` | Collects and exposes metrics. |
| `stratum.observability.metrics.common-tags` | map | *(empty)* | Extra tags on every metric, added to `application` and `environment`. |
| `stratum.observability.metrics.percentiles-histogram` | boolean | `true` | Publishes histograms for HTTP latency, so percentiles can be computed across instances. |
| `stratum.observability.tracing.enabled` | boolean | `true` | Creates and propagates traces. |
| `stratum.observability.tracing.sampling-probability` | number 0.0–1.0 | `0.1` | Share of requests that are traced (`0.1` = 10 %). |
| `stratum.observability.tracing.export.endpoint` | URL | `http://localhost:4318/v1/traces` | OTLP HTTP endpoint of the OpenTelemetry Collector. |
| `stratum.observability.tracing.export.timeout` | duration | `10s` | Timeout for sending a batch of spans. |
| `stratum.observability.health.probes-enabled` | boolean | `true` | Adds the liveness and readiness groups. |
| `stratum.observability.health.show-details` | `never`, `when-authorized`, `always` | `when-authorized` | Who sees component details in `/actuator/health`. |
| `stratum.observability.endpoints.exposed` | list | `health`, `info`, `prometheus` | Actuator endpoints exposed over HTTP. |

The `environment` tag comes from [`stratum.environment`](../configuration/reference.md#shared-properties).

Spring Boot properties this starter sets by default (your values win):

| Spring Boot property | Stratum default |
| --- | --- |
| `management.tracing.sampling.probability` | value of `stratum.observability.tracing.sampling-probability` |
| `management.endpoints.web.exposure.include` | value of `stratum.observability.endpoints.exposed` |
| `management.endpoint.health.probes.enabled` | `true` |
| `management.endpoint.health.show-details` | `when-authorized` |

## Examples

Production: low sampling, collector in the cluster, team tag:

```yaml
stratum:
  environment: prod
  observability:
    metrics:
      common-tags:
        team: checkout
    tracing:
      sampling-probability: 0.05
      export:
        endpoint: http://otel-collector.observability:4318/v1/traces
```

```properties
stratum.environment=prod
stratum.observability.metrics.common-tags.team=checkout
stratum.observability.tracing.sampling-probability=0.05
stratum.observability.tracing.export.endpoint=http://otel-collector.observability:4318/v1/traces
```

Local development: trace everything, expose every endpoint (the `local` profile does this for you):

```yaml
stratum:
  observability:
    tracing:
      sampling-probability: 1.0
    endpoints:
      exposed: "*"
```

```properties
stratum.observability.tracing.sampling-probability=1.0
stratum.observability.endpoints.exposed=*
```

> [!CAUTION]
> Never expose every endpoint in a shared environment. Endpoints such as `env`, `heapdump` and
> `threaddump` can reveal configuration and memory contents.

## Configure in Java

**Planned.** You will also be able to set every property on this page in Java code, through this module's
customizer or builder, with the same names and defaults. This section will show how once that API is designed.

## Customize

- **Rename or drop metrics, change tags:** declare a Micrometer `MeterFilter` bean.
- **Add attributes to every span:** declare an `ObservationFilter` bean.
- **Custom health check:** implement `HealthIndicator`; add it to the readiness group with
  `management.endpoint.health.group.readiness.include`.

## Switch it off

`stratum.observability.enabled=false` removes Stratum's settings. Actuator stays on the classpath
with Spring Boot's own defaults. To keep metrics but drop tracing, set
`stratum.observability.tracing.enabled=false`.

## Troubleshooting

| Symptom | Likely cause and fix |
| --- | --- |
| Traces break between two services | The caller does not use a Stratum HTTP client (or Spring's `RestClient.Builder` bean), so no `traceparent` header is sent. |
| No traces in the backend | Check `tracing.export.endpoint`, and that `sampling-probability` is not `0`. Look for export errors in the log. |
| `/actuator/prometheus` returns 404 | It is not in `endpoints.exposed`, or the observability starter is missing. |
| Readiness stays `DOWN` | Open `/actuator/health/readiness` as an authorised caller to see which component fails. |
