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

Baggage travels with the trace: Spring's HTTP clients, `WebClient` in reactive code included, send it
in the W3C `baggage` header on every outgoing call, to every host, third parties included, and
Kafka, JMS and RabbitMQ senders put it in a `baggage` message header ([messages](../../starters/stratum-starter-observability/README.md#messages)). So the
correlation ID set in baggage reaches every service you call or send a message to.

> [!WARNING]
> Never put a secret or personal data, such as a customer's name or email address, in the
> correlation ID or any other baggage entry. Every host your service calls receives it.

The web starters also take it from the `X-Correlation-Id` header ([web](web.md#correlation-id)).
**Planned:** the logging starter will limit the baggage to your own hosts.

## URLs on spans and metrics

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

A URL that cannot be parsed is recorded as `[UNPARSABLE_URL]`. Declaring your own
`TelemetryUrlFilter` bean replaces this cleaning, and turning the module off turns it off with the
rest of the module. It applies when Spring's web classes are on the classpath, which is the only
way Spring makes HTTP client spans and metrics.

> [!WARNING]
> Never put a secret or personal data, such as a token, an IBAN or a card number, in a URL's path
> or query string. Trace backends store the URL as it is, and so do proxies and access logs on the
> way. Send such values in a header or the request body. When a call fails, Spring's error message
> also names the URL with its user info, such as
> `I/O error on GET request for "https://user:secret@partner.com/rates"`, and the span records that
> message.

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
