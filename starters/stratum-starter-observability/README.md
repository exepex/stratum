# Observability: `stratum-starter-observability`

**Status: Partly built.** Metrics, traces and health for every service, sent over OTLP to an
OpenTelemetry Collector, with safe defaults. Docs per app type, vendor recipes and profiling are
[planned](#planned).

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

The `stratum-starter-microservice`, `-reactive-microservice`, `-web-ui` and `-batch` bundles
include it; `-cli` does not, so a command-line tool adds it on its own when it needs it. The version
comes from Stratum's parent or BOM ([installation](../../docs/getting-started/installation.md)).

The starter is on as soon as it is on the classpath, and it needs your Collector's addresses, in
every profile. In `application.properties`:

```properties
spring.application.name=order-service
stratum.environment=prod
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

With the module off (`stratum.observability.enabled=false`), the starter turns off sending metrics
and traces, so it never sends anything, not even to Spring Boot's default address,
`localhost:4318`. With the module off, logs go over OTLP only if your service sets a logs endpoint
and installs the OpenTelemetry appender itself.

## What you get

| Feature | Behaviour |
| --- | --- |
| Metrics | Spring Boot's metrics (HTTP server and client, JVM, connection pools, caches, including those made on their first use ([Cache → Metrics](../../docs/modules/cache.md#metrics)), `@Scheduled` jobs, Spring Batch jobs), pushed over OTLP every minute |
| Tags on every metric | `application` from `spring.application.name`, `environment` from [`stratum.environment`](../../docs/configuration/reference.md#shared-properties), and `host` from the `HOSTNAME` environment variable, which in Kubernetes is the pod's name. Each is left out when its value is not set |
| Timings as histograms | Every timer, such as HTTP server and client calls, `@Scheduled` jobs and Spring Batch steps, is sent as a base-2 exponential histogram ([below](#timings)) |
| Tag guard | A metric with a tag named like an ID, such as `userId`, is dropped with one warning; the RabbitMQ routing key is removed from send timers ([below](#tag-guard)) |
| Correlation ID | In the logging context of every traced operation, so log lines and error reports carry it ([traces](../../docs/modules/observability-traces.md#correlation-id)) |
| Other threads | `@Async` methods and Spring Boot's executors keep the caller's trace and correlation ID ([below](#threads-and-reactive-code)). `@Scheduled` jobs start clean, and `LinkedWork` links work that a request saves for a later job back to that request ([guide](../../docs/guides/linking-later-work.md)) |
| Messages | Kafka, JMS (IBM MQ, ActiveMQ) and RabbitMQ: sending and handling a message are timed and traced, and the consumer joins the sender's trace and correlation ID ([below](#messages)) |
| Traces | OpenTelemetry traces. A request joins the caller's trace from its W3C `traceparent`, B3 `b3` or B3 multi `X-B3-*` header; calls made with Spring's HTTP clients send W3C `traceparent` only. To send B3 as well, set Spring Boot's `management.tracing.propagation.produce` ([examples](#examples)). Spring Boot keeps 10% of traces. Stratum makes each service choose by trace ID, so a caller's sampled flag cannot make it keep more, and services with the same probability keep the same traces ([sampling](../../docs/modules/observability-traces.md#sampling)). With the cache starter, Redis cache command spans carry `cache.name` ([Cache → Traces](../../docs/modules/cache-traces.md)) |
| Trace and metric resource | `service.name` from `spring.application.name` (set by Spring Boot), `deployment.environment.name` from `stratum.environment`, and `service.instance.id`, the [instance ID](../../docs/configuration/reference.md#instance-id) of this running copy, which its log lines carry too |
| Errors on spans | A failed span keeps its `ERROR` status but carries no exception type, message or stack trace, which belong in the logs; metrics keep their `exception` tag ([traces](../../docs/modules/observability-traces.md#errors-on-spans)) |
| URLs on spans | The full URL of each HTTP client call, query string included, is kept on its span up to 1,024 characters ([span size](../../docs/modules/observability-traces.md#span-size)); only the user info is removed. Metrics never carry the query ([traces](../../docs/modules/observability-traces.md#urls-on-spans-and-metrics)) |
| Dropped spans | Spans lost because the export queue was full are counted in `stratum.observability.spans.dropped`, with the alert to set ([traces](../../docs/modules/observability-traces.md#dropped-spans)) |
| Health | `/actuator/health`, with Spring Boot's Kubernetes `liveness` and `readiness` groups |
| Safe exposure | Only `health` and `info` are reachable over HTTP |
| Startup check | With the module on, startup stops if an export address is missing, in every profile ([below](#startup-check)) |
| No Collector on your laptop | With an address on `localhost`, metrics and spans that cannot be sent are dropped without a failed-send log; a full span queue still logs a warning ([below](#running-without-a-collector)) |

Pushing means a short-lived job, such as a batch run or a command-line tool, still sends its data:
Spring Boot sends the last metrics and traces when the application stops.

### Seeing one pod or all of them

Every metric carries the `host` tag, so a dashboard can show one pod alone or all pods together. If
one pod out of ten is slow, filter on `host` to see it. Outside Kubernetes, set `HOSTNAME` in the
service's environment to get the tag.

### Timings

Stratum records how long things take and never decides what is slow. Each timing is sent as a
**base-2 exponential histogram**: a count of calls per duration range, where the ranges grow by
powers of two. From it, your metrics backend works out any percentile, such as P50 or P99, for one
pod or for all pods added together, which percentiles computed inside each pod cannot do. There are
no bucket limits to choose. Dashboards and alerts decide what counts as too slow.

Check that your metrics backend, or the Collector exporter in front of it, accepts OTLP
exponential histograms. If it does not, send classic fixed buckets instead, in
`application.properties`:

```properties
management.otlp.metrics.export.histogram-flavor=explicit-bucket-histogram
```

or in `application.yml`:

```yaml
management:
  otlp:
    metrics:
      export:
        histogram-flavor: explicit-bucket-histogram
```

### Tag guard

Each distinct value of a metric tag creates a new time series, so a tag that holds an ID, such as a
user or order ID, can overload the metrics backend. Stratum drops every metric that has a tag with
one of these names, ignoring case, `_`, `-` and `.`:

`userId`, `customerId`, `accountId`, `orderId`, `sessionId`, `requestId`, `transactionId`,
`traceId`, `spanId`, `correlationId`, `email`, `ipAddress`

So `user_id`, `USER-ID` and `user.id` are dropped too. The first time a metric is dropped, Stratum
logs
[`SX-OBSERVABILITY-1002`](../../docs/guides/error-codes.md#stratum-starter-observability), naming
the metric and the tag, never a value. Put such IDs in log lines or span attributes instead.

The guard also removes the RabbitMQ routing-key tag, `messaging.rabbitmq.destination.routing_key`,
from every metric and keeps the metric, because a routing key can hold an ID
([messages](#messages)). `allowed` does not bring it back.

Add your own names with `stratum.observability.tags.extra-forbidden`. To keep a built-in name, list
it in `stratum.observability.tags.allowed`. Do that only when the tag has a small, known set of
values, such as `userId` in an internal tool with a few dozen users. The guard is about the cost of
metrics, not about privacy: whether an ID may appear in logs is up to the logging starter's
redaction. To change the rule itself, declare your own `MetricTagGuard` bean, and Stratum's backs
off.

### Messages

With the module on, sending and handling a Kafka, RabbitMQ or JMS message is traced and timed like
an HTTP call: the consumer joins the sender's trace, its log lines carry the sender's trace and
correlation IDs, and each send and handled message is a [histogram](#timings). Stratum turns on
Spring Boot's Kafka and RabbitMQ switches, which Spring Boot leaves off.
[Messages](../../docs/modules/observability-messages.md) lists the timers, their tags, the cases
that are not covered, such as Kafka batch listeners, and how to turn each switch off.

### Threads and reactive code

Stratum sets Spring Boot's `spring.task.execution.propagate-context=true`, which you can set to
`false`, so `@Async` methods, Spring Boot's task executor
and executors built with its `ThreadPoolTaskExecutorBuilder` keep the caller's trace and correlation
ID. Pools you declare as `Executor`, `ExecutorService` or `ScheduledExecutorService` beans keep them
too, unless `stratum.context-propagation.executor-beans=false` (boolean, default `true`); pools made
in code outside a bean do not. The module runs before Spring Boot's task scheduling; [log fields
across threads](../../docs/guides/context-across-threads.md) explains why and lists every case.

In reactive code, Stratum sets Spring Boot's `spring.reactor.context-propagation=auto` and turns on
Reactor's context propagation, even without WebFlux or WebClient. Every line in a WebFlux handler or
any operator carries the request's trace and correlation ID, never another request's, and `WebClient`
sends `baggage` like `RestClient`. Setting `spring.reactor.context-propagation=limited` keeps
Spring Boot's own mode, where only `handle` and `tap` lines carry them. The same setting carries the logging
starter's [workflow fields](../../docs/modules/logging-workflows.md#async-and-reactive-work).

### Startup check

With the module on, in every profile, startup stops with
[`SX-OBSERVABILITY-1001`](../../docs/guides/error-codes.md#stratum-starter-observability) if:

- metrics are sent over OTLP and `management.otlp.metrics.export.url` is not set; or
- traces are sent over OTLP and `management.opentelemetry.tracing.export.otlp.endpoint` is not set.

Set the address the message names, turn that export off, or turn the module off with
`stratum.observability.enabled=false`.

Whether an export is on follows Spring Boot: `management.otlp.metrics.export.enabled` decides for
metrics, or `management.defaults.metrics.export.enabled` when it is not set;
`management.tracing.export.otlp.enabled` decides for traces, or `management.tracing.export.enabled`
when it is not set. With none of them set, both exports are on. An address or switch that Spring
Boot reads from the standard OpenTelemetry environment variables, such as
`OTEL_EXPORTER_OTLP_ENDPOINT`, counts. With OpenTelemetry itself off
(`management.opentelemetry.enabled=false` or `OTEL_SDK_DISABLED=true`), Spring Boot sends no spans,
so only the metrics address is needed.

Without the check, metrics would go to `localhost:4318` and traces nowhere, and nobody would notice.
Both `http` and `https` addresses are accepted, because most Collectors run inside the cluster.
There is no exception for the `local` or `test` profile: if you do not want to send anything there,
turn the module off in that profile with `stratum.observability.enabled=false`.

### Running without a Collector

On your laptop you may point the exports at `localhost` without running a Collector. For each
export whose address is on this machine (`localhost`, `127.0.0.1` or `[::1]`), Stratum turns off
the log of a failed send: Micrometer's warning for metrics and OpenTelemetry's error for spans, each
of which would otherwise print a stack trace at every attempt. The data is dropped without those
logs. One warning stays, at any address: when spans are made faster than they can be sent,
OpenTelemetry keeps at most 2048 waiting by default (Spring Boot's
`management.opentelemetry.tracing.export.max-queue-size`), and at each export after a drop it logs
`BatchSpanProcessor dropped <n> span(s) since the last export because the queue is full`. Each
dropped span is also counted ([dropped spans](../../docs/modules/observability-traces.md#dropped-spans)).

To see the failed sends again, set the two log levels, in `application.properties`:

```properties
logging.level.io.micrometer.registry.otlp.OtlpMeterRegistry=warn
logging.level.io.opentelemetry.exporter.internal.http.HttpExporter=error
```

or in `application.yml`:

```yaml
logging:
  level:
    io.micrometer.registry.otlp.OtlpMeterRegistry: warn
    io.opentelemetry.exporter.internal.http.HttpExporter: error
```

With the gRPC transport, the second logger is `io.opentelemetry.exporter.internal.grpc.GrpcExporter`.
Any other address, such as `http://otel-collector:4318`, keeps both: a warning with a stack trace at
every metrics push, every minute by default, and an error with a stack trace for each batch of
spans that cannot be sent, so a broken Collector in a shared environment is noticed. The service
keeps running.

## Properties

Every key this starter reads or gives a default. Stratum's own keys come first. For a Spring Boot
key, Stratum only sets a default: where Spring Boot has a key, Stratum has none of its own, and a
value you set wins, from any file, the environment, the command line or Java default properties.

| Property | Type | Stratum's default | Spring Boot's default | Description |
| --- | --- | --- | --- | --- |
| `stratum.observability.enabled` | boolean | `true` | no such key | Whether the starter is on. With `false`, the starter sets up nothing and turns export off. |
| `stratum.observability.tags.extra-forbidden` | list of strings | empty | no such key | More tag names the [tag guard](#tag-guard) refuses, added to the built-in ones. Case, `_`, `-` and `.` are ignored. A blank name stops startup with `SX-CORE-1003`. |
| `stratum.observability.tags.allowed` | list of strings | empty | no such key | Tag names the [tag guard](#tag-guard) keeps even though they are refused by default, such as `customerId`. Compared like `extra-forbidden`. A blank name stops startup with `SX-CORE-1003`. |
| `management.otlp.metrics.export.enabled` | boolean | `false` while the module is off | `true` | Whether metrics are sent. |
| `management.tracing.export.enabled` | boolean | `false` while the module is off | `true` | Whether spans are sent. |
| `management.endpoints.web.exposure.include` | list of endpoint IDs | `health,info` | `health` | The actuator endpoints served over HTTP. Add other endpoints only behind authentication. |
| `management.metrics.tags.application` | string | value of `spring.application.name` | none | The `application` tag on every metric. |
| `management.metrics.tags.environment` | string | value of `stratum.environment` | none | The `environment` tag on every metric. |
| `management.metrics.tags.host` | string | value of the `HOSTNAME` environment variable | none | The `host` tag on every metric, the pod's name on Kubernetes. |
| `management.opentelemetry.resource-attributes[deployment.environment.name]` | string | value of `stratum.environment` | none | The environment on every span. |
| `management.opentelemetry.resource-attributes[service.instance.id]` | string | `service.instance.id` from `OTEL_RESOURCE_ATTRIBUTES`, else a random [instance ID](../../docs/configuration/reference.md#instance-id) | none, so no instance ID | The running copy on every span and metric. |
| `management.metrics.distribution.percentiles-histogram.all` | boolean | `true` | none, so no histogram | Every timing is a [histogram](#timings). `false` keeps only count, sum and max. |
| `management.otlp.metrics.export.histogram-flavor` | `explicit-bucket-histogram` or `base2-exponential-bucket-histogram` | `base2-exponential-bucket-histogram` | `explicit-bucket-histogram` | How histograms are sent. |
| `management.opentelemetry.tracing.sampler` | `always-on`, `always-off`, `trace-id-ratio`, `parent-based-always-on`, `parent-based-always-off` or `parent-based-trace-id-ratio` | `trace-id-ratio` | `parent-based-trace-id-ratio` | How each service chooses the traces it keeps: by trace ID, so a caller cannot ask for more ([sampling](../../docs/modules/observability-traces.md#sampling)). |
| `management.opentelemetry.tracing.limits.max-attribute-value-length` | int | `1024` | no limit | The longest attribute value on a span, in characters; longer values are cut ([span size](../../docs/modules/observability-traces.md#span-size)). |
| `management.opentelemetry.tracing.limits.max-attributes` | int | `64` | `128` | The most attributes one span keeps. |
| `management.opentelemetry.tracing.limits.max-events` | int | `16` | `128` | The most events one span keeps. |
| `spring.task.execution.propagate-context` | boolean | `true` | `false` | Whether `@Async` methods and Spring Boot's executors keep the trace ([threads](#threads-and-reactive-code)). |
| `spring.reactor.context-propagation` | `limited` or `auto` | `auto` | `limited` | Whether every Reactor operator keeps the trace, not only `handle` and `tap` ([threads](#threads-and-reactive-code)). |
| `spring.kafka.template.observation-enabled`, `spring.kafka.listener.observation-enabled` | boolean | `true` | `false` | Whether Kafka sends and listeners are traced and timed ([messages](#messages)). |
| `spring.rabbitmq.template.observation-enabled`, `spring.rabbitmq.listener.simple.observation-enabled`, `.direct.observation-enabled`, `.stream.observation-enabled` | boolean | `true` | `false` | Whether RabbitMQ sends and listeners are traced and timed ([messages](#messages)). |
| `logging.level.io.micrometer.registry.otlp.OtlpMeterRegistry` | log level | `off` when `management.otlp.metrics.export.url` is on this machine, otherwise unset | unset, so the root level | Hides the warning of a failed metrics push ([running without a Collector](#running-without-a-collector)). |
| `logging.level.io.opentelemetry.exporter.internal.http.HttpExporter`, `logging.level.io.opentelemetry.exporter.internal.grpc.GrpcExporter` | log level | `off` when `management.opentelemetry.tracing.export.otlp.endpoint` is on this machine, otherwise unset | unset, so the root level | Hides the error of a failed span send ([running without a Collector](#running-without-a-collector)). |

The shared [`stratum.environment`](../../docs/configuration/reference.md#shared-properties) sets the
`environment` tag and the `deployment.environment.name` resource attribute. The shared context
switches are described in [threads and reactive code](#threads-and-reactive-code).

## Examples

Refuse tags that hold tenant and device IDs, as well as the built-in ones, and keep `customerId`
for a business-to-business service with a few dozen customers:

```properties
stratum.observability.tags.extra-forbidden=tenantId,deviceId
stratum.observability.tags.allowed=customerId
```

```yaml
stratum:
  observability:
    tags:
      extra-forbidden:
        - tenantId
        - deviceId
      allowed:
        - customerId
```

When a service you call reads only B3 headers, send B3 next to W3C `traceparent` on every call
made with Spring's HTTP clients:

```properties
management.tracing.propagation.produce=w3c,b3
```

```yaml
management:
  tracing:
    propagation:
      produce: w3c,b3
```

Keep every trace while you develop, in `application-local.properties` or `application-local.yml`.
Point the exports at `localhost`; start a Collector on your laptop if you want to see the data, or
leave it off and the data is dropped ([running without a Collector](#running-without-a-collector)):

```properties
management.otlp.metrics.export.url=http://localhost:4318/v1/metrics
management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:4318/v1/traces
management.tracing.sampling.probability=1.0
```

```yaml
management:
  otlp:
    metrics:
      export:
        url: http://localhost:4318/v1/metrics
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: http://localhost:4318/v1/traces
  tracing:
    sampling:
      probability: 1.0
```

Send metrics to Prometheus by scraping instead of pushing. Add `io.micrometer:micrometer-registry-prometheus`
(its version comes from Spring Boot), expose the endpoint, and turn OTLP metrics off. With Stratum's
histograms on, a timer with the default bounds becomes 69 `_bucket` series per set of tags in the
scrape: 68 bounds from 1 ms to 30 s, plus `+Inf`. The `percentiles-histogram.all` line below turns
them off, leaving count, sum and max; to keep them for one metric, also set
`management.metrics.distribution.percentiles-histogram.<metric-name>=true`.

```properties
management.endpoints.web.exposure.include=health,info,prometheus
management.metrics.distribution.percentiles-histogram.all=false
management.otlp.metrics.export.enabled=false
```

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  metrics:
    distribution:
      percentiles-histogram:
        all: false
  otlp:
    metrics:
      export:
        enabled: false
```

> [!CAUTION]
> Expose only the endpoints you need, and never `*` in a shared environment. Endpoints such as
> `env`, `heapdump` and `threaddump` can reveal configuration and memory contents.

## Configure in Java

Every `stratum.observability.*` setting above can also be set in a Java class, which receives your
files' settings and wins:

```java
public class OrdersObservabilitySettings implements ObservabilityPropertiesCustomizer {

  @Override
  public ObservabilityProperties customize(ObservabilityProperties properties) {
    var tags = properties.tags().toBuilder().extraForbidden(List.of("tenantId")).build();
    return properties.toBuilder().tags(tags).build();
  }
}
```

List it in `src/main/resources/META-INF/spring.factories`, as
[Set the same values in Java](../../docs/configuration/overriding-defaults.md#set-the-same-values-in-java)
shows; as a `@Bean` it stops startup with `SX-CORE-1004`:

```properties
com.exepex.stratum.observability.ObservabilityPropertiesCustomizer=com.acme.orders.config.OrdersObservabilitySettings
```

The switch and the Spring Boot properties above are set in Java through
[default properties](../../docs/configuration/overriding-defaults.md#switches-shared-properties-and-spring-boot-properties-in-java).

## Customize

- **Your own metrics and spans:** use Micrometer's `MeterRegistry` or `@Observed` (which needs
  AspectJ on the classpath and `management.observations.annotations.enabled=true`). For a business
  workflow, use the logging module's
  [`@Workflow`](../stratum-starter-logging/README.md#workflows), which also names its log lines.
- **Rename or drop metrics, change tags:** declare a Micrometer `MeterFilter` bean.
- **Your own URL cleaning:** declare a `TelemetryUrlFilter` bean; Stratum's then backs off.
- **Your own tag rule:** declare a `MetricTagGuard` bean; Stratum's then backs off.
- **Your own correlation ID handling:** declare a `CorrelationIdRecorder` bean; Stratum's then
  backs off.
- **Exception data on spans:** a bean named `stratumExceptionFreeSpans` replaces the
  post-processor that keeps exception data off your spans
  ([errors on spans](../../docs/modules/observability-traces.md#errors-on-spans)); declare it
  `static`, as any `BeanPostProcessor`. Your spans then carry the exception's message, which can
  hold personal data.
- **Not replaceable:** the Spring Boot defaults and the export address check run before any bean
  exists, so no bean of yours replaces them. `stratum.observability.enabled` turns them all off;
  turning an export off also satisfies the check for that export ([startup check](#startup-check)).
  The quiet local logging gives way to your own `logging.level` for those loggers
  ([running without a Collector](#running-without-a-collector)).
- **Custom health check:** implement `HealthIndicator`; add it to the readiness group with
  `management.endpoint.health.group.readiness.include`.

> [!WARNING]
> Never put unbounded values (user IDs, order IDs, URLs with IDs) into metric **tags**. The
> [tag guard](#tag-guard) catches common ID names, but not every name: a tag called `customer` that
> holds IDs gets through. Put such values in logs or span attributes instead.

## Switch it on or off

The starter is on when it is on the classpath; leaving `stratum.observability.enabled` unset, or
empty, is the same as `true`. With `stratum.observability.enabled=false`, metrics and traces are not
sent, and Spring Boot's own actuator defaults apply.

## Planned

| Feature | What it adds |
| --- | --- |
| How each app type is observed | Docs for batch, command-line and reactive services |
| Cloud queues | Traces and timings for Amazon SQS, Azure Service Bus and Google Pub/Sub, with the cloud starters |
| Vendor and cloud recipes | Collector setups for Datadog, New Relic, Splunk, Dynatrace, AWS, Azure and Google Cloud |
| Profiling | The Pyroscope profiler, after a licence and overhead check |

## Troubleshooting

| Symptom | Likely cause and fix |
| --- | --- |
| Startup stops with `SX-OBSERVABILITY-1001` | Set the property the message names to your Collector's address, turn that export off, or turn the module off with `stratum.observability.enabled=false`. |
| No metrics or traces arrive | `stratum.observability.enabled` is not `true`, or the Collector is not reachable at the configured address. Look for export errors in the log. |
| Traces break between two services | The caller does not use Spring's `RestClient`, `WebClient` or `RestTemplate` from Spring Boot's builder, so no `traceparent` header is sent. |
| A trace has spans from only some services | The services have different `management.tracing.sampling.probability` values, so each keeps a different set of traces. Give every service in the chain the same probability ([sampling](../../docs/modules/observability-traces.md#sampling)). |
| A message's trace ends at the sender | The consumer service does not have this module on, or sets its listener's `observation-enabled` switch to `false` ([messages](#messages)). |
| No `host` tag | `HOSTNAME` is not set in the service's environment. |
| A metric is missing and the log shows `SX-OBSERVABILITY-1002` | One of its tags is named like an ID. Remove the tag, or rename it if it holds only a few fixed values. |
| No percentiles in the metrics backend | It does not accept OTLP exponential histograms; see [Timings](#timings). |
| `/actuator/prometheus` returns 404 | Prometheus is not set up; see the [example](#examples). |
| Readiness stays `DOWN` | Open `/actuator/health/readiness` as an authorised caller to see which component fails. |
