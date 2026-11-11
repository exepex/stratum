# Design note: B1, resilience of the blocking HTTP client (Feature track), round 2, revised after the round-2 verdict

**Item:** "resilience" of ADR 0011's follow-up items: `retries`, `retry-delay`, `total-timeout` and the breaker
(ADR 2.4, 4.1 to 4.5). Lane B, step 1 of 3 (owner, 2026-11-09 13:26Z: parallel lanes, autonomous work under the
agreed process). Built on a fresh branch from `main` at `aabdae8` (H1, PR #242, merged).

**In B1:**
- four keys under `defaults` and `clients.<name>`: `retries` 3 (0 to 9), `retry-delay` 200ms, `total-timeout` 8s,
  `circuit-breaker` true;
- one Resilience4j `Retry` and one `CircuitBreaker` per client, run around each call of the client's HTTP interface
  proxy (an `HttpExchangeAdapterDecorator`), never as a request interceptor;
- the fixed retry and breaker rules of ADR 4.2 to 4.5, with `Retry-After` and the per-attempt time limit;
- Java: two default methods on `StratumClientCustomizer` that change one client's Resilience4j retry or breaker
  rules (ADR 1.4);
- the module page, the IDE metadata and the `HttpClientPropertiesCustomizer` example.

**Later items, not B1:** the codes `SX-HTTPC-0001` to `0004` and `DownstreamException` (B2: until then an open
breaker throws Resilience4j's `CallNotPermittedException`, and the page says so); counting refused calls, the retry
and breaker meters and the pool meters (B3, which adds the optional Micrometer wiring once for all of them);
streamed uploads themselves (lane C; B1 already never retries one); auth (lane A, whose interceptors run inside
the retry, so on every attempt); `stratumHttpClients.restClient("<name>")` for generated clients (lane C, ADR 9.2),
which has no proxy, gets the same `Retry` and breaker in lane C's C2; the reactive starter.

## 1. Who uses it, and does anything change for them?
Teams with a `@StratumClient` (new, hybrid, parent or company framework). They see: idempotent calls retried up to 3
times, a POST only after a connect failure, every call ending within 8s; no line to write (`retries: 0`,
`circuit-breaker: false` per client). API: `ClientAttempt`, two `StratumClientCustomizer` methods; rest internal.

## 2. How is it built, and why this way?
Resilience4j 2.4.0 `Retry` around `CircuitBreaker` around one whole exchange, as an `HttpExchangeAdapterDecorator`
set per group through `forEachProxyFactory`, so interceptors run on every attempt. Rejected: a request interceptor,
which makes Spring buffer every request body (lane C, checked). Limit per attempt: `setHttpContextFactory` + `ScopedValue`.

## 3. Is it safe and fast?
ST-AG-14 below. Hostile input: a downstream `Retry-After` (huge, negative, garbage, date in the past). No thread hop:
attempts and back-off waits run on the caller's thread (`Thread.sleep` inside Resilience4j), so logging and tracing
context stay; a `ScopedValue`, not a `ThreadLocal`, carries the time left, so nothing grows with virtual threads.

## 4. Which rules and owner decisions does it follow?
ADR 0011 1.3, 1.4, 2.4, 4.1 to 4.5; ST-ENG-05, -07, -24, -33, -34, -50, -51, -53, -54, -56, -62; ST-SEC-04;
ST-DOC-01, -04, -05, -08; ST-TEST-01, -14, -34. Each owner decision has a test in "Decisions and their tests".
Facts and their sources are under "Facts".

## Details

### Modules and packages
- `libs/stratum-http-client` (`com.exepex.stratum.httpclient`): the four keys on `ClientSettings`, their constants
  and validation. No Resilience4j type here.
- `stratum-starter-http-client` (`com.exepex.stratum.restclient`):
  - `ClientAttempt` (API record): the method, the failure if any (Spring's `RestClientResponseException` for an
    error answer, `ResourceAccessException` for an I/O failure), the exchange's result when it succeeded, whether
    the body is streamed, the time left and the next wait;
  - `RetryRules` (`@StratumInternal`, `@UtilityClass`): ADR 4.2 and 4.3 as two pure functions, "retry this
    attempt?" and "wait how long?", with the `Retry-After` reader;
  - `ClientResilience` (`@StratumInternal`): builds each client's `Retry` and `CircuitBreaker` from its settings and
    the customizers, and logs each breaker state change once (WARN). It is not tied to the proxy: it hands out
    one client's `Retry` and `CircuitBreaker` and runs any call under them (`call(client, attempt)`), and the
    retry and breaker `ClientExchangeDecorator` beans are thin users of it, so lane C's C2
    (`stratumHttpClients.resilience(name).call(...)`) reuses the same objects and rules;
- `ClientExchangeDecorator` (`@StratumInternal` interface, one method `decorate(String client,
    HttpExchangeAdapter)` plus `Ordered`): the one place where Stratum's decorators meet (coordinator, 13:34Z).
    Each lane adds a bean of it; `StratumClientGroups` composes every one, by order, for each client. Fixed orders
    in `HttpClientConstants`, outermost first: retry, breaker, lane A's 401 resend (lane C's mocks use a request factory, not a decorator). B1 ships the
    first two; no lane calls `exchangeAdapterDecorator` itself;
  - `StreamedBody` (`@StratumInternal`, `@UtilityClass`): the one check "is this request's body streamed"
    (`StreamingHttpOutputMessage.Body`, any `Resource`), which lanes A and C reuse;
  - `AttemptTimeLeft` (`@StratumInternal`, `@UtilityClass`): holds the `ScopedValue` of the current attempt's
    time left, which the request factory's context factory reads, and one accessor `timeLeft()` returning
    `Optional<Duration>` (empty outside a Stratum call), which lane C's C3 reads to cap a mocked delay at
    `total-timeout` (coordinator, 13:45Z);
- `StratumClientGroups` sets the composed decorator with `forEachProxyFactory` on Stratum groups only, chosen by the
  same predicate as the rest of the group set-up. Lane C (C2a) widens that one predicate to groups built from
  `clients.<name>.interfaces`; B1 uses whatever predicate is on `main` and adds none of its own, so such a group gets
  retries too once C2a lands; `ClientConnectionPools`
  gives each request factory the context factory. Stratum adds no `ClientHttpRequestInterceptor` (lanes A and C:
  header-only work uses `ClientHttpRequestInitializer`, which does not buffer). No new package, so no new cycle; the starter already depends on the library.
- New dependencies: `io.github.resilience4j:resilience4j-retry` and `resilience4j-circuitbreaker` 2.4.0 (Maven
  Central, Apache 2.0), version in the root `pom.xml`, managed in `stratum-bom` (ST-ENG-60; Spring Boot does not
  manage Resilience4j). Reason (ST-ENG-62): the owner chose it as the one retry layer, and the foreign-client step
  (ADR 9.3) hands the same objects to teams.

### Keys and real cases
| Key | Default | A real case that changes it |
|---|---|---|
| `retries` | 3 (0 to 9) | 0 for a downstream that retries itself; 5 for a flaky partner API |
| `retry-delay` | 200ms | 1s for a downstream that recovers slowly |
| `total-timeout` | 8s | 25s for a report client with `timeout: 20s` |
| `circuit-breaker` | true | false for a client whose every call must be tried, such as an audit sink |

No Spring Boot key exists for these (ST-ENG-34's exception, ADR 2.1). All four work in `defaults`, per client and in
`HttpClientPropertiesCustomizer`, with IDE metadata from the record's Javadoc.

### The fixed rules (ADR 4.2 to 4.5)
- **Attempts:** at most `retries + 1`. **Wait:** `retry-delay`, doubled each time, with ±50% jitter, at most 2s
  (Resilience4j `IntervalFunction.ofExponentialRandomBackoff`).
- **A streamed upload** (`StreamingHttpOutputMessage.Body`, `InputStreamResource` or another `Resource` as the
  body) is sent once, never retried (ADR 4.3, A14).
- **Retried for GET, HEAD, OPTIONS, PUT and DELETE only** (an allow list; ADR 4.2 names GET, PUT and DELETE, and
  HEAD and OPTIONS are added on purpose, as safe methods under RFC 9110 with no body). **Every other method**, POST,
  PATCH and any custom one, is treated like POST (round 2, M1). **Idempotent methods** are retried after a connect
  failure, a dropped connection before any answer, a read timeout, and on 502, 503, 504 and 429. **POST, PATCH
  and any other method:** a connect failure only (connection refused, connect
  timeout, unknown host), where nothing was written. **Never:** other 4xx, 500, 501; a full pool (it is our side,
  and retrying adds load); a refused call of an open breaker.
- **`Retry-After`** on 429 or 503, seconds or an HTTP date, replaces the wait. When it is longer than the time left
  less 100ms, there is no retry and the 429 or 503 is the answer. A value that is not a number or a date, or is
  negative, is ignored; one of more than 32 characters is ignored without parsing.
- **Time:** the call's deadline is `total-timeout` from its start. Each attempt's pool wait, connect timeout and
  answer timeout are the smaller of their own setting and the time left. No new attempt starts with less than
  100ms left: the last answer or failure is returned as it is. The back-off wait is held to the same rule as
  `Retry-After`: when it is longer than the time left less 100ms, there is no retry (round 1, F1).
- **Where the stop is decided (round 2, F1):** the decorated supplier never throws: every attempt, failure
  included, ends as a `ClientAttempt` result, which carries its own next wait, computed once when the attempt ends
  (`Retry-After` if present, else the jittered back-off). Resilience4j's `retryOnResult` predicate is
  `RetryRules.shouldRetry(attempt)`: the rule allows it and `attempt.nextWait()` is at most the time left less
  100ms. The interval function only returns `attempt.nextWait()`, so it is never negative and Resilience4j never
  hits its `IllegalStateException` path. When retries stop, the decorator rethrows the last attempt's failure (for
  an error answer, Spring's own exception for it) or returns its result. Each attempt is the whole exchange, body read
  included, so `total-timeout` bounds the whole call. **A streamed download** (a return type of `InputStream` or
  `ResponseEntity<InputStream>`, lane C; a `Resource` answer is read in full, so it is not one) is the exception: no new attempt starts after the deadline, and
  each attempt keeps the full `timeout`, the longest gap between bytes (ADR 7), because Apache applies one answer
  timeout to the headers and the body alike. So a long download is never cut off at 8s, and the headers of a
  retried streamed download may arrive up to `timeout` after the deadline.
- **Breaker:** one per client, Resilience4j's defaults (50% failure rate over the last 100 calls, at least 100
  calls, 60s open, 10 trial calls). Counted as failure: an I/O failure (timeouts included), 5xx and 429. Never
  counted: other 4xx. Each attempt is one call of the breaker, so when it opens during retries the next attempt is
  refused and the retries stop. An open breaker's refusal sends nothing.
- **Java (ADR 1.4):** `StratumClientCustomizer.customizeRetry(String client, RetryConfig.Builder<ClientAttempt>)`
  and `customizeCircuitBreaker(String client, CircuitBreakerConfig.Builder)`, both default no-ops, called after
  Stratum filled the builder, in the customizers' order. The functional method `customize` stays the only abstract
  one, so existing lambdas compile.

### Safe-and-fast, by ST-AG-14
- Speed: one breaker check and one scoped value per attempt; no allocation beyond `ClientAttempt`.
- Throughput: retries are bounded by count and by `total-timeout`; an open breaker refuses at once.
- Log volume: one WARN per breaker state change; retries log nothing (the call log is B3).
- Memory and leaks: no request body is buffered by Stratum (test below); Spring closes each failed attempt's answer,
  so its connection returns to the pool (test below); no `ThreadLocal`.
- Data leaks: nothing new is logged but the client name and the breaker state.
- Security: a POST is never sent twice once written (ST-ENG-51); `Retry-After` is bounded by the time left.
- Maintainability: Resilience4j's own `Retry` and `CircuitBreaker`, configured, not re-written.
- Scalability: jitter spreads retries of many pods; the breaker stops a retry storm.

### Decisions and their tests
The streaming tests use lane C's `StreamingDownstream` once C1 is on `main`; if B1 merges first, they use a
`StreamingHttpOutputMessage.Body` against `ScriptedDownstream` and move to the helper in C1. `restClient(name)`
gets retry and breaker in C2. Integration tests use `ScriptedDownstream` with new scripted paths (502, 504, 429, 500, 501, 404, `Retry-After`,
a dropped connection, a slow answer), and `SilentPort` for a connect failure. Short durations keep them fast.

| Decision | Source | Test |
|---|---|---|
| `retries: 3` = at most 4 attempts on a 503 GET | ADR 4.1, A1 | `RetryIntegrationTest.getRetriedUntilAttemptsRunOut` |
| 502, 504, 429 retried; 500, 501, 400, 404 not | ADR 4.2, A7 | `RetryIntegrationTest.retriedStatuses`, `.notRetriedStatuses` |
| GET retried after a dropped connection and a read timeout | ADR 4.2 | `RetryIntegrationTest.getRetriedAfterDropAndTimeout` |
| POST and PATCH: retried after a connect failure only | ADR 4.2, owner 11-09 | `RetryIntegrationTest.postRetriedOnlyWhenNothingSent` |
| POST not retried on 503, after a drop or a read timeout | ADR 4.2, ST-ENG-51 | `RetryIntegrationTest.postNeverResent` |
| A full pool is not retried | this note | `RetryIntegrationTest.fullPoolNotRetried` |
| `retries: 0` sends once | ADR 2.4 | `RetryIntegrationTest.zeroRetriesSendsOnce` |
| A retried answer's connection goes back to the pool | ST-ENG-48 | `RetryIntegrationTest.retriedAnswerReleasesConnection` |
| Decorators compose in their fixed order, retry outermost | coordinator 13:34Z | `StratumClientGroupsTest.decoratorsInOrder` |
| `StreamedBody` knows each streamed body type, and a byte array is not one | ADR 4.3 | `StreamedBodyTest` |
| A streamed download is not cut off by `total-timeout`; its gap limit stays `timeout` | ADR 7, lane C | `TotalTimeoutIntegrationTest.streamedDownloadOutlivesTotalTimeout` |
| A streamed upload is sent once and Stratum adds no interceptor (no buffering) | ADR 4.3, A14, lane C | `RetryIntegrationTest.streamedUploadSentOnce`, `StratumClientGroupsTest.noRequestInterceptor` |
| Team interceptors run on every attempt | ADR 1.3, spike 6 | `RetryIntegrationTest.interceptorRunsOnEveryAttempt` |
| Back-off doubles from `retry-delay`, jitter, at most 2s | ADR 2.4 | `RetryRulesTest.waitDoublesWithinJitterUpTo2s` |
| `Retry-After` seconds and date honoured; too long = no retry | ADR 4.3 | `RetryRulesTest.retryAfter*`, `RetryIntegrationTest.retryAfterHonoured` |
| Hostile `Retry-After` ignored | question 3 | `RetryRulesTest.hostileRetryAfterIgnored` |
| Each attempt gets min(`timeout`, time left); call ends by `total-timeout` | ADR 4.4, A8 | `TotalTimeoutIntegrationTest.callEndsByTotalTimeout` |
| A 503 with `Retry-After` longer than the time left is sent once, and the caller gets the 503's own exception | ADR 4.3, round 2 F1 | `RetryIntegrationTest.retryAfterTooLongReturnsTheAnswer` |
| The back-off wait never passes the deadline; too long = no retry | ADR 4.4, round 1 F1 | `RetryRulesTest.waitNeverPassesTheDeadline` |
| No attempt starts with under 100ms left | ADR 4.4 | `RetryRulesTest.noAttemptUnder100msLeft` |
| Breaker opens on 5xx/429/I/O and then sends nothing | ADR 4.5, A6 | `CircuitBreakerIntegrationTest.opensAndRefusesWithoutSending` |
| 4xx never counted | ADR 4.5 | `CircuitBreakerIntegrationTest.clientErrorsNotCounted` |
| No retry while the breaker is open | ADR 4.3 | `CircuitBreakerIntegrationTest.openBreakerStopsRetries` |
| State change logged once | ADR 4.5 | `CircuitBreakerIntegrationTest.stateChangeLoggedOnce` |
| `circuit-breaker: false` has none | ADR 2.4 | `CircuitBreakerIntegrationTest.switchedOff` |
| One breaker per client | ADR 4.5 | `CircuitBreakerIntegrationTest.oneBreakerPerClient` |
| Java changes one client's retry and breaker rules | ADR 1.4 | `ClientCustomizerIntegrationTest.resilienceRulesForOneClient` |
| Keys: defaults, client wins, Java wins, `retries` 10 stops startup, both file formats | ADR 2.1, ST-ENG-31/32/34 | `HttpClientPropertiesTest` (new cases), `ConfigurationMetadataTest` |

The breaker tests set a small window through `customizeCircuitBreaker`, which also exercises the Java API.

### Facts
- Spring 7.0.10 `InterceptingClientHttpRequest` extends `AbstractBufferingClientHttpRequest`, so any request
  interceptor buffers the request body; `HttpExchangeAdapterDecorator` exists with `exchange*` methods to wrap
  (both `javap`, 2026-11-09).
- Spring 7.0.10 `HttpComponentsClientHttpRequestFactory.createRequest` calls the context factory for every request,
  and a `RequestConfig` set there wins over the factory's (bytecode read, `javap`, 2026-11-09). Spring's
  end-of-chain execution creates a new request per `execute`, so each attempt gets its own config (bytecode of
  `InterceptingClientHttpRequest$EndOfChainRequestExecution`; spike item 6 ran it).
- Apache's context `RequestConfig` replaces the client's default whole, so Stratum copies the client's config and
  changes three timeouts (Apache `RequestConfig.copy`; to be confirmed by `TotalTimeoutIntegrationTest`).
- Resilience4j 2.4.0 is the latest release on Maven Central (`maven-metadata.xml`, 2026-11-09); ADR 0011 records
  that it supports Boot 4. Its breaker defaults are from its `CircuitBreakerConfig` (checked in the jar when built).
- `ScopedValue` is final in JDK 25 (JEP 506).

### Documents
- `docs/modules/http-client.md`: the four keys in the table, both formats, the Java section, "What every client
  gets" (retries, total time, breaker), the planned list, and the note that an open breaker gives
  `CallNotPermittedException` until B2;
- `docs/modules/resilience.md`: its HTTP client line already points to ADR 0011; checked, changed only if wrong;
- `docs/configuration/reference.md` (lines 175 and 200, "retries planned"), `docs/getting-started/installation.md`
  (line 100) and `docs/getting-started/quick-start.md` (line 318, which sends retries to the resilience starter):
  retries and the breaker are built and come with the client starter (round 1, F2; found with
  `grep -rn "retries" docs/ README.md`);
- `README.md` line 269: retries and the breaker are built; tracing and tokens stay as they are (round 2, F2);
- the module page also says a full pool counts toward the breaker, and that an open breaker becomes `SX-HTTPC-0003`
  and 503 with B2.

## Round 1 findings (Fable, `reviews/judge/B1-resilience-note.md`)
- **F1, accepted:** the back-off wait is held to the deadline like `Retry-After`; new test row.
- **F2, accepted:** three pages added to "Documents".
- **M1, accepted:** the streamed-download line says each attempt keeps the full `timeout`, so its headers may arrive
  up to `timeout` after the deadline.
- **M2, accepted:** HEAD and OPTIONS are added on purpose, with the reason.
- **Notes, carried into the code step:** `ClientAttempt` also carries the exchange's result, so a team's
  `retryOnResult` can see it; the deprecated `RequestConfig.Builder.setConnectTimeout` gets a scoped
  `@SuppressWarnings("deprecation")` with its reason; the page names the full-pool and 503 points above; the breaker's
  100-call minimum is stated on the page with the Java way to change it; `ClientResilience.call` is used by the
  decorators in B1 itself, so nothing in it is unused at merge.

## Not verified
- Resilience4j 2.4.0 on JDK 25 and Boot 4.1.2 (the build will show it);
- whether `Thread.sleep` inside Resilience4j pins a virtual thread (JDK 24+ does not pin on `synchronized`; to be
  checked in the jar).

## Round 2 findings (Opus, `reviews/judge/B1-resilience-note-r2.md`)
- **F1, accepted:** "Where the stop is decided" names it; the wait travels on `ClientAttempt`, so the interval is
  never negative; new integration row `retryAfterTooLongReturnsTheAnswer`.
- **F2, accepted:** `README.md` line 269 added to "Documents".
- **M1, accepted:** retried methods are an allow list; any other method is treated like POST.
- **M2, accepted:** answer 1 is three lines.
