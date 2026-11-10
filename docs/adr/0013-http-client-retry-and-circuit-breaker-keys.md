# ADR 0013: Retry and circuit breaker keys for the HTTP client starters

**Status:** Accepted, 2026-11-09, by the owner, who chose the two groups and every key name at 19:38Z and approved the
design at 21:43Z (issue #263). Replaces one part of [ADR 0011](0011-http-client-starters.md): the keys `retries`,
`retry-delay` and `circuit-breaker` in decision 2.4. Every other part of ADR 0011, and all of
[ADR 0012](0012-http-client-changes-found-while-building.md), stands.

[← Documentation home](../README.md)

## Context

- ADR 0011 gave each client three resilience keys: `retries`, `retry-delay` and `circuit-breaker: true|false`. The
  back-off always doubled, at most 2s, and every breaker used Resilience4j's defaults. A team that needed another
  threshold had to write a `StratumClientCustomizer`.
- The owner asked for every client's retry and breaker to be set per client and under `defaults`, in YAML,
  `.properties` and Java, with safe values when nothing is set and a team free to change them (2026-11-09, 19:06Z,
  19:07Z and 19:21Z).
- Names a team already knows read best: Spring Framework 7's `@Retryable` names its retry settings `maxRetries`,
  `delay`, `multiplier` and `maxDelay`, and Resilience4j names its breaker settings. The owner rejected invented
  names (19:38Z).

## Decision

1. Each client's retry settings are one group, `retry`, and its breaker settings one group, `circuit-breaker`, under
   `stratum.http-client.defaults` and under `stratum.http-client.clients.<name>`. A key falls back on its own: from
   the client to `defaults`, then to Stratum.
2. The keys and Stratum's defaults, which are today's behaviour:
   - `retry.max-retries` 3 (0 to 9), `retry.backoff-policy` `exponential` or `fixed` (`exponential`),
     `retry.delay` 200ms, `retry.multiplier` 2.0 (1.0 to 10.0), `retry.max-delay` unset, which means 2s or `delay`
     when that is longer;
   - `circuit-breaker.enabled` true, `failure-rate-threshold` 50, `sliding-window-size` 100,
     `minimum-number-of-calls` 100, `wait-duration-in-open-state` 60s,
     `permitted-number-of-calls-in-half-open-state` 10, and `slow-call-duration-threshold` and
     `slow-call-rate-threshold` unset, so the slow-call rule stays Resilience4j's own.
3. They replace `retries` (now `retry.max-retries`), `retry-delay` (now `retry.delay`) and
   `circuit-breaker: true|false` (now `circuit-breaker.enabled`).
4. In Java, the groups are the records `ClientRetry` and `ClientCircuitBreaker` and the enum `BackoffPolicy`, built
   in an `HttpClientPropertiesCustomizer` like every other setting. A `StratumClientCustomizer` still runs after the
   keys and wins, for what they cannot express.
5. What stays fixed: which failures are retried and which the breaker counts (ADR 0011, 4.2 and 4.5), a `POST`
   never sent again once written, and one breaker per client.
6. A value out of range, a duration of 0 or less, a `max-delay` below `delay` on an exponential back-off, and a
   slow-call rate without its duration stop startup with `SX-CORE-1003`, naming the rule, never the value. No rule
   rejects `multiplier` or `max-delay` with `fixed`: Stratum's own default `multiplier` reaches every client, so
   such a rule would stop every service that chose `fixed`. Both keys are ignored with `fixed`.

## Consequences

- A service that sets nothing behaves as before (test `ClientResilienceTest.defaultsAreTodays`).
- A team moves `retries: 3` to `retry.max-retries: 3`, `retry-delay: 200ms` to `retry.delay: 200ms` and
  `circuit-breaker: false` to `circuit-breaker.enabled: false`. An old `circuit-breaker` value stops startup with
  Spring Boot's report naming the key; an old `retries` or `retry-delay` is ignored, as any unknown key is. IDEs flag
  the three old keys under `defaults` as errors; the keys of one client cannot be flagged, because `clients` is a map,
  so the migration guide lists them. No service runs on Stratum yet, so no running service loses its settings.
- A startup check that would reject the old keys was weighed and not chosen: it would scan the property sources for
  keys no running service has set. The owner can ask for it.
- Resilience4j's own Spring Boot keys (`resilience4j.circuitbreaker.instances.<name>.*`) were not chosen: they need a
  new dependency and would give one client two places for its settings.
- **How the decision weighs (ST-AG-14):**
  - Speed and throughput: the keys are read once, when a client's retry and breaker are built on its first call;
    nothing changes per call. The total wait stays capped by `total-timeout`.
  - Log volume: unchanged, one `WARN` per change of a breaker's state.
  - Memory and leaks: a count-based window holds one entry per attempt; the cap of 10000 bounds it per client.
  - Data leaks and security: the values are numbers, durations and an enum, and startup errors name the rule, never
    the value. `max-retries` stays at most 9 and trial calls at most 1000, so neither can flood a downstream.
  - Maintainability and scalability: two records merged like the other settings, on stable Resilience4j APIs, and
    one noisy client can open early without making the others stricter.
- The keys are documented on [HTTP client: retries and the circuit breaker](../modules/http-client-resilience.md).
