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
`TelemetryUrlFilter` bean replaces this cleaning, and `stratum.observability.enabled=false` turns it
off with the rest of the module. It applies when Spring's web classes are on the classpath, which
is the only way Spring makes HTTP client spans and metrics.

> [!WARNING]
> Never put a secret or personal data, such as a token, an IBAN or a card number, in a URL's path
> or query string. Trace backends store the URL as it is, and so do proxies and access logs on the
> way. Send such values in a header or the request body. When a call fails, Spring's error message
> also names the URL with its user info, such as
> `I/O error on GET request for "https://user:secret@partner.com/rates"`, and the span records that
> message.
