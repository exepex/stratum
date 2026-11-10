# HTTP client: retries and the circuit breaker

When a Stratum client sends a failed call again, how long it waits first, and when its circuit breaker stops calls
for a while. Each client sets these in two groups, `retry` and `circuit-breaker`, under `defaults` for every client
or under one client. Part of the [HTTP client](http-client.md) page, whose property table lists every key with its
type and default.

[← HTTP client](http-client.md) · Design: [ADR 0011](../adr/0011-http-client-starters.md), decision 4, and
[ADR 0013](../adr/0013-http-client-retry-and-circuit-breaker-keys.md)

## With nothing set

Every client gets today's safe values, and a service sets no key:

- up to 3 retries of a call that is safe to repeat, the first after about 200ms, each next wait doubled, at most 2s;
- a circuit breaker with Resilience4j's own defaults: it opens when half of the last 100 attempts failed, once it has
  seen 100, stays open for 60s, then lets 10 trial calls through. Its slow-call rule, 60s at 100%, is never reached
  while `total-timeout` stays at 8s.

A **circuit breaker** watches a client's recent attempts. While most of them work it is **closed** and calls go
through. When too many fail it **opens**: every call fails at once, without being sent, so a service that is down
gets time to recover. After a while it is **half open**: a few trial calls go through, and their result closes it or
opens it again.

Each key falls back on its own: a key a client leaves unset comes from `defaults`, and one `defaults` leaves unset
from Stratum. A client that sets only `failure-rate-threshold` keeps every other value.

## Which calls are retried

The rules below are fixed, and safe for any service. The keys set how often and how long to wait, never what is
sent again.

| A failed attempt | Sent again? |
| --- | --- |
| The connection could not be opened (refused, connect timeout, unknown host) | Yes, for every method: nothing was sent. |
| A `502`, `503`, `504` or `429` answer | Only a `GET`, `HEAD`, `OPTIONS`, `PUT` or `DELETE`. |
| No answer, or a body cut off: a read timeout or a dropped connection | Only a `GET`, `HEAD`, `OPTIONS`, `PUT` or `DELETE`. |
| The client's pool is full | Never: more calls would add to the load. |
| A request body streamed from a `Resource`, also as a multipart part | Never: it cannot be sent twice. |
| Any other answer, such as `500` or `404` | Never. |

`POST`, `PATCH` and any other method are sent again only when the connection could not be opened.

A `503` or `429` with a `Retry-After` of whole seconds or an HTTP date waits that long instead of the back-off. A wait
set in `customizeRetry` replaces both and is checked against `total-timeout` the same way. A call is sent again only
while the wait still leaves at least 100ms before `total-timeout`; otherwise the last answer or failure is the call's
result. Each attempt's connection wait, connect timeout and `timeout` are cut to the time left, and reading an
answer's body stops when `total-timeout` runs out, with `SX-HTTPC-0001`. A method that returns
an `InputStream`, a streamed download, is the exception: no new attempt starts after `total-timeout`, but each
attempt keeps its full `timeout`, the longest gap between bytes, so a long download is never cut off. Its headers may
therefore arrive up to `timeout` after `total-timeout`, and reading the stream is bounded only by `timeout` between
bytes.

## The `retry` group

| Key under `retry.` | What it does | A real case |
| --- | --- | --- |
| `max-retries` | How many times a failed call is sent again, from 0 to 9: 3 means at most 4 attempts. | `0` for an audit sink, where a repeat is pointless. |
| `backoff-policy` | `exponential`: each wait is `multiplier` times the last. `fixed`: every wait is `delay`. | `fixed` with a short `delay` for a cache-like service that recovers at once. |
| `delay` | The wait before the first retry. | `50ms` for a fast, noisy service. |
| `multiplier` | How much each wait grows, from 1.0 to 10.0. Read only with `exponential`; ignored with `fixed`. | `3` for a payment service that needs a fast first retry and then backs off. |
| `max-delay` | The longest single wait, at least `delay`. Unset, it is 2s, or `delay` when that is longer. Read only with `exponential`; ignored with `fixed`. | `1s` to keep a payment call's retries short. |

Under both policies each wait is spread at random by up to half its length, so that many callers do not retry at
the same instant.

## The `circuit-breaker` group

The names are Resilience4j's own, so a team that knows Resilience4j reads them at once.

| Key under `circuit-breaker.` | Breaker state it governs | A real case |
| --- | --- | --- |
| `enabled` | Whether the client has a breaker at all. | `false` for an audit sink that must never be cut off. |
| `failure-rate-threshold` | Closed to open: the share of counted attempts, in percent from 1 to 100, that failed. | `10` for a payment client that must open early. |
| `sliding-window-size` | Closed: how many of the latest attempts the rate is taken over, from 1 to 10000. | `20` for a low-traffic client, which at 100 might never open. |
| `minimum-number-of-calls` | Closed: how many attempts it must see before it may open, from 1 to 10000. Above `sliding-window-size`, the window size is used. | `10`, with the window above. |
| `wait-duration-in-open-state` | Open: how long every call fails at once, without being sent, before it is half open. | `10s` for a downstream that recovers in seconds. |
| `permitted-number-of-calls-in-half-open-state` | Half open: how many trial calls go through, from 1 to 1000. Below the threshold it closes, otherwise it opens again. | `1` for a fragile downstream, so one probe goes first. |
| `slow-call-duration-threshold` | Sets the slow-call rule: an attempt that takes longer counts as slow. Unset, Resilience4j's 60s applies. | `2s` for a downstream that slows down before it fails, such as one with a filling database pool. |
| `slow-call-rate-threshold` | Closed to open: the share of slow attempts, in percent from 1 to 100, that opens it. Only with `slow-call-duration-threshold`; when only the duration is set, Resilience4j's `100` applies. | `50`, with the duration above. |

**What counts.** The breaker counts an attempt with no answer, a full pool included, an answer whose body was cut off,
and an answer of `5xx` or `429`. Other `4xx` answers mean the service is up, so they never count. It sees each
attempt, retries included: with `max-retries: 3`, one failing `GET` can be 4 counted attempts, so
`minimum-number-of-calls: 10` can be reached by 3 failing calls. Size the window in attempts, not calls. A call
that was never sent is neither retried nor recorded, as a success or a failure, and in half-open state it gives back
its permit: one where an [interceptor](http-client-headers.md#interceptors) failed, or read a body larger than
`interceptor-body-limit` (`SX-HTTPC-0005`); one whose service token could not be fetched before the first send
(`SX-HTTPC-0002`); and one with no caller token to relay (`SX-HTTPC-0004`). A call that was sent, answered `401` and
then could not get a new token also ends with `SX-HTTPC-0002`, but counts as the `401` does. A
`customizeCircuitBreaker` that replaces Stratum's `ignoreException` rule counts such calls again.

**Without `slow-call-duration-threshold`, the slow-call rule is Resilience4j's own: 60s at 100%.** Resilience4j has no
switch to turn it off. The breaker then opens on slow calls only when every attempt in its window took longer than
60s. With Stratum's `total-timeout` of 8s no attempt lasts that long, so in practice the rule never acts. A client
whose `total-timeout` is above 60s can reach it: set `slow-call-duration-threshold` above that client's longest
expected attempt, or to the value you want. Setting `slow-call-rate-threshold` without the duration stops startup, so
no team thinks it changed the rule when it did not.

When the breaker is open, a call fails with `SX-HTTPC-0003`, which answers `503` ([errors](http-client-errors.md)).
Each change of its state is logged once at `WARN`, such as `The circuit breaker of HTTP client inventory went from
CLOSED to OPEN`. There is one breaker per client.

## Examples

Every client retries twice, from 100ms, and its breaker stays open for 30s. A critical payments client opens early
and probes carefully; a noisy recommendations client waits a fixed 50ms and tolerates more failures; an audit client
has neither retries nor a breaker.

```yaml
stratum:
  http-client:
    defaults:
      retry:                              # every client, unless it sets its own
        max-retries: 2
        delay: 100ms
      circuit-breaker:
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
    clients:
      payments:                           # critical: open early, probe carefully
        url: http://payments:8080
        retry:
          backoff-policy: exponential
          delay: 200ms
          multiplier: 3
          max-delay: 1s
        circuit-breaker:
          failure-rate-threshold: 10
          sliding-window-size: 20
          minimum-number-of-calls: 10
          permitted-number-of-calls-in-half-open-state: 3
          slow-call-duration-threshold: 2s
          slow-call-rate-threshold: 50
      recommendations:                    # noisy and not critical
        url: http://recommendations:8080
        retry:
          backoff-policy: fixed
          delay: 50ms
        circuit-breaker:
          failure-rate-threshold: 80
      audit:                              # no retries, no breaker
        url: http://audit:8080
        retry:
          max-retries: 0
        circuit-breaker:
          enabled: false
```

```properties
stratum.http-client.defaults.retry.max-retries=2
stratum.http-client.defaults.retry.delay=100ms
stratum.http-client.defaults.circuit-breaker.failure-rate-threshold=50
stratum.http-client.defaults.circuit-breaker.wait-duration-in-open-state=30s
stratum.http-client.clients.payments.url=http://payments:8080
stratum.http-client.clients.payments.retry.backoff-policy=exponential
stratum.http-client.clients.payments.retry.delay=200ms
stratum.http-client.clients.payments.retry.multiplier=3
stratum.http-client.clients.payments.retry.max-delay=1s
stratum.http-client.clients.payments.circuit-breaker.failure-rate-threshold=10
stratum.http-client.clients.payments.circuit-breaker.sliding-window-size=20
stratum.http-client.clients.payments.circuit-breaker.minimum-number-of-calls=10
stratum.http-client.clients.payments.circuit-breaker.permitted-number-of-calls-in-half-open-state=3
stratum.http-client.clients.payments.circuit-breaker.slow-call-duration-threshold=2s
stratum.http-client.clients.payments.circuit-breaker.slow-call-rate-threshold=50
stratum.http-client.clients.recommendations.url=http://recommendations:8080
stratum.http-client.clients.recommendations.retry.backoff-policy=fixed
stratum.http-client.clients.recommendations.retry.delay=50ms
stratum.http-client.clients.recommendations.circuit-breaker.failure-rate-threshold=80
stratum.http-client.clients.audit.url=http://audit:8080
stratum.http-client.clients.audit.retry.max-retries=0
stratum.http-client.clients.audit.circuit-breaker.enabled=false
```

## Configure in Java

The same `defaults` and `payments` settings in an `HttpClientPropertiesCustomizer`, which runs after the files and
wins over them. List it in `META-INF/spring.factories`, as the [module page](http-client.md#configure-in-java)
shows.

```java
public class ResilienceSettings implements HttpClientPropertiesCustomizer {

  @Override
  public HttpClientProperties customize(HttpClientProperties properties) {
    var shared = properties.defaults();
    var defaults =
        shared.toBuilder()
            .retry(shared.retry().toBuilder()
                .maxRetries(2)
                .delay(Duration.ofMillis(100))
                .build())
            .circuitBreaker(shared.circuitBreaker().toBuilder()
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build())
            .build();
    var withDefaults = properties.toBuilder().defaults(defaults).build();
    var payments = withDefaults.clientSettings("payments");
    var paymentsSettings =
        payments.toBuilder()
            .retry(payments.retry().toBuilder()
                .backoffPolicy(BackoffPolicy.EXPONENTIAL)
                .delay(Duration.ofMillis(200))
                .multiplier(3.0)
                .maxDelay(Duration.ofSeconds(1))
                .build())
            .circuitBreaker(payments.circuitBreaker().toBuilder()
                .failureRateThreshold(10f)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .permittedNumberOfCallsInHalfOpenState(3)
                .slowCallDurationThreshold(Duration.ofSeconds(2))
                .slowCallRateThreshold(50f)
                .build())
            .build();
    var clients = new HashMap<>(withDefaults.clients());
    clients.put("payments", paymentsSettings);
    return withDefaults.toBuilder().clients(clients).build();
  }
}
```

`clientSettings("payments")` returns the client merged over `defaults`, so the example changes `defaults` first and
reads `payments` after. `payments` sets its own `delay` of 200ms, and takes `max-retries: 2` and
`wait-duration-in-open-state: 30s` from the new `defaults`, as in the YAML.

## Rules the keys cannot express

A time-based window, a custom wait or another rule goes in a `StratumClientCustomizer` bean, through Resilience4j's
builders. Its `customizeRetry` and `customizeCircuitBreaker` run once per client, on its first call, after the keys,
and win over them:

```java
@Bean
StratumClientCustomizer paymentsResilience() {
  return new StratumClientCustomizer() {
    @Override
    public void customize(String client, RestClient.Builder builder) {
      // The client itself stays as Stratum sets it.
    }

    @Override
    public void customizeCircuitBreaker(String client, CircuitBreakerConfig.Builder breaker) {
      if ("payments".equals(client)) {
        breaker.slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
            .slidingWindowSize(60);
      }
    }
  };
}
```

A rule given to `retry.retryOnResult` in `customizeRetry` decides on each `ClientAttempt`: its method, its failure,
the time left and the next wait.

## Startup errors

A value that breaks a rule stops startup with `SX-CORE-1003`, naming the rule, never the value. Range and duration
rules check `defaults` and each client's own keys; the two rules that join two keys check each client merged over
`defaults`, since the keys may come from either.

| Rule in the message | Broken by |
| --- | --- |
| `max-retries-in-range` | `max-retries` below 0 or above 9 |
| `multiplier-in-range` | `multiplier` below 1.0 or above 10.0 |
| `max-delay-at-least-delay` | `max-delay` shorter than `delay`, for a client whose `backoff-policy` is `exponential` |
| `durations-positive` | `delay`, `max-delay`, `wait-duration-in-open-state` or `slow-call-duration-threshold` of 0 or less |
| `failure-rate-threshold-in-range`, `slow-call-rate-threshold-in-range` | a rate below 1 or above 100 |
| `sliding-window-size-in-range`, `minimum-number-of-calls-in-range` | below 1 or above 10000 |
| `permitted-number-of-calls-in-half-open-state-in-range` | below 1 or above 1000 |
| `slow-call-rate-with-duration` | `slow-call-rate-threshold` without `slow-call-duration-threshold`, in `defaults` or a client |

A `backoff-policy` other than `exponential` or `fixed` stops startup with Spring Boot's own report, which names the key,
such as `stratum.http-client.clients.payments.retry.backoff-policy`, and the value it could not read. IDEs offer only
the two values.

## Moving from the old keys

Three keys were replaced by the groups ([ADR 0013](../adr/0013-http-client-retry-and-circuit-breaker-keys.md)):

| Old | New |
| --- | --- |
| `retries: 3` | `retry.max-retries: 3` |
| `retry-delay: 200ms` | `retry.delay: 200ms` |
| `circuit-breaker: false` | `circuit-breaker.enabled: false` |

- An old `circuit-breaker: true` or `false` stops startup: Spring Boot cannot read a value where the group goes, and
  its report names the key.
- An old `retries` or `retry-delay` is ignored, as any unknown key is, so the client keeps Stratum's values. Search
  your files for both.
- IDEs mark `defaults.retries`, `defaults.retry-delay` and `defaults.circuit-breaker` in red, with the new key. They
  cannot mark the keys of one client, because `clients` is a map.
