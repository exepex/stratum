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

The `stratum-starter-microservice`, `-web-ui` and `-batch` bundles already include it. The
`-cli` bundle does not: a command-line tool adds it on its own when it needs it. The version comes
from Stratum's parent or BOM ([installation](../../docs/getting-started/installation.md)).

The starter does nothing until you switch it on, and once it is on it needs your Collector's
addresses, in every profile. In `application.properties`:

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
| Timings as histograms | Every timer, such as HTTP server and client calls, `@Scheduled` jobs and Spring Batch steps, is sent as a base-2 exponential histogram ([below](#timings)) |
| Tag guard | A metric with a tag named like an ID, such as `userId`, is dropped with one warning; the RabbitMQ routing key is removed from send timers ([below](#tag-guard)) |
| Correlation ID | In the logging context of every traced operation, so log lines and error reports carry it ([below](#correlation-id)) |
| Other threads | `@Async` methods and Spring Boot's executors keep the caller's trace and correlation ID ([below](#threads-and-reactive-code)) |
| Messages | Kafka, JMS (IBM MQ, ActiveMQ) and RabbitMQ: sending and handling a message are timed and traced, and the consumer joins the sender's trace and correlation ID ([below](#messages)) |
| Traces | OpenTelemetry traces. W3C `traceparent` is read from incoming requests and sent on calls made with Spring's HTTP clients. Spring Boot keeps 10% of new traces, and always follows the caller's decision, so a trace is never half kept |
| Trace resource | `service.name` from `spring.application.name` (set by Spring Boot), and `deployment.environment.name` from `stratum.environment` |
| URLs on spans | The full URL of each HTTP client call, query string included, is kept on its span; only the user info is removed. Metrics never carry the query ([below](#urls-on-spans-and-metrics)) |
| Health | `/actuator/health`, with Spring Boot's Kubernetes `liveness` and `readiness` groups |
| Safe exposure | Only `health` and `info` are reachable over HTTP |
| Startup check | With the module on, startup stops if an export address is missing, in every profile ([below](#startup-check)) |
| No Collector on your laptop | With an address on `localhost`, metrics and spans that cannot be sent are dropped without a warning ([below](#running-without-a-collector)) |

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
exponential histograms. If it does not, send classic fixed buckets instead with
`management.otlp.metrics.export.histogram-flavor=explicit-bucket-histogram`.

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

### Correlation ID

The correlation ID ties together every step of one business workflow, such as one order. While an
operation is traced, Stratum keeps it in the logging context (SLF4J's MDC) under `correlationId`,
so every log line and every [error report](../../docs/guides/error-codes.md#the-trace-span-and-correlation-ids)
carries it:

- If the tracing **baggage** (values that travel with the trace) has a `correlationId` entry, that
  is the ID. Stratum also records it on each new span, so a trace can be found by it.
- Otherwise the ID is the **trace ID**. Every service in the same trace has the same trace ID, so
  the correlation ID is the same across them too.

A baggage value is used only if it is 1 to 128 letters, digits, `-`, `_`, `.` or `:`; anything
else, such as a line break, is ignored and the trace ID is used, so a caller cannot forge a log
line. Outside a traced operation Stratum sets no correlation ID. Stratum owns the `correlationId`
key while tracing: a value your code puts in the MDC directly is replaced or removed when the
current span changes, so use the baggage instead.

To set a correlation ID in your own code, put it in the baggage with Micrometer's `Tracer`:

```java
try (var correlation = tracer.createBaggageInScope("correlationId", order.correlationId())) {
  // log lines, error reports and new spans here carry the order's correlation ID
}
```

Baggage travels with the trace: Spring's HTTP clients, `WebClient` in reactive code included, send it
in the W3C `baggage` header on every outgoing call, to every host, third parties included, and
Kafka, JMS and RabbitMQ senders put it in a `baggage` message header ([messages](#messages)). So the
correlation ID set in baggage reaches every service you call or send a message to.

> [!WARNING]
> Never put a secret or personal data, such as a customer's name or email address, in the
> correlation ID or any other baggage entry. Every host your service calls receives it.

**Planned:** the web starter will take the ID from the `X-Correlation-Id` header, and the logging
starter will limit the baggage to your own hosts.

### Messages

With the module on, sending and handling a message are observed like an HTTP call:

- **Traces.** The sender adds the W3C `traceparent` and `baggage` headers to the message. The
  consumer reads them, so its span is part of the sender's trace, and its log lines carry the
  sender's trace ID and [correlation ID](#correlation-id).
- **Timings.** Each send and each handled message is timed, as a [histogram](#timings) like every
  other timing.
- **Errors.** A Kafka or RabbitMQ listener that throws records its timing with the `error` tag set
  to an exception's class name: on Kafka the listener's own exception, such as
  `IllegalStateException`; on RabbitMQ Spring's wrapper, `ListenerExecutionFailedException`. Every
  retry is counted. A message handled without error has `error=none`. JMS timers do not show
  failures: a `@JmsListener` that throws is still recorded with `error=none`, so count JMS failures
  from your logs.
- **Kafka batch listeners.** Spring Kafka observes listeners that take one record at a time. A
  `@KafkaListener` with `batch = "true"` does not join the sender's trace and has no correlation ID,
  and its `spring.kafka.listener` timer carries Spring Kafka's older tags, `name`, `result` and
  `exception`, instead of the ones below. Spring Kafka can observe each record of a batch, if you
  turn on `setRecordObservationsInBatch(true)` on the listener container's properties.

| Technology | Timers | How it is switched on |
| --- | --- | --- |
| Kafka (`spring-kafka`) | `spring.kafka.template` (send), `spring.kafka.listener` (handle) | Stratum sets `spring.kafka.template.observation-enabled` and `spring.kafka.listener.observation-enabled` to `true`; Spring Boot leaves them off |
| JMS, such as IBM MQ or ActiveMQ | `jms.message.publish` (send), `jms.message.process` (handle) | Spring Boot observes `JmsTemplate` and `@JmsListener` on its own |
| RabbitMQ (`spring-rabbit`) | `spring.rabbit.template` (send), `spring.rabbit.listener` (handle) | Stratum sets `spring.rabbitmq.template.observation-enabled` and `spring.rabbitmq.listener.{simple,direct,stream}.observation-enabled` to `true`; Spring Boot leaves them off |

Kafka and RabbitMQ timers carry the topic or queue name, such as `messaging.destination.name`.
JMS timers do not: Micrometer keeps the JMS queue name on the span only. No timer carries a message
ID or a Kafka record key.

Spring also tags the RabbitMQ send timer, `spring.rabbit.template`, with the message's routing key
(`messaging.rabbitmq.destination.routing_key`). A routing key can hold an ID, such as
`order.42.created`, and each value would make its own time series, so the [tag guard](#tag-guard)
always removes that tag. Sends are still timed per destination, just not per routing key.

This works with the senders and listeners Spring Boot sets up, such as `KafkaTemplate`,
`@KafkaListener`, `JmsTemplate` and `@JmsListener`. This starter adds no messaging library, so
Stratum's switches do nothing until your service brings Kafka or RabbitMQ.

To turn them all off, set `stratum.observability.trace-kafka-and-rabbitmq-messages=false`. To turn
one off, set its switch to `false`, for example `spring.kafka.listener.observation-enabled=false`.

### Threads and reactive code

Stratum sets Spring Boot's `spring.task.execution.propagate-context=true`, unless
`stratum.context-propagation.async-threads=false`, so `@Async` methods, Spring Boot's task executor
and executors built with its `ThreadPoolTaskExecutorBuilder` keep the caller's trace and correlation
ID. Threads your code creates itself do not: `Executors` pools, `new Thread`, virtual threads you
start, `new ThreadPoolTaskExecutor()` and `CompletableFuture.supplyAsync` without an executor. Wrap
your own pool with `ContextExecutorService.wrap(pool)`; [log fields across
threads](../../docs/guides/context-across-threads.md) lists every case.

In reactive code, Stratum sets Spring Boot's `spring.reactor.context-propagation=auto` and turns on
Reactor's context propagation, even without WebFlux or WebClient. Every line in a WebFlux handler or
any operator carries the request's trace and correlation ID, never another request's, and `WebClient`
sends `baggage` like `RestClient`. `stratum.context-propagation.reactor=false` keeps Spring Boot's
`limited` mode, where only `handle` and `tap` lines carry them. The same setting carries the logging
starter's [workflow fields](../stratum-starter-logging/README.md#async-and-reactive-work).

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

With the module on, in every profile, startup stops with
[`SX-OBSERVABILITY-1001`](../../docs/guides/error-codes.md#stratum-starter-observability) if:

- metrics are sent over OTLP and `management.otlp.metrics.export.url` is not set; or
- traces are sent over OTLP and `management.opentelemetry.tracing.export.otlp.endpoint` is not set.

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
of which would otherwise print a stack trace at every attempt. The data is dropped quietly. To see
them again, set `logging.level.io.micrometer.registry.otlp.OtlpMeterRegistry=warn` or
`logging.level.io.opentelemetry.exporter.internal.http.HttpExporter=error` (`...grpc.GrpcExporter`
with the gRPC transport). Any other address, such as `http://otel-collector:4318`, keeps both: a
warning with a stack trace at every metrics push, every minute by default, and an error with a stack
trace for each batch of spans that cannot be sent, so a broken Collector in a shared environment is
noticed. The service keeps running.

## Properties

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `stratum.observability.enabled` | boolean | `false` | Switches the whole starter on. Until it is `true`, the starter sets up nothing and turns export off. |
| `stratum.observability.actuator-endpoints-exposed-over-http` | list of strings | `health,info` | The actuator endpoints reachable over HTTP. Add others only behind authentication. A blank name stops startup with `SX-CORE-1003`. |
| `stratum.observability.record-timings-as-histograms` | boolean | `true` | Whether every timing is stored as a base-2 exponential histogram ([timings](#timings)). `false` keeps only count, sum and max. |
| `stratum.observability.trace-kafka-and-rabbitmq-messages` | boolean | `true` | Whether Kafka and RabbitMQ messages are traced and timed ([messages](#messages)). `false` turns Spring Boot's switches off. |
| `stratum.observability.tags.extra-forbidden` | list of strings | empty | More tag names the [tag guard](#tag-guard) refuses, added to the built-in ones. Case, `_`, `-` and `.` are ignored. A blank name stops startup with `SX-CORE-1003`. |
| `stratum.observability.tags.allowed` | list of strings | empty | Tag names the [tag guard](#tag-guard) keeps even though they are refused by default, such as `customerId`. Compared like `extra-forbidden`. A blank name stops startup with `SX-CORE-1003`. |

The shared [`stratum.environment`](../../docs/configuration/reference.md#shared-properties) sets the
`environment` tag and the `deployment.environment.name` resource attribute. The shared context
switches are described in [threads and reactive code](#threads-and-reactive-code).

Spring Boot properties this starter sets by default. A value you set wins, from any file, the
environment, the command line or Java default properties:

| Spring Boot property | Stratum default |
| --- | --- |
| `management.otlp.metrics.export.enabled` | `false` while the module is off |
| `management.tracing.export.enabled` | `false` while the module is off |
| `management.endpoints.web.exposure.include` | value of `stratum.observability.actuator-endpoints-exposed-over-http` |
| `management.metrics.tags.application` | value of `spring.application.name` |
| `management.metrics.tags.environment` | value of `stratum.environment` |
| `management.metrics.tags.host` | value of the `HOSTNAME` environment variable |
| `management.opentelemetry.resource-attributes[deployment.environment.name]` | value of `stratum.environment` |
| `management.metrics.distribution.percentiles-histogram.all` | value of `stratum.observability.record-timings-as-histograms` |
| `management.otlp.metrics.export.histogram-flavor` | `base2-exponential-bucket-histogram` |
| `spring.task.execution.propagate-context` | value of `stratum.context-propagation.async-threads` ([threads](#threads-and-reactive-code)) |
| `spring.reactor.context-propagation` | `auto`, or `limited` when `stratum.context-propagation.reactor=false` |
| `spring.kafka.template.observation-enabled`, `spring.kafka.listener.observation-enabled` | value of `stratum.observability.trace-kafka-and-rabbitmq-messages` |
| `spring.rabbitmq.template.observation-enabled`, `spring.rabbitmq.listener.simple.observation-enabled`, `.direct.observation-enabled`, `.stream.observation-enabled` | value of `stratum.observability.trace-kafka-and-rabbitmq-messages` |

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

Keep every trace while you develop, in `application-local.properties` or `application-local.yml`.
Point the exports at `localhost`; start a Collector on your laptop if you want to see the data, or
leave it off and the data is dropped quietly:

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
histograms on, each timer becomes about 70 `_bucket` series per set of tags in the scrape. The
`record-timings-as-histograms` line below turns them off, leaving count, sum and max; to keep them
for one metric, also set `management.metrics.distribution.percentiles-histogram.<metric-name>=true`.

```properties
stratum.observability.actuator-endpoints-exposed-over-http=health,info,prometheus
stratum.observability.record-timings-as-histograms=false
management.otlp.metrics.export.enabled=false
```

```yaml
stratum:
  observability:
    actuator-endpoints-exposed-over-http: health,info,prometheus
    record-timings-as-histograms: false
management:
  otlp:
    metrics:
      export:
        enabled: false
```

> [!CAUTION]
> Expose only the endpoints you need, and never `*` in a shared environment. Endpoints such as
> `env`, `heapdump` and `threaddump` can reveal configuration and memory contents.

## Configure in Java

Every setting above can also be set in a Java class, which receives your files' settings and wins:

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
- **Not replaceable:** the Spring Boot defaults, the export address check and the quiet local
  logging run before any bean exists, so only `stratum.observability.enabled` turns them off.
- **Custom health check:** implement `HealthIndicator`; add it to the readiness group with
  `management.endpoint.health.group.readiness.include`.

> [!WARNING]
> Never put unbounded values (user IDs, order IDs, URLs with IDs) into metric **tags**. The
> [tag guard](#tag-guard) catches common ID names, but not every name: a tag called `customer` that
> holds IDs gets through. Put such values in logs or span attributes instead.

## Switch it on or off

`stratum.observability.enabled=true` switches the starter on. Leaving it unset is the same as
`false`: metrics and traces are not sent, and Spring Boot's own actuator defaults apply.

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
| Startup stops with `SX-OBSERVABILITY-1001` | Set the property the message names to your Collector's address, or turn that export off. |
| No metrics or traces arrive | `stratum.observability.enabled` is not `true`, or the Collector is not reachable at the configured address. Look for export errors in the log. |
| Traces break between two services | The caller does not use Spring's `RestClient`, `WebClient` or `RestTemplate` from Spring Boot's builder, so no `traceparent` header is sent. |
| A message's trace ends at the sender | The consumer service does not have this module on, or sets its listener's `observation-enabled` switch to `false` ([messages](#messages)). |
| No `host` tag | `HOSTNAME` is not set in the service's environment. |
| A metric is missing and the log shows `SX-OBSERVABILITY-1002` | One of its tags is named like an ID. Remove the tag, or rename it if it holds only a few fixed values. |
| No percentiles in the metrics backend | It does not accept OTLP exponential histograms; see [Timings](#timings). |
| `/actuator/prometheus` returns 404 | Prometheus is not set up; see the [example](#examples). |
| Readiness stays `DOWN` | Open `/actuator/health/readiness` as an authorised caller to see which component fails. |
