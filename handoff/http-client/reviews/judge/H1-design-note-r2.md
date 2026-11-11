# Judge verdict: design note of H1 (blocking HTTP client starter on ADR 0011), Feature track, review steps 1, 2, 3 and 5, round 2

Note: `design/http-client-libraries/h1-design-note.md` (165 lines). Sources: ADR 0011 at `origin/main` b509a9f,
`gap-review-merged.md` "Decisions", `spike-result.md`, the round 1 verdict `H1-design-note.md`, the repo at b509a9f,
and the Spring Boot 4.1.2 and Apache HttpClient 5.6.5 sources from Maven Central. Fresh judge, no part in building
the note, no owner decision reopened. Model: Claude Opus 5.5 (round 1 ran on Fable 5.1).

**FAIL.** All four round 1 findings are fixed. One new should-fix finding: adding `httpclient5` changes the engine
of a team's own `RestClient`, which the note says is "never touched". One minor finding.

I read AGENTS.md and all six topic files. Text-only item: step 4 (build) skipped; one throwaway probe was run to
back F1 and then left outside the repo. Nothing changed in the repo; nothing posted on GitHub.

## Round 1 fixes

| Finding | Result | Evidence |
|---|---|---|
| F1 API list | FIXED | note:61-64 lists `@StratumClient`, `HttpClientPropertiesCustomizer`, `HttpClientProperties`, `ClientSettings` and builders, `StratumClientCustomizer`; every other public type `@StratumInternal`. Matches engineering.md:300-301 and the web module's pattern |
| F2 decision → test | FIXED | note:114-136, 20 rows; every item round 1 listed (Boot-key stop, `Mono`/`Flux` stop, `max-wait`, `idle-timeout`, stale check, lifetime, Keep-Alive, redirects, no network, `shared-pool`, Java wins) has a named test |
| M1 codes | FIXED | note:91-97 `SX-HTTPC-1001`/`-1002`; `error-codes.md` on main has no `SX-HTTPC` code yet (`grep -c` = 0), so no clash |
| M2 thread hop | FIXED | note:36-37 |
| Notes | FIXED | commit-count command given (count taken from round 1, not re-run: 0978a0a is not fetchable here); gzip and ADR 9's sentence in the documents list (note:146-154) |

## Gap list (rest of the note)

| Requirement | Result | Evidence |
|---|---|---|
| Gate: note complete, four answers, each at most 3 lines | MET | note:24-42 |
| Scope (blocking only, no Boot global timeouts, fresh branch) | MET | accepted in round 1; unchanged |
| Keys and defaults (ADR 2.4) | MET | note:74-83 against ADR 2.4 |
| Q1: each change a consumer sees, or "none" with a test | NOT MET | F1 |
| Q2, Q3 | MET | note:29-37; spike facts match `spike-result.md` |
| Q4: owner decisions with tests | MET | table; small gaps in M1 |
| Documents (ST-DOC-01, -05, -08) | MET | note:146-154 |
| Size (ST-AG-10), secrets (ST-SEC-01) | MET | 165 of 500 lines; no values |

## Findings

**F1. Should fix (design-note question 1, ADR 9, owner 2026-11-08 "never conflict" with foreign clients).**
note:26 says "a company's own `RestClient` beans are never touched", and note:136 plans a test for it. But note:56
adds `httpclient5` to the starter, and the blocking bundle lists the starter (`stratum-starter-microservice/pom.xml:29`).
Boot picks Apache HC5 first whenever it is on the classpath (`ClientHttpRequestFactoryBuilder.detect`, line 210 in
4.1.2). So a team's own `RestClient.Builder` moves from the JDK client to HC5 on Boot's defaults, which bring:
- a silent retry: one GET answering 503 reaches the server twice (HC5 `DefaultHttpRequestRetryStrategy(1, 1s)`);
- 5 connections per host, 25 in total (`PoolingHttpClientConnectionManager:119-120`; Boot sets no limit);
- a 3-minute wait for a free connection (`RequestConfig:48`; Boot sets none).

Probe (Boot 4.1.2, `spring-boot-starter-webmvc` + `-restclient`, the injected `RestClient.Builder`, one GET to a
local server answering 503): without `httpclient5`: `JdkClientHttpRequestFactory`, 1 hit. With it:
`HttpComponentsClientHttpRequestFactory`, 2 hits. A generated client on Boot's builder in a busy service then queues
behind 5 connections per host. `plainBuilderUnchanged` would pass if it checks only for Stratum timeouts.
**Fix:** state this change in question 1 and pick one way; the choice is the owner's, since it touches his
foreign-client rule:
- **A (recommended):** keep the dependency and say so in the README and module page: Boot then builds your own
  `RestClient` on HC5 (5 per host, one retry on 503 and 429); set `spring.http.clients.imperative.factory=jdk` to keep
  the JDK client. A test pins the fact, so the page fails when Boot changes. Simplest, no key set by Stratum.
- **B:** Stratum sets `spring.http.clients.imperative.factory=jdk` as a `StratumDefaults` default (a team's value
  wins), so foreign clients keep the JDK client. Stops the silent change, but a team that already chose HC5 by
  adding it itself is switched to JDK unless it sets the key, and it is a global Boot key, which scope call 2 avoided.

**Minor**
- M1. Three tests are promised but not named in the table: two interfaces with one name sharing a group
  (note:70, 165), the gzip answer (note:154), and a bad client name (note:71), whose result (which startup stop and
  code) is not said. Fix: three table rows, and the code for a bad name (e.g. `SX-CORE-1003` or `SX-HTTPC-1003`).

## Planted faults

Not applicable to a text-only note. Checked instead by reading the Boot 4.1.2 and HC5 5.6.5 sources and by the
probe above.

## Notes (not findings)

- `StratumClientCustomizer` takes a `RestClient.Builder` but sits in the shared lib (note:51). The reactive starter
  will need its own `WebClient` form; that belongs to that item's note.
- ADR 5.4 "values are always encoded" and 5.5 "readiness never depends on a downstream" rest on Spring's encoding
  and on H1 adding no health indicator; neither has its own test row. Fine for H1, worth one line in the Claims.

## Not verified

Library behaviour other than F1 was taken from `spike-result.md`. Owner decisions were checked against
`gap-review-merged.md` and the ADR, not the chat. The probe ran on JDK 21. Not merged; nothing posted on GitHub.
