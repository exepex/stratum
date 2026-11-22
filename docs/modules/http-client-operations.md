# HTTP client: operations

How one method of a client's interface gets timeouts of its own, such as a slow monthly report on a client whose
other calls are fast. Part of the [HTTP client](http-client.md) page, whose property table lists every key with its
type and default.

[← HTTP client](http-client.md) · Design: [ADR 0033](../adr/0033-http-client-starters-and-settings.md), decision 2,
and [ADR 0034](../adr/0034-http-client-auth-resilience-and-connections.md), decision 2.4

## An operation

An **operation** is the methods of a client's interface marked `@StratumOperation` with its name: lowercase letters,
digits and hyphens, used once per client.

```java
@StratumClient("reports")
public interface ReportsClient {

  @StratumOperation("monthly-report")
  @GetExchange("/reports/monthly")
  Report monthlyReport();

  @GetExchange("/reports/latest")
  Report latest();
}
```

Its settings go under `stratum.http-client.clients.<client>.operations.<operation>`, and win over the client's own.
An operation reads only three keys:

| Key under `operations.<operation>.` | What it sets | Unset |
| --- | --- | --- |
| `read-timeout` | The longest wait for the first byte of the operation's answers and between two of their bytes. | the client's `read-timeout` |
| `total-timeout` | The time allowed for a whole call of the operation: every attempt, its wait for a connection, the waits between attempts and its answer's head and body, except a streamed download's. | the client's `total-timeout` |
| `errors` | Your own error code for each error status of the operation's answers ([your own code for an error status](http-client-errors.md#your-own-code-for-an-error-status)). | the client's codes |

Any other key under `operations.<operation>`, such as `connect-timeout` or `retry.max-retries`, is ignored, as any
unknown key is: the connect timeout, the pool, `max-wait`, the retries and the circuit breaker stay the client's.

## Give one method more time

The reports client answers most calls fast, so it keeps short timeouts, while its monthly report may take 20 seconds:

```properties
stratum.http-client.clients.reports.url=http://reports:8080
stratum.http-client.clients.reports.read-timeout=3s
stratum.http-client.clients.reports.operations.monthly-report.read-timeout=20s
stratum.http-client.clients.reports.operations.monthly-report.total-timeout=30s
```

```yaml
stratum:
  http-client:
    clients:
      reports:
        url: http://reports:8080
        read-timeout: 3s
        operations:
          monthly-report:
            read-timeout: 20s
            total-timeout: 30s
```

Raise both. Each wait for the answer is cut to the time the call's `total-timeout` leaves, so a `read-timeout` raised
alone still ends by the client's `total-timeout`, 8s by default. A `read-timeout` longer than the `total-timeout` is
accepted, as it is for a client.

An operation can also have shorter timeouts than its client, such as a health check that must answer within 500ms:
set `operations.health-check.read-timeout: 500ms`.

## Configure in Java

As every setting, an operation's timeouts can be set in an `HttpClientPropertiesCustomizer` class listed in
`META-INF/spring.factories`, and Java wins over the files:

```java
public class ReportClientSettings implements HttpClientPropertiesCustomizer {

  @Override
  public HttpClientProperties customize(HttpClientProperties properties) {
    var reports =
        properties.clientSettings("reports").toBuilder()
            .operations(
                Map.of(
                    "monthly-report",
                    OperationSettings.builder()
                        .readTimeout(Duration.ofSeconds(20))
                        .totalTimeout(Duration.ofSeconds(30))
                        .build()))
            .build();
    var clients = new HashMap<>(properties.clients());
    clients.put("reports", reports);
    return properties.toBuilder().clients(clients).build();
  }
}
```

```properties
com.exepex.stratum.httpclient.HttpClientPropertiesCustomizer=com.acme.orders.ReportClientSettings
```

## Where it applies

- **Each call of a method marked `@StratumOperation`,** and each of its retries: no new attempt starts with less
  than about 100ms left before the operation's `total-timeout`.
- **A streamed download** (a method that returns `InputStream`) waits up to the operation's `read-timeout` between
  bytes, and is not cut off by a `total-timeout` ([streaming](http-client-streaming.md)).
- **Not** a method with no `@StratumOperation`, a call through the client's `RestClient` or `RestTemplate` bean, or a
  generated interface named under `interfaces`, which cannot carry the annotation: they keep the client's timeouts.
- **Not** the fetch of a service token, which keeps the client's `read-timeout`.
- **One circuit breaker per client.** An operation's attempts count toward its client's breaker. When you set
  `slow-call-duration-threshold`, set it above the longest attempt of any operation, at most that operation's
  `total-timeout`, or the long operation counts as slow for every method of the client
  ([the slow-call rule](http-client-resilience.md#the-circuit-breaker-group)).
- **Above your own time limit.** With an operation's `total-timeout` longer than your service's own response time
  limit, 10s by default ([response timeouts](web-timeouts.md)), your caller is answered `503` at that limit, and a
  call that ignores the interrupt, such as a socket read on a platform thread, may run on until its own deadline.

## Startup errors

- A `read-timeout` or `total-timeout` of 0 or less stops startup with `SX-CORE-1003`, naming the rule
  `durations-positive`, never the value.
- `operations` under `defaults`, or an operation that no method of the client's interfaces carries, stops startup
  with `SX-HTTPC-1010`. An operation's name that is not lowercase letters, digits and hyphens, or that two methods of
  one client carry, stops it with `SX-HTTPC-1009`.

## Timing per endpoint

Stratum's own meter `stratum.http.client.calls` is tagged by client and outcome only. To see the time of each
method's calls, use Spring Boot's `http.client.requests` timer, which is tagged with each attempt's `method` and `uri`
template ([call log and meters](http-client-observability.md)).
