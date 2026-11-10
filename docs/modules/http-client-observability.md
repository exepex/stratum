# HTTP client: call log and meters

**Status: Partly built.** Every call of a Stratum client is written to the log once when it fails, and counted in
the meters: how long it took and how it ended, how many retries ran, the circuit breaker's state, and how full each
connection pool is. Trace headers (`trace-headers`) are planned ([Planned](#planned)).

[← HTTP client](http-client.md) · [← Documentation home](../README.md) · Property prefix: `stratum.http-client` ·
Design: [ADR 0016](../adr/0016-http-client-call-log-and-meters.md), [ADR 0011](../adr/0011-http-client-starters.md),
decision 8

## What every call gets

Every way of calling a client gets the same line and meters, with no code: its `@StratumClient` interfaces,
`stratumHttpClients.restClient("<name>")`, and its `RestClient` and `RestTemplate` beans.

- **A failed call writes one WARN line**, after its retries. "Failed" means it ended with an error answer (4xx or 5xx),
  a timeout, no connection, a full pool, a service token that could not be fetched, or a failure inside your service,
  such as an answer of an interface method that cannot be read ([Limits](#limits) says when the body is read).
- A call that the open circuit breaker refused writes **no** line: it is counted instead (`outcome=REFUSED`).
- A `404` that a method returning `Optional` reads as empty is not a failure: it writes nothing.
- A successful call writes nothing, unless the client has `call-log: all`.

Each client writes through its own logger, `stratum.http-client.<name>`, so you can turn one client's line off with
`logging.level.stratum.http-client.orders=off`.

## The line

Call: `ordersClient.find(8812, "OPEN")`, with `@GetExchange("/orders/{id}")` and a `@RequestParam status`; the other
service answers `503` four times, so the call is sent once and retried three times.

### With the logging starter

While the [logging starter](../../starters/stratum-starter-logging/README.md) is on, its redaction runs over every
line, so the line also carries the real address it called, `url`, with secrets and personal data masked. The facts are
key-value fields, so you can search them. In the default JSON format (ECS):

```json
{"@timestamp":"2026-11-09T18:40:12.345Z","log.level":"WARN","log.logger":"stratum.http-client.orders",
 "message":"HTTP client call failed","client":"orders","method":"GET","uri":"/orders/{id}?{queryParam-status}={queryParam-status[0]}",
 "url":"http://orders:8080/orders/8812?status=OPEN","status":503,"outcome":"SERVER_ERROR","attempts":4,
 "durationMs":1412,"traceId":"4bf92f3577b34da6a3ce929d0e0e4736","correlationId":"checkout:777"}
```

In the logging starter's `text` format, the message is followed by each field as `key=value`:

```text
2026-11-09T18:40:12.345Z  WARN [nio-8080-exec-1] stratum.http-client.orders : HTTP client call failed client=orders method=GET uri=/orders/{id}?{queryParam-status}={queryParam-status[0]} url=http://orders:8080/orders/8812?status=OPEN status=503 outcome=SERVER_ERROR attempts=4 durationMs=1412
```

### Without it

Without the logging starter, or with `stratum.logging.enabled=false`, nothing masks the line, so it never holds the
real address: no `url`. Spring Boot's default console pattern prints only the message, so the facts go into it, and
`uri` stops at its first `?` and at 256 characters:

```text
2026-11-09T18:40:12.345Z  WARN 1 --- [orders-service] [nio-8080-exec-1] stratum.http-client.orders : HTTP client call failed: client=orders method=GET uri=/orders/{id} status=503 outcome=SERVER_ERROR attempts=4 durationMs=1412
```

### The fields

| Field | What it holds |
| --- | --- |
| `client` | The client's name. |
| `method` | The HTTP method. |
| `uri` | The URI template as Spring keeps it, such as `/orders/{id}`, never a value; Spring writes a `@RequestParam` into it as `{queryParam-status}`. `none` for a call given a whole `URI`, and for every `RestTemplate` call, which has no template. |
| `url` | The address the last attempt was sent to, path and query included. Only with the logging starter on. |
| `status` | The status of the error answer. None for a timeout, a failed connection or a success. |
| `outcome` | How the call ended (below). |
| `attempts` | The attempts sent; a call the breaker refused part-way counts only those sent. |
| `durationMs` | The time of the whole call, retries and waits included. |

The trace and correlation IDs come from the logging context, as on every line. The line never holds a request or
response header, a body, the exception or its message, or the status text.

| `outcome` | When |
| --- | --- |
| `SUCCESS` | An answer that is not an error, or a `404` that a method returning `Optional` reads as empty. |
| `CLIENT_ERROR` | An error answer of 4xx. |
| `SERVER_ERROR` | An error answer of 5xx. |
| `TIMEOUT` | No answer within the client's `timeout`. |
| `UNAVAILABLE` | No answer for any other reason: a failed connection or connect timeout, an unknown host, a full pool, a dropped connection, or a service token that could not be fetched. |
| `ERROR` | A failure inside your service, such as an answer that cannot be read. |
| `REFUSED` | The open breaker refused the call before any attempt was sent. Counted, never logged. |

## What the redaction masks in `url`

With the logging starter on, its redaction masks, in `url` and in `uri`:

- a secret under a telling name, such as `api_key=****` or `email=****`;
- a JWT, and a user and password in the address, written `http://****@host`;
- a plain email, card number or IBAN, found by its shape: `****.com`, `****1111`, `****3000`.

**The rule for what is not masked:** a value the redactor finds by its shape is not found once Spring percent-encodes
a character in it. That covers an email (`@` becomes `%40`), a card number or IBAN written with spaces (`%20`), and
any of your own `stratum.logging.redaction.extra-patterns` that needs such a character. So
`/users/jane%40example.com`, `?q=4111%201111%201111%201111` and `?r=DE89%203704%200044%200532%200130%2000` are
written as sent. Spring percent-encodes the values it puts into an address, from a `@RequestParam`, a `@PathVariable`
or a `{}` placeholder, so an email passed that way is written as sent too. A value under a telling name is still
masked by its name, and a card or IBAN with dashes or no separator is still found. Issue [#273](https://github.com/exepex/stratum/issues/273) teaches the redactor to read
percent-escapes.

**Never put personal data in a URL.** Free text with no telling name, such as `?name=Priya`, is written as it is.

**Build every address with `{}` placeholders.** A team's own call can pass a whole address as its template:

```java
// Do this: the value is a placeholder, so uri holds the template only.
orders.get().uri("/users/{email}/orders", email).retrieve().body(String.class);

// Never this: the value is joined into the template.
orders.get().uri("/users/" + email + "/orders").retrieve().body(String.class);
```

Without the logging starter, the second line's `uri` holds the email: the cut at `?` and 256 characters keeps a
joined query out of the line, not a joined path.

A value longer than the logging starter's `max-field-length` (16 KB) is cut at a word boundary, and a URL is one
word, so a URL that long is written as `...[truncated]`.

The OpenTelemetry Java agent and OpenTelemetry's log appender read each event before any format, so they see `url`
unmasked, as they see every line: mask in the OpenTelemetry Collector, as the logging starter's page says.

## The key

`call-log` is listed in the module's [property table](http-client.md#properties): `errors` (the default), one WARN
per failed call; `all`, also one INFO per successful call; `none`, no line, while the meters still count every call.

Set it under `stratum.http-client.defaults` for every client, or under `stratum.http-client.clients.<name>` for one;
the client's value wins. A value that is none of the three stops startup with Spring Boot's binding error naming the
key. Use `all` while you onboard a new partner, and `none` for a health pinger whose failures are expected.

`application.yml`:

```yaml
stratum:
  http-client:
    defaults:
      call-log: errors          # the default: one WARN per failed call
    clients:
      orders:
        url: http://orders:8080
      inventory:
        url: http://inventory:8080
        call-log: all           # every call: INFO on success, WARN on failure
      status-pinger:
        url: http://pinger:8080
        call-log: none          # no line, meters only
```

`application.properties`, the same values:

```properties
stratum.http-client.defaults.call-log=errors
stratum.http-client.clients.orders.url=http://orders:8080
stratum.http-client.clients.inventory.url=http://inventory:8080
stratum.http-client.clients.inventory.call-log=all
stratum.http-client.clients.status-pinger.url=http://pinger:8080
stratum.http-client.clients.status-pinger.call-log=none
```

### Configure in Java

The same values for the two changed clients, in an `HttpClientPropertiesCustomizer`; Java wins over the files:

```java
public class PartnerClientSettings implements HttpClientPropertiesCustomizer {

  @Override
  public HttpClientProperties customize(HttpClientProperties properties) {
    var inventory = properties.clientSettings("inventory").toBuilder().callLog(CallLog.ALL).build();
    var pinger =
        properties.clientSettings("status-pinger").toBuilder().callLog(CallLog.NONE).build();
    var clients = new HashMap<>(properties.clients());
    clients.put("inventory", inventory);
    clients.put("status-pinger", pinger);
    return properties.toBuilder().clients(clients).build();
  }
}
```

```properties
# META-INF/spring.factories
com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer=com.acme.orders.PartnerClientSettings
```

## The meters

The meters are made when Micrometer and a meter registry are present, as with Spring Boot's Actuator or the
[observability starter](../../starters/stratum-starter-observability/README.md). Without them the starter works the
same and makes no meters. Each meter is tagged `client`, the client's name.

| Meter | Kind | Tags | What it shows |
| --- | --- | --- | --- |
| `stratum.http.client.calls` | timer | `client`, `outcome` | Each whole call, retries and waits included: count, total time and longest. |
| `stratum.http.client.retries` | counter | `client` | Attempts sent after the first; retries per call is this divided by the calls' count. |
| `stratum.http.client.circuitbreaker.state` | gauge | `client`, `state` = `closed`, `open` or `half_open` | 1 for the breaker's current state, 0 for the others; from the client's first call. |
| `stratum.http.client.pool.refused` | counter | `client` | Attempts refused because no connection came free within `max-wait`. |
| `httpcomponents.httpclient.pool.total.max` | gauge | `httpclient`, `client` | The pool's `max-connections`. |
| `httpcomponents.httpclient.pool.total.connections` | gauge | `httpclient`, `client`, `state` = `leased` or `available` | Busy and idle connections. |
| `httpcomponents.httpclient.pool.total.pending` | gauge | `httpclient`, `client` | Calls waiting for a free connection. |
| `httpcomponents.httpclient.pool.route.max.default` | gauge | `httpclient`, `client` | The most connections per route, equal to `max-connections` here. |

- The four pool gauges come from Micrometer's Apache HttpClient 5 binder, from the client's first call. A client with
  `shared-pool: true` reports the shared pool under `client=_shared`; the small pools that fetch service tokens report
  nothing.
- No tag ever holds a path value, a query value or a header: `outcome` and `state` have the fixed values above.
- Spring Boot's own `http.client.requests` timer stays as it is: one per attempt, tagged with the template.
- How long a call waits for a free connection is not measured yet
  ([#237](https://github.com/exepex/stratum/issues/237)).

## Limits

- **Log volume.** At most one line per failed call, and none per refused call. With the default breaker, a dead
  service opens it after about 25 failed calls, so about 25 lines, then a few per `wait-duration-in-open-state`. A
  4xx answer does not count toward the breaker, so a storm of 400s writes one line per call: the logging starter caps
  each logger at 5000 WARN lines per second, and without it there is no cap.
- **A streamed download** is recorded when its headers arrive; a failure while your code reads the body is not seen.
- **`restClient(name)` and the `RestClient` and `RestTemplate` beans** end the call when a successful answer's
  headers arrive: Spring reads its body afterwards. A body that cannot be read, or whose reading times out, writes
  no line and is not counted as a failure, and the calls timer leaves out the time spent reading it. A call of an
  interface method includes the reading.
- **A `StratumClientCustomizer` that replaces the client's request factory** also replaces the part that notes the
  address: that client's line has no `url`.
- **Log4j2:** the key-value fields are checked under Logback, the default; not checked under Log4j2.

## Planned

- `trace-headers`: `X-Correlation-Id` on every call, and `false` to send no trace or correlation header to a third
  party. Spring Boot's tracing already sends `traceparent` and `baggage` on Stratum clients when tracing is on.
