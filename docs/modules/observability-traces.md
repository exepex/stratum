# Observability: traces

This page belongs to the [observability starter](../../starters/stratum-starter-observability/README.md).

## Correlation ID

The correlation ID ties together every step of one business workflow, such as one order. While an
operation is traced, Stratum keeps it in the logging context (SLF4J's MDC) under `correlationId`,
so every log line and every [error report](../guides/error-codes.md#the-trace-span-and-correlation-ids)
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

Baggage travels with the trace: Spring's HTTP clients, `WebClient` in reactive code included, send the
[allowed entries](#baggage) in the W3C `baggage` header on every outgoing call, to every host, third
parties included, and Kafka, JMS and RabbitMQ senders put them in a `baggage` message header
([messages](../../starters/stratum-starter-observability/README.md#messages)). So the correlation ID
set in baggage reaches every service you call or send a message to.

> [!WARNING]
> Never put a secret or personal data, such as a customer's name or email address, in the
> correlation ID or any other baggage entry. Every host your service calls receives it.

The web starters also take it from the `X-Correlation-Id` header ([web](web.md#correlation-id)).

## Baggage

Stratum reads and sends only the baggage entries you allow: by default only `correlationId`, plus
the names in Spring Boot's `management.tracing.baggage.remote-fields`, which Spring Boot also sends
in headers of their own. Any other entry a caller sends is dropped when the call or message is read,
so your code never sees it and never passes it on. Any other entry your own code sets is not sent in
the `baggage` header. Names match exactly, case included: `CORRELATIONID` is not `correlationId`.

One exception comes from Spring Boot: it sends, in a header of its own, each entry your code sets
whose name equals a remote field ignoring case, such as `TENANTID` for the remote field `tenantId`.

Allow more names with `stratum.observability.baggage.allowed`. The list you set **replaces** the
default, so keep `correlationId` in it: without it, the correlation ID that callers send, and the one
the web starters take from `X-Correlation-Id`, is dropped, and each service uses its own trace ID.

Pass a caller's `tenantId` on, and also send `region` in a header of its own:

```properties
stratum.observability.baggage.allowed=correlationId,tenantId
management.tracing.baggage.remote-fields=region
```

```yaml
stratum:
  observability:
    baggage:
      allowed:
        - correlationId
        - tenantId
management:
  tracing:
    baggage:
      remote-fields:
        - region
```

The same allowed names in Java, in an `ObservabilityPropertiesCustomizer` listed in
`META-INF/spring.factories` ([configure in Java](../../starters/stratum-starter-observability/README.md#configure-in-java)),
which wins over the files. Spring Boot's `remote-fields` is set in Java through
[default properties](../configuration/overriding-defaults.md#switches-shared-properties-and-spring-boot-properties-in-java).

```java
public class OrdersObservabilitySettings implements ObservabilityPropertiesCustomizer {

  @Override
  public ObservabilityProperties customize(ObservabilityProperties properties) {
    var baggage = new ObservabilityProperties.Baggage(List.of("correlationId", "tenantId"));
    return properties.toBuilder().baggage(baggage).build();
  }
}
```

Set an allowed entry in your own code with Micrometer's `Tracer`; calls and messages inside the block
send it:

```java
try (var tenant = tracer.createBaggageInScope("tenantId", order.tenantId())) {
  shippingClient.ship(order); // sends baggage: correlationId=...,tenantId=...
}
```

The list applies to the propagators that Spring Boot's tracing and OpenTelemetry use, including a
`ContextPropagators` bean you declare yourself. Known limits:

- Allowed entries still go to every host you call, third parties included. Sending baggage only to
  your own hosts is planned ([#171](https://github.com/exepex/stratum/issues/171)).
- Code that calls OpenTelemetry's `W3CBaggagePropagator` itself, and the OpenTelemetry Java agent,
  do not go through the list.
- Reading a call or message limits all the baggage current at that moment, so an entry your code set
  before it, and did not allow, is dropped there too.

## URLs on spans and metrics

Spring records the full URL of every outgoing HTTP call on its span, and the URI template in the
`uri` tag of the span and of the `http.client.requests` metrics. Stratum keeps the query string on
the span, up to 1,024 characters ([span size](#span-size)), so you can see which values were sent,
and changes two things:

| Recorded by Spring | Kept on the span | Kept in the `uri` tag |
| --- | --- | --- |
| `https://partner.com/rates?currency=EUR&page=2` | `https://partner.com/rates?currency=EUR&page=2` | `/rates` |
| `https://user:secret@partner.com:8443/rates` | `https://partner.com:8443/rates` | `/rates` |

- **User info is removed** from the span's URL, because it is a password.
- **The `uri` tag loses its query**, on spans and metrics, because each distinct value would create
  a new time series and can overload the metrics backend.

A URL that cannot be parsed is recorded as `[UNPARSABLE_URL]`. Declaring your own
`TelemetryUrlFilter` bean replaces this cleaning, and turning the module off turns it off with the
rest of the module. It applies when Spring's web classes are on the classpath, which is the only
way Spring makes HTTP client spans and metrics.

> [!WARNING]
> Never put a secret or personal data, such as a token, an IBAN or a card number, in a URL's path
> or query string. Trace backends store the URL as it is, and so do proxies and access logs on the
> way. Send such values in a header or the request body. When a call fails, Spring's error message
> also names the URL with its user info, such as
> `I/O error on GET request for "https://user:secret@partner.com/rates"`. Exported spans do not carry that
> message ([errors on spans](#errors-on-spans)), but your logs do if your code logs the exception.

## Errors on spans

Spans are for following a request across services, not for logging. An exception's message can
hold personal data, such as a customer's email address, so Stratum sends no exception data to the
trace backend:

| Recorded by Spring and OpenTelemetry | Sent with the span |
| --- | --- |
| Status `ERROR` | Kept, so a backend can still find and count failed spans |
| The status description, which is the exception's message | Removed |
| The `exception` event, with the exception's type, message and stack trace | Removed |
| The `exception` attribute (the type, or `none`) and, with Spring's OpenTelemetry conventions, `error.type` | Removed, on every span |

The exception's details belong in your logs. Each log line carries the trace ID, so a failed span
leads to its log lines in your log backend. Metrics are not changed: `http.server.requests` and
the other timers keep their `exception` or `error` tag.

Stratum removes this data when the spans are sent, from every exporter that Spring Boot's span
processor uses, your own `SpanExporter` beans included, and from spans made with the OpenTelemetry
API directly. Until then, a span waiting in the queue still holds its exception. Two kinds of code
see spans before Stratum removes the data, or without it: a Micrometer `SpanReporter` bean, and a
`SpanProcessor` bean of your own that sends spans through an exporter it holds itself.

## Error codes on requests

With a web starter, every incoming request's `http.server.requests` timer has an `error.code` tag:
the code of the exception the request failed with, such as `ORD-0001`, or of its first cause that
has one, else `none`. It follows the same rule as the `error.code` log field, so a timer, a span
and a log line of the same failure carry the same code. The request's server span carries the
code only when there is one; `error.code=none` is removed when the span is sent. You write no code
and set no property; the tag goes on the timers that Spring Boot's
`management.observations.http.server.requests.name` names.

On Spring MVC and WebFlux alike, a failure that Stratum answers is recorded on the request: its
timer gets the `error` and `exception` tags and its span the `ERROR` status. That covers a
`StratumException`, an exception that implements `FailureWithErrorCode`, any other exception from
a controller, a request that runs out of time (`RequestTimedOutException`) and a body over its
limit (`RequestBodyTooLargeException`). A code that only Stratum's error body holds, such as
`SX-WEB-1003` for an unknown path, is not a code the request failed with, so the tag is `none`.

A failure that your own `@ExceptionHandler` answers is not recorded, on either stack, as Spring
does not record it. To count it, record it yourself in the handler: on Spring MVC with
`ServerHttpObservationFilter.findObservationContext(request).ifPresent(context ->
context.setError(exception))`, and on WebFlux with
`ServerRequestObservationContext.findCurrent(exchange.getAttributes())` in the same way. That
tags the timer; the span keeps its status. With `stratum.web.enabled=false`, Spring MVC records
none of the failures its own resolvers answer, as without Stratum, while WebFlux still does.

Each distinct code adds one timer per route and status, within the
[meter caps](observability-metrics.md).

## Span size

Each span waits in a queue of up to 2,048 spans until it is sent. So that a span cannot hold
megabytes, Stratum lowers three of Spring Boot's span limits (the `limits` keys in the starter's
[property table](../../starters/stratum-starter-observability/README.md#properties)): a value longer
than 1,024 characters is cut to 1,024, and a span keeps at most 64 attributes and 16 events; later
ones are dropped.

The values most likely to be cut are long URLs and SQL text (`db.query.text`). An exception's stack
trace is rendered only up to 1,024 characters, so failed requests also cost less. The backend is
not told that something was cut: Micrometer's exporter rebuilds each span from what it kept, so the
dropped counts reach the backend as zero.

To keep more, set Spring Boot's key; your value wins:

```properties
management.opentelemetry.tracing.limits.max-attribute-value-length=4096
```

```yaml
management:
  opentelemetry:
    tracing:
      limits:
        max-attribute-value-length: 4096
```

Not bounded by these keys:

- Attribute names, the number of items in a list attribute, and span names (only values are cut).
- The `Throwable` behind each exception event, with its stack, and the status description, which
  is the exception's message. Both are held until the span is sent; Stratum removes them then
  ([errors on spans](#errors-on-spans)).
- Code that uses the OpenTelemetry API directly: events with many attributes (up to 128 each),
  extra attributes on `recordException` (up to 64), and links (up to 128, with 128 attributes
  each).
- A `SpanLimits` bean or an `SdkTracerProvider` of your own, which ignore these keys.

## Dropped spans

When spans are made faster than they can be sent, the queue fills (2,048 spans by default) and
OpenTelemetry drops each new span until there is room. Stratum counts them in the counter
`stratum.observability.spans.dropped` (in Prometheus, `stratum_observability_spans_dropped_total`),
with the `application` and `host` tags of every metric. Set an alert on it, for example:

```promql
sum by (application, host) (increase(stratum_observability_spans_dropped_total[5m])) > 0
```

with `for: 10m` and a warning severity. When it fires, check that the Collector can be reached,
then raise `management.opentelemetry.tracing.export.max-queue-size` or lower
`management.tracing.sampling.probability`.

Spring Boot sends up to 512 spans in one batch
(`management.opentelemetry.tracing.export.max-batch-size`). Stratum has not checked a full batch of
large spans, at the [span size limits](#span-size), against the Collector's receive limit, which is
4 MiB per message over gRPC unless the Collector raises it. A batch the Collector refuses loses its
spans: it is logged as an error, like any batch that cannot be sent, but not counted by the counter
above ([#228](https://github.com/exepex/stratum/issues/228)).

The count comes from OpenTelemetry's own span processor, and Spring Boot gives that processor
the service's OpenTelemetry `MeterProvider` bean. If your service declares its own
`MeterProvider`, it replaces Stratum's, and the counter is not registered. To turn the counter
off, set Spring Boot's key:

```properties
management.metrics.enable.stratum.observability.spans.dropped=false
```

```yaml
management:
  metrics:
    enable:
      "[stratum.observability.spans.dropped]": false
```

## Health probes

Kubernetes probes and load balancers call the health endpoint every few seconds on every pod.
Stratum keeps those calls out of traces: a probe request, and any timed work inside it such as a
health check of your own, makes no span. Its `http.server.requests` timer stays, so probe latency
and failures still show in metrics. Logs written during a probe carry no trace ID.

A probe is a request to the health endpoint or below it (`/actuator/health/liveness`), to a health
group's `additional-path`, or to `/livez` and `/readyz` when
`management.endpoint.health.probes.add-additional-paths=true`. The paths follow
`management.endpoints.web.base-path`, `management.endpoints.web.path-mapping.health`, the server's
base path (`server.servlet.context-path` or `spring.webflux.base-path`) and
`management.server.base-path`. To trace probes again:

```properties
stratum.observability.tracing.health-probes=true
```

```yaml
stratum:
  observability:
    tracing:
      health-probes: true
```

Two cases still make spans: a span your health check starts with the tracer or OpenTelemetry's
API directly, which becomes a trace of its own; and, on Spring MVC with a separate management
port, timed work in a health check, because Spring Boot does not time those probe requests.

## Sampling

A trace is the record of one request across every service it reaches. Keeping every trace costs
CPU, network and storage, so each service keeps only some of them. This is **sampling**. Deciding
when a trace starts, before anyone knows how it ends, is **head sampling**. It is what Spring Boot
does, and what Stratum sets up.

### How a service chooses

Spring Boot keeps 10% of traces by default (`management.tracing.sampling.probability=0.10`).
Stratum sets Spring Boot's `management.opentelemetry.tracing.sampler` to `trace-id-ratio`, so each
service decides from the trace ID alone:

- A trace is kept when the last 16 hex digits of its trace ID, read as a signed number, are close
  enough to zero. For the default probability the limit is `0cccccd000000000`: a trace ID ending in
  `0ccccccfffffffff` is kept, and one ending in `0cccccd000000000` is not.
- The caller's sampled flag does not decide. That covers the `01` flag in `traceparent`, the `1`
  or debug `d` flag in `b3`, and `X-B3-Sampled` or `X-B3-Flags`. A caller cannot make a service keep
  more than its probability of traces this way.
- Every service with the same probability gives the same answer for the same trace ID. So when all
  the services of a chain share one probability, each trace is kept whole or not at all.

For callers that do not choose their own trace IDs, a service exports at most its probability times
its traffic. Spring Boot's own default, `parent-based-trace-id-ratio`, follows the caller's flag
instead.

> [!WARNING]
> A service whose probability differs from its caller's keeps a different set of traces, so some
> traces have spans from only some services. With a lower probability downstream, the callee's
> spans of traces its caller kept are missing. With a higher one, the callee keeps traces its caller
> dropped. Give every service in a chain the same probability.

To follow the caller's decision again, for example in a service that only trusted services behind
a gateway call, set Spring Boot's own sampler:

```properties
management.opentelemetry.tracing.sampler=parent-based-trace-id-ratio
```

```yaml
management:
  opentelemetry:
    tracing:
      sampler: parent-based-trace-id-ratio
```

The standard OpenTelemetry environment variables `OTEL_TRACES_SAMPLER` and `OTEL_TRACES_SAMPLER_ARG`
also work. Spring Boot reads them into these two keys, and they win over every other value.

**Known gap:** a caller that chooses its own trace IDs can still force sampling. An ID whose last
16 hex digits are close enough to zero, such as `…ffffffffffffffff` (−1), is always kept. Until
Stratum handles this on edge services ([#162](https://github.com/exepex/stratum/issues/162)), have
your gateway start a new trace for callers you do not trust. That means dropping or replacing their
`traceparent`, `tracestate`, `b3` and every `X-B3-*` header.

### Keeping every error: tail sampling

Head sampling drops 90% of failed requests too. To keep every error and every slow request, decide
after the trace has ended. This is **tail sampling**. Stratum does not do it in the service: the
OpenTelemetry Collector does, between your services and your tracing backend. It is a choice for
the whole chain of services, and it needs both of these:

1. **Every service in the chain sends every span**, with
   `management.tracing.sampling.probability=1.0`. The Collector can only keep what services send.
   A callee left at 0.1 drops 90% of its spans before the Collector sees them, error or not.
2. **Every span of one trace reaches the same Collector instance**, because each instance decides
   from the spans it holds. With more than one instance, put a load-balancing tier in front that
   routes by trace ID.

```properties
management.tracing.sampling.probability=1.0
```

```yaml
management:
  tracing:
    sampling:
      probability: 1.0
```

The load-balancing tier receives the services' spans and sends each trace to one instance of the
tail-sampling tier. Here, the tail-sampling instances share the DNS name `tail-samplers`:

```yaml
receivers:
  otlp:
    protocols:
      http:
        endpoint: 0.0.0.0:4318
exporters:
  loadbalancing:
    routing_key: traceID
    protocol:
      otlp:
        tls:
          insecure: true
    resolver:
      dns:
        hostname: tail-samplers
        port: "4317"
service:
  pipelines:
    traces:
      receivers: [otlp]
      exporters: [loadbalancing]
```

Each tail-sampling instance holds the spans of up to 100,000 traces in memory (`num_traces`) and
decides 10 seconds after the trace's first span (`decision_wait`). It keeps a trace if any span
failed, or if the trace lasted longer than 2 seconds from its earliest start to its latest end, and
drops the rest. Replace `debug` with the exporter of your tracing backend, and turn TLS on between
the tiers outside a test:

```yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
processors:
  tail_sampling:
    decision_wait: 10s
    num_traces: 100000
    policies:
      - name: keep-errors
        type: status_code
        status_code:
          status_codes: [ERROR]
      - name: keep-slow
        type: latency
        latency:
          threshold_ms: 2000
exporters:
  debug:
    verbosity: detailed
service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [tail_sampling]
      exporters: [debug]
```

These two configurations were run once with `otel/opentelemetry-collector-contrib:0.140.0`: one
load-balancing instance and two tail-sampling instances. Nine traces were sent, each with two
spans in separate requests: three fast and successful, three with a failed span and three slow.
The six failed or slow traces were kept with both spans, each on one instance, using both
instances. The three fast ones were dropped.

What a probability of 1.0 costs each service, measured once against 0.10. The setup was
OpenTelemetry SDK 1.62.0 on JDK 25, with 400,000 requests of two spans each, sent over OTLP without
compression:

| Probability | Time on the request thread | CPU of the whole process | OTLP bytes sent |
| --- | --- | --- | --- |
| 0.10 | about 0.7 µs per request | about 2.8 µs per request | about 55 per request |
| 1.0 | about 1.5 µs per request | about 16.3 µs per request | about 530 per request |

So 1.0 sends about ten times the bytes, about 530 KB per second at 1,000 requests per second. The
CPU column includes receiving the data on the same machine, so it overstates the service's own
cost. The Collector's cost comes on top, and grows with `num_traces` and `decision_wait`.
