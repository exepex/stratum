# Design note: C2b, `stratumHttpClients.resilience(name)` on the blocking HTTP client starter (Feature track), round 2

**Item:** ADR 0011 section 9.3, second half: "wrapping a call in `stratumHttpClients.resilience("<name>")` gives it
the client's retry and circuit breaker". Lane C, owner's message 2026-11-09 13:26Z; owner decision 13:38Z (option A):
`restClient(name)` never retries by itself, the team wraps the call. Base: `main` at da23adc (B1, PR #256, merged).
Track: Feature (new public API).

## 1. Who uses it, and does anything change for them?
Teams whose client Stratum did not build: a generated client that takes `stratumHttpClients.restClient("partner")`,
or a vendor SDK on its own engine. New, existing or hybrid services. Nothing changes for anyone who does not call
`resilience(name)`: `restClient(name)` still sends once (#252 tests unchanged). Lines:

```java
Order order = stratumHttpClients.resilience("partner").call(HttpMethod.GET, () -> partnerOrders.getOrder(id));
```

API: `StratumHttpClients.resilience(String)` returning `ResilientCalls` (new public interface, three methods below).
Internal: `DefaultStratumHttpClients` implements it on B1's `ClientResilience.call(...)`.

## 2. How is it built, and why this way?
B1's `ClientResilience.call(client, method, streamedBody, streamedDownload, exchange)` already runs any call under
the client's retry rules, breaker and `total-timeout` (with `AttemptTimeLeft` bound, so the pool wait, connect and
answer timeouts of a call through `restClient(name)`, which shares the client's pool, end by the deadline). C2b adds
only the public door to it. `ResilientCalls` has three methods, because the retry rules need two facts the team's
lambda does not show:

| Method | Passes to `ClientResilience.call` | For |
|---|---|---|
| `call(HttpMethod, Supplier<T>)` | `streamedBody=false`, `streamedDownload=false` | an ordinary call |
| `upload(HttpMethod, Supplier<T>)` | `streamedBody=true` | a call whose body is a stream: never sent twice (ST-ENG-51) |
| `download(HttpMethod, Supplier<T>)` | `streamedDownload=true` | a call that returns an `InputStream`: keeps its gap-between-bytes timeout (B1) |

The method is required, as B1's rules retry a `POST` only when it was never sent (ST-ENG-51); a Stratum default
cannot guess it from a lambda. Each attempt re-runs the team's whole lambda, so the request is built again and lane A's
initializer puts a fresh token on it; nothing is buffered (no interceptor, C1's upload test stays green).

Rejected: (a) one `call(Supplier)` assuming `GET`: would retry a team's `POST` after it was sent. (b) a builder
(`resilience(name).method(POST).streamedBody().call(...)`): three facts do not need a builder; three verbs read
plainer. (c) wrapping inside the `RestClient` with an interceptor: buffers bodies (C1 probe; owner 13:38Z).

Package: `ResilientCalls` sits in `com.exepex.stratum.restclient`, next to `StratumHttpClients` and
`ClientResilience`. It imports only `org.springframework.http.HttpMethod` and `java.util.function`, so no new package
edge and no cycle.

**Lane A's 401 resend** (`ServiceTokenResend`, A1 note F5) is not in `main`. ADR 0012 decision 3 says
`resilience(name)` gives retry, breaker and the new token after a 401. Whichever of C2b and A1b lands second wires
`ServiceTokenResend.call` inside `ClientResilience.call`, within the breaker and per attempt, as A1 note F5 commits to
(test `ResendOn401.plainCallThroughResilience`). Until then C2b's docs say "retry and circuit breaker" and list the
401 resend under Planned. Cross-lane note sent to lane A.

**Errors: Spring's stay, only the open breaker maps (lane B's B2).** As `restClient(name)` does, a call through
`resilience(name)` keeps Spring's exceptions: a failed call throws Spring's `RestClientException`, unchanged. Only a
call refused by an open breaker changes: today it throws Resilience4j's `CallNotPermittedException` (a 500 if
uncaught); B2 maps that one to `SX-HTTPC-0003` (503) inside `ClientResilience.call`, the path `resilience(name)` and the
interfaces share, so `resilience(name)` gets it with no code of its own. C2b adds no mapping; its breaker test asserts
the exception that is in `main` when it is written, and its docs say so. Agreed with lane B (17:52Z): B2's
`ClientResilience.rethrow` throws `SX-HTTPC-0003` with the cause kept; if C2b merges first, B2 adds
`OwnClientsIntegrationTest.Resilience.openBreakerIs503` and changes C2b's assertion; if B2 merges first, C2b's
breaker row asserts `SX-HTTPC-0003`.

## 3. Is it safe and fast?
- Speed: one map lookup for the cached `ResilientCalls`, then B1's existing path; no extra copy of a body.
- Throughput: no thread hop and no lock (B1 runs on the caller's thread); the pool is the client's own.
- Log volume: no logging added.
- Memory and leaks: one `ResilientCalls` cached per configured name, like `restClient`; B1's guards are reused, so a
  generated client and the client's interfaces share one breaker (one downstream, one breaker). No new state.
- Data leaks and security: nothing new is read or logged.
- Maintainability: three one-line methods over `ClientResilience.call`; the retry rules stay in B1's one place.
- Scalability: per-name state only, bounded by the configured clients.

## 4. Which rules and owner decisions does it follow?
ADR 0011 9.3; ADR 0012 decision 3 (retry, breaker and the 401 resend); owner 13:38Z; ST-ENG-51 (retry rules, B1's), ST-ENG-56 (`ResilientCalls` on the API list,
`ArchTest`), ST-ENG-42/46/47, ST-DOC-05 (no new key). Docs: module page "Clients Stratum did not build" (replace
"planned `resilience`"), Planned list.

## Decisions and their tests
| Decision | Test (`OwnClientsIntegrationTest`, nested `Resilience`) | Planted fault |
|---|---|---|
| A `GET` through `restClient` answered 503 then 200 is retried and returns the 200 | `ScriptedDownstream` 503 then 200 | `call` runs the lambda once |
| A `POST` answered 503 is not retried | path count 1 | method ignored (always `GET`) |
| `upload` is never sent twice | 503 on a `PUT` upload, count 1 | `upload` passes `streamedBody=false` |
| The client's breaker opens and `resilience(name)` refuses with `CallNotPermittedException` | breaker settings from B1's tests | breaker not used |
| The same breaker as the client's interface | open via the interface, refused via `resilience` | a breaker per caller |
| `download` keeps the gap timeout | C1's slow answer longer than `total-timeout`'s attempt share, read in full | `streamedDownload=false` |
| Unknown name without `url` | `SX-HTTPC-1004`, as `restClient` | - |

## Not verified
`ClientResilience.call` with a client that has only `restClient` use and no interface (B1's tests use interfaces);
first test before code.

## Round 1 findings (reviews/judge/C2b-resilient-calls-note.md)
F2 fixed: ADR 0012 decision 3 cited and the 401 resend wiring stated (section 2); package and no cycle (section 2);
all eight ST-AG-14 parameters (section 3); one `ResilientCalls` cached per name. F1 (who `resilience(name)` serves: a
vendor SDK on its own engine gets no retry or breaker today) waits for the owner's answer; sections 1 and the tests
change once he picks.
