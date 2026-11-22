# Retry and circuit breaker settings in files and Java: design note

Round 3 of the note. Round 2 was rewritten to the keys Aashish approved on 2026-11-09 at 19:38Z ("Yes, that looks
better"), and he approved the design at 21:43Z. Round 2 of the note failed its judge (`reviews/judge/breaker-thresholds-note.md`);
its five findings are fixed below, marked **[r3]**. No key, name or default value a team sets changes.
Round 4: Aashish said to fix the round 2 judge's three findings (`reviews/judge/breaker-thresholds-note-r2.md`,
2026-11-09 22:08Z). They are fixed below, marked **[r4]**. No key, name or default changes.
Lane B. Track: **Feature**, because it adds keys and defaults and replaces three keys. Requested by Aashish on
2026-11-09, in lane C's thread, and passed on by the coordinator:

- 19:06Z: set the circuit breaker "per client and or globally ... when the circuit breaker should open and when it
  should be retried ... half open state".
- 19:07Z: "I want the flexibility for both in Java as well as the YAML and property files."
- 19:21Z: "There must be a default circuit breaker configuration as well ... but a team can override if they want."
- On lane C's card he chose a nested `circuit-breaker` group, and at 19:38Z he approved the two groups below.

## What it does

A client's retries are set by one group, `retry`, and its breaker by one group, `circuit-breaker`. Each group can
sit under `defaults`, for every client, or under one client. Each key falls back on its own, from the client to
`defaults` and then to Stratum. A client that sets only `failure-rate-threshold` keeps every other value from
`defaults` or Stratum. With nothing set, every client gets the values below, which are what Stratum does today.

### `retry`

| Key under `retry.` | Type | Default | What it does |
| --- | --- | --- | --- |
| `max-retries` | 0 to 9 | `3` | How many times a failed call is sent again, so 3 means at most 4 attempts. Replaces `retries` |
| `backoff-policy` | `exponential` or `fixed` | `exponential` | `exponential`: each wait is `multiplier` times the last. `fixed`: every wait is `delay` |
| `delay` | duration | `200ms` | The wait before the first retry. Replaces `retry-delay` |
| `multiplier` | 1.0 to 10.0 | `2.0` | How much each wait grows. Only read with `exponential`; with `fixed` it is ignored |
| `max-delay` | duration, at least `delay` | `2s`, or `delay` when that is longer | The longest single wait. Only read with `exponential`; with `fixed` it is ignored |

Each wait is spread at random by up to half its length, under both policies, as today (`RetryRules.BACK_OFF_SPREAD`),
so that many callers do not retry at the same instant. Which calls are retried is unchanged: only those that are
safe to repeat, on a transient failure (ST-ENG-51), and never past `total-timeout`.

### `circuit-breaker`

| Key under `circuit-breaker.` | Type | Default | Breaker state it governs |
| --- | --- | --- | --- |
| `enabled` | boolean | `true` | Whether the client has a breaker at all. Replaces `circuit-breaker: true or false` |
| `failure-rate-threshold` | percent, 1 to 100 | `50` | CLOSED to OPEN: the share of counted attempts that failed |
| `sliding-window-size` | attempts, 1 to 10000 | `100` | CLOSED: how many of the latest attempts the rate is taken over |
| `minimum-number-of-calls` | attempts, 1 to 10000 | `100` | CLOSED: how many attempts it must see before it may open |
| `wait-duration-in-open-state` | duration | `60s` | OPEN: how long every call fails at once, without being sent, before HALF_OPEN |
| `permitted-number-of-calls-in-half-open-state` | calls, 1 to 1000 | `10` | HALF_OPEN: how many trial calls go through. Below the threshold it closes, otherwise it opens again |
| `slow-call-duration-threshold` | duration | not set | Turns on the slow-call rule: an attempt that takes longer counts as slow |
| `slow-call-rate-threshold` | percent, 1 to 100 | not set; Resilience4j's `100` applies when only the duration is set | CLOSED to OPEN: the share of slow attempts that opens it. Only with `slow-call-duration-threshold` |

The breaker names are Resilience4j's own, so a team that knows Resilience4j reads them at once.

**The slow-call rule is off unless `slow-call-duration-threshold` is set.** Resilience4j has no switch for it, so
"off" means Stratum leaves Resilience4j's own values, 60s at 100%, which is today's behaviour. That rule opens only
when every attempt in the window took over 60s, and a whole call is cut at `total-timeout` (8s by default) long
before that.
**[r3, F1]** Stratum's own defaults leave both slow-call keys unset, so nothing Stratum ships can trip the check
below. `slow-call-rate-threshold` set without `slow-call-duration-threshold` fails startup, so a team never thinks a
rule is on when it is not. The check reads each client's merged settings (`clientSettings(name)`) and `defaults`, so a
rate in `defaults` with a duration only in one client fails for the other clients, which have no rule.

### YAML

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

### Properties

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

### Java

The existing `HttpClientPropertiesCustomizer`, listed in `META-INF/spring.factories`, runs after the files and wins
over them:

```java
public class ResilienceSettings implements HttpClientPropertiesCustomizer {

  @Override
  public HttpClientProperties customize(HttpClientProperties properties) {
    var defaults =
        properties.defaults().toBuilder()
            .retry(properties.defaults().retry().toBuilder()
                .maxRetries(2)
                .delay(Duration.ofMillis(100))
                .build())
            .build();
    var withDefaults = properties.toBuilder().defaults(defaults).build();
    var payments = withDefaults.clientSettings("payments");
    var paymentsSettings =
        payments.toBuilder()
            .retry(payments.retry().toBuilder()
                .backoffPolicy(BackoffPolicy.EXPONENTIAL)
                .multiplier(3.0)
                .maxDelay(Duration.ofSeconds(1))
                .build())
            .circuitBreaker(payments.circuitBreaker().toBuilder()
                .failureRateThreshold(10)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .slowCallDurationThreshold(Duration.ofSeconds(2))
                .build())
            .build();
    var clients = new HashMap<>(withDefaults.clients());
    clients.put("payments", paymentsSettings);
    return withDefaults.toBuilder().clients(clients).build();
  }
}
```

**[r4, F1]** `clientSettings(name)` returns the client merged over `defaults`, so the example changes `defaults` first
and reads `payments` after. `payments` then gets `max-retries: 2` and `delay: 100ms` from the new `defaults`, as in the
YAML. The docs page says this in one line beside the example. Test: `ClientResilienceTest.javaSettingsApply` sets
`defaults` and one client in one customizer, and checks the client's retries come from the new `defaults`.

A rule the keys cannot express, such as a time-based window or a custom wait, stays in
`StratumClientCustomizer.customizeRetry` and `customizeCircuitBreaker`. Those run after the keys and win, as today.

**What counts:** the breaker sees each attempt, retries included, as today (ADR 0011 5.5). With `max-retries: 3`, one
failing `GET` can be 4 counted attempts, so `minimum-number-of-calls: 10` can be reached by 3 failing calls. The docs
say this next to the table, so a team sizes the window in attempts, not calls.

**Startup failures, each naming the key and never the value** (`SX-CORE-1003`, as `retries-in-range` today).
**[r4, F3]** The range and duration checks read `defaults` and each client's own settings, as today's range checks do
(`allSettings()`, `HttpClientProperties.java:229-231`). The two cross-key checks read `defaults` and every client's
merged settings (`clientSettings(name)`), as today's `isSharedPoolWithoutSslBundle` does (`:182-186`), because the two
keys may come from different levels:

- a number out of its range in either table;
- a duration that is zero or negative;
- `max-delay` shorter than `delay`, **[r4, F2]** only where the merged `backoff-policy` is `exponential`, since `fixed`
  ignores `max-delay`;
- `slow-call-rate-threshold` set without `slow-call-duration-threshold`;
- a `backoff-policy` that is neither `exponential` nor `fixed`. Spring's binding fails on it and Boot's failure
  report names the key. Not verified yet; the code PR proves it with a test, or adds an `@AssertTrue` if it does
  not name the key.

The IDE shows `exponential` and `fixed` as the only values of `backoff-policy`, because it is an enum.

**[r3, F1] Dropped from round 2: the startup check on `multiplier` or `max-delay` set with `backoff-policy: fixed`.**
Stratum's default `multiplier` (2.0) is merged into `defaults` and every client, so that check would fail every service
that chose `fixed`. The tables say instead that both keys are only read with `exponential`, and the docs say a value
set with `fixed` is ignored. The keys and values Aashish approved are unchanged.

**Migration lines** (migration guide, module page and the new ADR named in question 4):

| Old | New |
| --- | --- |
| `retries: 3` | `retry.max-retries: 3` |
| `retry-delay: 200ms` | `retry.delay: 200ms` |
| `circuit-breaker: false` | `circuit-breaker.enabled: false` |

The three old keys are marked in the IDE metadata as removed (`deprecation.level: error`), each with its
replacement, so the IDE flags them in red and offers the new key. **[r3, F4]** That flag covers
`defaults.retries`, `defaults.retry-delay` and `defaults.circuit-breaker` only: the metadata has no entry per client,
because `clients` is a map, so `clients.<name>.retries` is not flagged. The migration guide covers the per-client keys. Whether Spring fails or ignores an old key at
startup is not verified: a scalar under the `circuit-breaker` group may fail binding, and the two retry keys would be
ignored as unknown. No service runs on Stratum yet (ST-AG-04), so no running service can lose its retries. A startup
check that rejects the old keys was weighed and not chosen; see question 2.

## 1. Who uses it, and does anything change for them?

- **Consumers:** every service with a Stratum HTTP client, whether new, existing or hybrid, and whether Stratum is
  its parent or sits next to a company framework (ADR 0001).
  - A service that sets nothing sees no change. Tests: `ClientResilienceTest.defaultsAreTodays`, which compares the
    built breaker with `CircuitBreakerConfig.ofDefaults()` and the built back-off's waits with today's
    `RetryRules.backOff(200ms)`, on the same random seed.
  - A service that wrote `retries`, `retry-delay` or `circuit-breaker: false` changes them as in the table above.
- **API:**
  - `ClientSettings` loses `retries`, `retryDelay` and `circuitBreaker(Boolean)`, and gains `retry` and
    `circuitBreaker`, two `@NestedConfigurationProperty` groups.
  - Three new API types in `com.exepex.stratum.httpclient`: the records `ClientRetry` and `ClientCircuitBreaker`, and
    the enum `BackoffPolicy`. A team builds them in an `HttpClientPropertiesCustomizer`, so they carry no
    `@StratumInternal` (ST-ENG-56) and go on the module's ArchTest API list.
  - `StratumClientCustomizer` is unchanged.
- **Reach:** `ClientResilience.newGuards` builds every client's retry and breaker. That covers the interfaces, and
  lane C's `RestClient` and `RestTemplate` beans through `ResilientRequestFactory` and `ClientResilience.call`. Lane C
  confirmed the beans build no retry or breaker of their own.

## 2. How is it built, and why this way?

- **Reused:**
  - Resilience4j's `CircuitBreakerConfig.Builder`: `failureRateThreshold`, `slidingWindowSize`,
    `minimumNumberOfCalls`, `waitDurationInOpenState`, `permittedNumberOfCallsInHalfOpenState`,
    `slowCallDurationThreshold` and `slowCallRateThreshold`.
  - Resilience4j's `IntervalFunction`: `ofExponentialRandomBackoff(delay, multiplier, spread, maxDelay)`, which
    `RetryRules.backOff` uses today, and `ofRandomized(delay, spread)` for `fixed`.
  - `ClientSettings.orElse`, the merge of a client over `defaults`, and the nested `auth` group as the model.
  - The `@AssertTrue` checks in `HttpClientProperties`, which give `SX-CORE-1003` and name the key.
  - The Java route (`HttpClientPropertiesCustomizer`) and the IDE metadata from record Javadoc.
- **The simplest way, chosen:**
  - `ClientRetry(maxRetries, backoffPolicy, delay, multiplier, maxDelay)` and `ClientCircuitBreaker(enabled,
    failureRateThreshold, slidingWindowSize, minimumNumberOfCalls, waitDurationInOpenState,
    permittedNumberOfCallsInHalfOpenState, slowCallDurationThreshold, slowCallRateThreshold)`. All components are
    `@Nullable`, with `@Builder(toBuilder = true)` and an `orElse(fallback)` that merges key by key.
  - `STRATUM_DEFAULTS` holds today's values. `max-delay` is left unset there, and the back-off uses
    `max(delay, 2s)` when it is unset, which is today's rule. **[r3, F1]** Both slow-call keys are left unset there
    too.
  - `RetryRules.backOff(ClientRetry)` picks the interval function from the policy. `newGuards` reads `maxRetries`,
    and passes the breaker values to the builder, the slow-call pair only when the duration is set. The customizers
    run after, as today.
  - One `@AssertTrue` per rule in the list above, so the error names one key.
  - The constants `DEFAULT_RETRIES`, `MAX_RETRIES`, `DEFAULT_RETRY_DELAY` and `MAX_RETRY_DELAY` stay, and new ones
    hold the multiplier and the breaker ranges.
- **Alternative not chosen: a startup check for the old keys.** It would read the `Environment` for each client's
  `retries`, `retry-delay` and scalar `circuit-breaker` and fail with a code from `SX-HTTPC-1010`. That is one more
  class and a scan of property sources, for keys no running service has set. The IDE flag and the migration guide
  cover a team moving a client to Stratum. The owner can ask for the check.
- **Alternative not chosen: flat keys** next to `retries` and `circuit-breaker: true|false`. The owner chose the
  groups.
- **Alternative not chosen: Resilience4j's own Spring Boot keys** (`resilience4j.circuitbreaker.instances.<name>.*`).
  They need the `resilience4j-spring-boot` module, a new dependency (ST-ENG-62), and would make a second place to set
  one client's behaviour. Spring Boot has no retry or breaker keys for HTTP clients (ST-ENG-34).
- **Module and package:** the three types are in `libs/stratum-http-client`, next to `ClientSettings` and
  `ClientAuth`, and are read in the starter's `ClientResilience` and `RetryRules`. That is today's direction, so no
  cycle can form (ST-ENG-57).
- **A real case for each key:**
  - `max-retries: 0` for an audit sink where a repeat is pointless.
  - `backoff-policy: fixed` with a short `delay` for a cache-like service that recovers at once.
  - `multiplier` and `max-delay` for a payment service that needs fast first retries and a short cap.
  - `failure-rate-threshold: 10` for a payment client that must open early.
  - `sliding-window-size` and `minimum-number-of-calls` of 10 for a low-traffic client, which at 100 might never open.
  - `wait-duration-in-open-state: 10s` for a downstream that recovers in seconds.
  - `permitted-number-of-calls-in-half-open-state: 1` for a fragile one, so one probe goes first.
  - The slow-call pair for a downstream that slows down before it fails, such as one with a filling database pool.
  - `enabled: false` for an audit sink that must never be cut off.

## 3. Is it safe and fast?

| Parameter | Effect |
| --- | --- |
| Speed | Read once, when each client's retry and breaker are built, at first use. Nothing per call. |
| Throughput | Unchanged at the defaults. Each team's values change only its own client. The total wait stays capped by `total-timeout`. |
| Log volume | Unchanged: each breaker state change is one `WARN`. A small window can mean more changes, at most one OPEN per `wait-duration-in-open-state` per client. |
| Memory and leaks | The count-based window holds one entry per attempt in a fixed ring. The cap of 10000 bounds it per client. |
| Data leaks | None. The values are numbers, durations and an enum, and validation names the key, never the value. |
| Security | No untrusted input; these are service settings. `max-retries` stays capped at 9 and half-open calls at 1000, so neither can flood a downstream. |
| Maintainability | Two records merged like `ClientSettings`, on stable Resilience4j APIs, with Resilience4j's names. |
| Scalability | Per-client values let one noisy client open early without making the others stricter. |

- **Hostile inputs:** each startup failure above is a `@FieldSource` row in `HttpClientPropertiesTest`, under
  `defaults` and under a client: each range's two edges (such as `failure-rate-threshold: 0` and `101`,
  `multiplier: 0.5` and `11`), `delay: 0s`, `max-delay` below `delay`, the slow-call rate alone, and
  `backoff-policy: linear`. **[r4, F2]** One more row proves that `defaults.retry.max-delay: 1s` with a `fixed` client
  whose `delay` is `2s` starts. **[r3, F1]** One more row proves that `backoff-policy: fixed` under `defaults`, with
  nothing else set, starts, and that the merged `multiplier` is ignored.
- **`minimum-number-of-calls` above `sliding-window-size`:** allowed. Resilience4j 2.4.0 caps it at the window size
  for a count-based window (`CircuitBreakerMetrics.java:50-51`, checked by the round 2 judge), so the docs say so.
- There is no thread hop, no parsing beyond Spring's binding, no regex and no matching rule.

## 4. Which rules and owner decisions does it follow?

- **Rules:**
  - ST-ENG-34: no Boot key exists, so the keys live under `stratum.*`, and every one can also be set in Java.
  - ST-ENG-05: two records and an enum, as `auth`; no startup scan.
  - ST-ENG-51: what is retried is unchanged.
  - ST-ENG-52: the window and the retries are bounded.
  - ST-ENG-56: the three new types are API and go on the ArchTest list.
  - ST-ENG-57.
  - ST-DOC-05: YAML and properties for each example, plus Java.
  - ST-AG-10: the docs split, below.
  - ST-TEST-01. Planted faults:
    - a key not passed to the builder (one row per key);
    - the client's value losing to `defaults`;
    - a client that sets one key losing the other keys of `defaults`;
    - `fixed` built as exponential;
    - `max-delay` unset no longer giving `max(delay, 2s)`;
    - the slow-call pair passed when the duration is unset;
    - a customizer that no longer wins;
    - a range check dropped;
    - `enabled: false` ignored.
- **Owner decisions:**
  - Aashish, 19:06Z and 19:07Z: per client and as defaults, in YAML, properties and Java. Protected by:
    - `ClientSettingsTest.keysFallBackOneByOne`: a client's `failure-rate-threshold` and `delay` apply along with
      the `defaults`' `wait-duration-in-open-state` and `max-retries`;
    - `ClientResilienceTest.javaSettingsApply`: an `HttpClientPropertiesCustomizer` value is used;
    - `CircuitBreakerIntegrationTest.opensAtTheClientsThreshold`: a client with `failure-rate-threshold: 50`,
      `sliding-window-size: 2` and `minimum-number-of-calls: 2` opens after 2 failing attempts through a real
      downstream, while a second client on the defaults stays closed;
    - `RetryIntegrationTest.fixedPolicyRetries`: a `fixed` client with `max-retries: 2` sends 3 attempts.
  - Aashish, 19:21Z: defaults that apply with nothing set and that a team can override. Protected by
    `defaultsAreTodays` and the integration test's second client.
  - Aashish, 19:38Z: the two groups and their key names. Protected by the binding tests above.
  - ADR 0011 5.5: one breaker per client, and what counts as a failure. Both are unchanged.
- **Library facts:**
  - Resilience4j `CircuitBreakerConfig` defaults: failure rate 50, a count-based window of 100, minimum calls 100,
    open wait 60s, 10 half-open calls, slow calls at 60s and 100%. The module page states the first five at line
    253; `defaultsAreTodays` proves them all.
  - Today's back-off is `IntervalFunction.ofExponentialRandomBackoff(delay, 2, 0.5, max(delay, 2s))`
    (`RetryRules.backOff`, read on main).
  - Not verified yet: how Spring binds a bad enum value and a scalar under a group. The code PR tests both.
  - Resilience4j 2.4.0 does not check the multiplier (`IntervalFunction.java:146-158`), so the 1.0 to 10.0 range is
    Stratum's own bound (ST-ENG-52).
- **Documents:**
  - `docs/modules/http-client.md` is at 486 lines **[r3, F5]**. Its section "Retries and the circuit breaker" moves to a new page,
    `docs/modules/http-client-resilience.md`, with both tables, the three examples, the "what counts" note and the
    migration table. The module page keeps a link and the new rows in its key table, and its examples use the new keys.
  - `docs/configuration/reference.md` and `docs/guides/error-codes.md`: the old keys become the new ones.
  - `docs/getting-started/quick-start.md` line 318 links the new page.
  - **[r3, F2]** ADR 0012 is accepted and is not edited. The two groups and the three replaced keys are recorded in a
    new record: lane C's ADR 0013 if it is still open when this code PR is written, otherwise this item's own new ADR,
    with its row in `docs/README.md`. ADR 0011 is left unedited, as agreed for this starter.
  - **[r3, F3]** `docs/README.md` gets a row for the new page `docs/modules/http-client-resilience.md`, next to the
    module, auth and errors pages (`docs/README.md:69`).
  - `docs/guides/migrating-a-service.md` gets the migration table, and a line mapping a moved client's
    `resilience4j.circuitbreaker.instances.<name>.*` and `resilience4j.retry.instances.<name>.*` keys to these.
