# Judge verdict: design note of B1 (resilience of the blocking HTTP client), Feature track, review steps 1, 2, 3 and 5, round 1

Note: `design/http-client-libraries/b1-resilience-design-note.md`, the revision marked "revised 13:40Z" with the
`ClientExchangeDecorator` and streamed-download changes (185 lines, read 2026-11-09 13:36Z). Sources: ADR 0011 at
`origin/main` aabdae8, `gap-review-merged.md` "Decisions", the H1 code merged in PR #242, `spike-result.md`, the
owner's message of 13:26Z (parallel lanes), and the sources of Spring Web 7.0.10, Apache HttpClient 5.6.5 and
Resilience4j 2.4.0 from Maven Central. Fresh judge, no part in building the note, no owner decision reopened.
Model: Claude Fable 5.1.

**FAIL.** Two should-fix findings and two minor ones, all text fixes to the note. The two scope calls, the decorator
approach and every library fact the note states hold.

I read AGENTS.md and all six topic files. Text-only item: step 4 (build) skipped. Nothing changed in the repo;
nothing posted on GitHub; not merged.

## Gap list

| Requirement | Result | Evidence |
|---|---|---|
| Gate: four answers, each at most 3 lines, note complete | MET | note:24-43; answers are 4, 3, 3 and 3 lines (the first runs one line over, text only) |
| Scope call 1: `SX-HTTPC-0003` and `DownstreamException` land in step 2 (errors) | MET | note:17-19; ADR 6.1 lists the codes under Errors; until then `CallNotPermittedException`, and the page says so (note:178-179, ST-DOC-08) |
| Scope call 2: refused-call counting, retry and breaker meters land in step 3 with the pool meters | MET | note:18-19; ADR 4.5 "refused calls are counted" and 8.3 pool meters are both metrics work; one Micrometer wiring (ST-ENG-05) |
| Interceptor order: auth and team interceptors run inside the retry on every attempt | MET, by construction | The retry now wraps the whole exchange as an `HttpExchangeAdapterDecorator` (note:31-33), so every `RestClient` interceptor and initializer runs per attempt. Spring 7.0.10 has `Groups.forEachProxyFactory` (`HttpServiceGroupConfigurer.java:85`) and `HttpServiceProxyFactory.Builder.exchangeAdapterDecorator`, which composes with `andThen` (`HttpServiceProxyFactory.java:178-179`, applied at :244). No request interceptor is added, so the Spring iterator question is moot |
| Why not an interceptor: Spring buffers the body | MET | `InterceptingClientHttpRequest extends AbstractBufferingClientHttpRequest` (`InterceptingClientHttpRequest.java:36`); A14 "a streamed upload is never retried" (note:89-90) |
| Keys and defaults (ADR 2.4): `retries` 3 (max 9), `retry-delay` 200ms, `total-timeout` 8s (B2), `circuit-breaker` true | MET | note:76-81 |
| Retry list (ADR 4.2, A7): connect, drop before answer, read timeout, 502/503/504/429; never other 4xx, 500, 501; POST/PATCH only when nothing was sent | MET | note:91-94; M2 on HEAD/OPTIONS |
| `Retry-After` within the time left, no retry while open, streamed upload never retried (ADR 4.3) | MET | note:89-97 |
| Per-attempt limit = min(`timeout`, time left), no attempt under ~100ms left (ADR 4.4, A8) | PARTLY | note:98-100; F1: the back-off wait itself is not bounded by the deadline |
| Breaker rules (ADR 4.5, A6): one per client, counts timeouts/I/O/5xx/429, never other 4xx, state change logged once | MET | note:105-108; Resilience4j defaults as stated (`CircuitBreakerConfig.java:43-48`) |
| Java: custom back-off, retry and breaker rules on `StratumClientCustomizer` (ADR 1.4) | MET | note:109-112; default methods, `customize` stays the functional method |
| Resilience4j only, Apache retry off (ADR 4.1, A1) | MET | H1 already calls `disableAutomaticRetries()` (`ClientConnectionPools.java:72`); note adds no second layer |
| Q2: per-attempt timeouts reach Apache through `setHttpContextFactory` + `ScopedValue` | MET | `HttpComponentsClientHttpRequestFactory.createRequest` keeps a custom `RequestConfig` found in the context (`:199-210, :219-224`); HC5 takes `connectTimeout` from the context config when set, else from `ConnectionConfig` (`InternalExecRuntime.java:160-164`, `PoolingHttpClientConnectionManager.java:542`); `connectionRequestTimeout` and `responseTimeout` are in `RequestConfig` too (`InternalExecRuntime.java:226-228`) |
| Q2: Resilience4j 2.4.0 latest, Maven Central, Apache 2.0, version in root POM and `stratum-bom` | MET | `maven-metadata.xml` release 2.4.0 (checked 13:35Z); ST-ENG-60 puts libraries Spring Boot does not manage in the BOM |
| Q3: eight parameters, hostile `Retry-After`, no thread hop, no `ThreadLocal` | MET | note:35-38, 114-123; `RetryImpl` sleeps on the caller's thread (`RetryImpl.java:49, 259-268`) |
| Q4: each owner decision with a test | MET | note:131-158, 26 rows |
| Documents (ST-DOC-01, -05, -08) | PARTLY | note:176-180; F2: three other pages still say "planned" |
| Size (ST-AG-10), secrets (ST-SEC-01) | MET | 185 of 500 lines; no values |

## Findings

**F1. Should fix (ADR 4.4, note:26-27 "every call ends within 8s", ADR 2.4 rationale "below the web starter's 10s").**
The note bounds the `Retry-After` wait by the time left (note:95-96) but not the back-off wait. Resilience4j decides
"retry?" first, then sleeps the interval, then runs the next attempt (`RetryImpl.java:259-268`), so the "no attempt
under 100ms left" check (note:99-100) runs before a sleep of up to 2s. Real case with the defaults: a GET whose 503
arrives at 4.9s, retried, 503 again at 7.8s; wait ≈0.6s (0.3 to 0.9 with jitter); the call returns at ≈8.4s. With
`retry-delay: 1s` (the note's own real case) the overrun reaches 2s, and 8s + 2s is exactly the web starter's 10s
answer timeout that the 8s default was chosen to stay under. **Fix:** one line under "Time": the wait is capped at the
time left less 100ms, or there is no retry when the wait is longer (the same rule as `Retry-After`), with a row
`RetryRulesTest.waitNeverPassesTheDeadline`.

**F2. Should fix (ST-DOC-01, ST-DOC-08; documentation's "every other page that makes the same promise is found by
search").** Three pages describe retries as planned and are not in "Documents" (note:176-180):
`docs/configuration/reference.md:175` and `:200` ("auth, retries planned"), `docs/getting-started/installation.md:100`
("its retries and circuit breaker are planned"), and `docs/getting-started/quick-start.md:318`, which sends the reader to
the resilience starter to add retries to the inventory call, which ADR 4.1 rules out. **Fix:** add the three pages to
"Documents" (`grep -rn "retries" docs/ README.md` finds them).

**Minor**
- M1. note:101-104 says `total-timeout` "covers the call only until the headers" of a streamed download, but the
  answer timeout "stays `timeout`" and "is not shortened". HC5 applies `responseTimeout` as the socket timeout for
  headers and body alike (`InternalExecRuntime.java:226-228`), so the two cannot be split; a retried streamed download
  whose second attempt starts at 5.3s may get its headers at up to 10.3s. Fix: say that for a streamed download no new
  attempt starts after the deadline and each attempt keeps the full `timeout`, so the headers may arrive up to
  `timeout` after it.
- M2. note:91 retries a read timeout for "every method but POST and PATCH", so HEAD and OPTIONS too; ADR 4.2 names
  "GET, PUT and DELETE only". Harmless, as both are safe methods. Fix: say the note widens ADR 4.2 to HEAD and OPTIONS
  on purpose, or match it.

## Planted faults

Not applicable to a text-only note. Checked instead by reading the Spring 7.0.10, HttpClient 5.6.5 and Resilience4j
2.4.0 sources named in the gap list, the H1 code on main and the docs by search.

## Notes (not findings)

- `RequestConfig.Builder.setConnectTimeout` is deprecated in HC5 5.6.5 (`RequestConfig.java:520`) but still wins over
  `ConnectionConfig` when set; the build has no `-Werror`, so a scoped `@SuppressWarnings("deprecation")` with a
  comment is enough.
- `RetryConfig.Builder<ClientAttempt>` (note:109) only gives a team's `retryOnResult` anything to see if the
  decorated supplier returns a `ClientAttempt`; the record then has to carry the exchange's result as well as the
  failure (note:51-52 lists no result). The code judge checks this.
- A full pool (`ConnectionRequestTimeoutException`) is an I/O failure and so counts toward the breaker. ADR 4.5
  allows it ("timeouts"); it sheds load when our own pool is exhausted. Worth one line on the page.
- Resilience4j's breaker judges only after 100 calls in the window (`DEFAULT_MINIMUM_NUMBER_OF_CALLS`), so a
  low-traffic client's breaker rarely opens. No owner decision fixes the window; Java can change it.
- Until B2, an open breaker's `CallNotPermittedException` reaches the web starter's global handler as a 500 with the
  standard body. The note says the page states this; the page should also name the 503 that B2 brings.
- `ClientResilience.call(client, attempt)` (note:56-59) has its use in lane C's C2, not in B1. Acceptable as the one
  composition point the coordinator asked for; the code judge checks that nothing in it is unused at merge.

## Not verified

Resilience4j 2.4.0 on JDK 25 and Boot 4.1.2, and `Thread.sleep` pinning, as the note's own "Not verified" lists. The
spike's runtime results were taken from `spike-result.md`, not re-run. Owner decisions were checked against
`gap-review-merged.md` and the ADR, not the chat. Not merged; nothing posted on GitHub.
