# HTTP client starter: handover

Written 2026-11-09 22:30Z, at Aashish's go (22:16Z). Everything in flight is merged; nothing runs. This file is for a
new session that finishes the blocking HTTP client starter, `stratum-starter-http-client`, with the same process
used so far: one coordinator, implementer agents in lanes, an independent judge per review step, Aashish merges.

**Where this lives:** branch `claude/project-thread-vhf1zq` of exepex/stratum, folder `handoff/http-client/`,
shown in a parked draft pull request that is never merged. Get it with
`git fetch origin claude/project-thread-vhf1zq && git checkout claude/project-thread-vhf1zq -- handoff/http-client`,
read it, and do the work on fresh branches from `main` (never commit this folder there). Every path below is in
this folder or in `main`.

Tracking issue, in pick-up order: **#270**. Every issue there carries its own goal, what is done, links, acceptance
criteria and an implementer prompt.

## 1. Paste-in prompt for the new coordinator session

> You are the coordinator for finishing Stratum's blocking HTTP client starter (exepex/stratum). Read, in full and
> in this order: `AGENTS.md`, the six files in `docs/agents/`, this file
> (`handoff/http-client/HANDOVER.md`), `handoff/http-client/process/stratum-judge-process.md`,
> `handoff/http-client/process/stratum-handoff.md` sections 2 and 2b, ADR 0011 and ADR 0012 in `docs/adr/`, and
> issue #270. Then post Aashish a short status: what is merged, the order in #270, and the one decision you need
> first (C2b round 3, section 5). Run the work as section 3 says. You never write code, tests, project documents
> or rule text yourself: each item goes to an implementer agent with the brief of `docs/agents/judge.md`
> ("The implementer brief"). You start a fresh judge for each review step, never the implementer. Aashish merges.

## 2. Where things stand (main at 8e1b7ea)

Merged: #239 ADR 0011, #242 H1 base (settings, pool, SSL), #251 A1a auth (basic, bearer, api-key, mTLS), #252 C2a
own clients (`restClient(name)`, `settings(name)`, `interfaces`), #253 C1 streaming, #255 span query mask, #256 B1
retry and breaker, #257 A1b service token and token relay, #258 B2 errors, #259 C3 mocks, #265 B2b 404 as
`Optional`.

| Issue | Item | Lane | Track | State |
|---|---|---|---|---|
| #263 | Retry and circuit-breaker keys | B | Feature | note PASS (confirm verdict), Aashish approved 21:43Z; code next |
| #261 | C2b `RestClient` + `RestTemplate` bean per client | C | Feature | note r3; r1 and r2 FAIL, all fixed; no r3 verdict file |
| #264 | A2 headers, copied headers, interceptors | A | Feature | note PASS r2 (base before A1b and C3); code next |
| #266 | B3 call log, trace headers, pool metrics, refused calls | B | Feature | no note |
| #267 | Test: mocked client fetches no token | C | Fix | not started |
| #260 | Jackson 2 parse error read as a cut-off body | B | Fix | not verified, probe first |
| #250 | Docs: Boot's global client timeouts do not apply | any | Docs | not started |

Planned, only when Aashish asks: #262 own-engine (vendor SDK) retry and breaker, #237 pool wait and pool-full
meters (fold into #266 if it fits), #236, #238, #243 to #249, #254, #268 body debug (blocked on the logging timed
switch), #269 reactive starter.

## 3. The process (same as today)

### Roles
- **Coordinator** (one session): puts each item on a track (ST-AG-15), writes each implementer's brief
  (ST-AG-18), starts every judge, relays Aashish's decisions to lanes, posts one status line per PR state change,
  says "Ready to merge at <sha>" only when the conditions below hold. Never builds anything itself.
- **Implementer lanes** (one agent per lane; today's lanes: A auth and headers, B resilience, errors, logs and
  metrics, C own clients and mocks). Each builds one item at a time on the branch the coordinator gives it, writes
  the design note first, reviews its own change (ST-AG-17), writes the Claims, and never judges its own work.
- **Judge**: a fresh agent per review step, never combined with any other role on that change. It reads the rules,
  checks every claim against the code, runs the build, **plants faults** in the code to prove the tests catch them
  (ST-TEST-01), and writes only its verdict. How a judge works and its ready-to-paste prompt:
  `handoff/http-client/process/stratum-judge-process.md` (sections 3 to 15). A new session has no shared folder: post each new design note
  on its GitHub issue as a comment, and each verdict as a comment on the issue (note) or the PR (code).

### Steps for every item
1. **Track** (ST-AG-15). Shortcuts: text-only goes to Docs; anything on the trigger list (untrusted input, masking,
   logged data, consumer contract) goes to Feature. Otherwise a vote of three agents on different models.
2. **Design note before code** (ST-AG-16; questions in `docs/agents/judge.md`, "The design note"): Fix answers 1,
   2 and 4; Feature answers all four; Docs has none.
3. **Aashish sees the design first**, with full examples in YAML, `.properties` and Java (every key, a real case
   for each), in plain words. He decides; then the judge reviews the note.
4. **Note judge.** PASS, or FAIL with findings the implementer fixes. After a FAIL, the next round is a **fresh
   judge on a different model**. After two rounds, Aashish decides whether a third round runs or code starts.
5. **Go** from Aashish for the named item (ST-AG-11) before any branch, commit or PR.
6. **Code**: code, tests and docs in one PR; planted faults recorded in the Claims; `./mvnw verify` exits 0
   (decide pushes on the exit code, never on piped output); line counts listed.
7. **PR** opened ready for review (never draft), Claims in the body, assigned to `contactexepex`, with the
   attribution block. Wait for CI, Codex and Sonar at the head commit; fix or answer every thread with evidence
   (ST-AG-04); rebase on `main`.
8. **Code judge**, started by the coordinator once everything is green. **Nothing is pushed while a judge
   reviews.** A later commit needs the judge (or a fresh one) to confirm the new head.
9. **Ready to merge at <sha>**: judge PASS at the head, CI green, every finding fixed, accepted by Aashish, answered
   with evidence the judge accepted, or deferred by the judge to a named issue; every thread resolved.
10. **Aashish merges.** No agent merges, ever. A valid finding after merge gets its own small PR with one judge.

### Working with Aashish (firm)
- One question at a time, short, plain words, with a concrete example, every option and your pick. Close one before
  asking the next. Use full examples before he accepts a design choice.
- A question or a discussion is not a go. In particular, do not start handover-style work (issues, plans) unless
  he asks for it (2026-11-09 19:44Z).
- No patched solutions; look at the whole SDK first. Realistic findings only. Unverified items become GitHub
  issues, non-blocking.
- Change nothing he has decided without asking. Keep him updated on design progress; watch every lane and nudge
  stuck ones; continue autonomously between his answers.
- He reads one place (the project chat). Post PR links there as soon as they are ready for him.

## 4. Code, key and test rules (summary; `AGENTS.md` and `docs/agents/` win)

- **Simple first** (ST-ENG-05): nothing built for a use that does not exist. One job per class; no tiny classes
  (ST-ENG-16); no duplication; helpers shared (ST-ENG-18). Constructor injection only. Lombok and MapStruct for
  boilerplate; no hand-written getters, builders or `equals` (ST-ENG-42). No private static helpers in tests
  (ST-ENG-47). Catch `RuntimeException` only in global handlers.
- **Public API** (ST-ENG-56, ADR 0009): every public type a service must not use is `@StratumInternal`, nested types
  too; API types expose only API types; new API types go on the module's ArchTest list. No package cycles
  (ST-ENG-57).
- **Keys**: every client setting lives under `stratum.http-client.clients.<name>.*` and `defaults.*` (ADR 0011,
  ST-ENG-34 exception). Every key is settable in YAML, `.properties` and Java (`HttpClientPropertiesCustomizer` in
  `META-INF/spring.factories`) with IDE metadata from record Javadoc. A team's bean always wins
  (`@ConditionalOnMissingBean`). Use names teams know from Spring and Resilience4j, never invented ones (Aashish
  19:38Z). Changing a key, default, public API or adding a library needs Aashish (ST-AG-12, ST-ENG-62).
- **High volume**: timeouts on every remote call; bounded pools and queues; retries only for idempotent calls on
  transient failures, or a write never sent (ST-ENG-51). Prefer metrics over logs. Virtual threads never default.
- **Secrets and data**: never log tokens, header values of forbidden headers, or bodies; errors name keys, never
  values (ST-SEC-01, -02). Never log access tokens; log a token id.
- **Tests**: prove each test fails on broken code (ST-TEST-01). `@SpringBootTest` with `@Nested` and its own
  `@DynamicPropertySource` for request ITs, never `SpringApplicationBuilder` (ST-TEST-31). `@Nested` per behaviour
  (ST-TEST-17). Shared data in `testsupport`, repeated checks in assertion helpers (ST-TEST-13 to -16). Test HTTP
  servers bind `127.0.0.1`. No WireMock; use the existing downstream helpers (`ScriptedDownstream`, `SilentPort` in
  `stratum-test`).
- **Docs** in the same PR: YAML and properties for every example, plus Java (ST-DOC-05). `docs/modules/
  http-client.md` is near its 500-line limit: new topics get their own page, linked from it and `docs/README.md`.
- **File limits** (ST-AG-10): 400 lines code and config, 500 text; split, never squeeze.
- **ADRs are never edited**: changes go in a new ADR (next free number: 0013).
- **Checkstyle**: no `OAuth` abbreviation in names (use `Token...`).

## 5. HTTP client facts every lane needs

- **Layering, outermost first:** `ClientErrors` (error mapping, outside retry), `OptionalNotFound` (404 to empty,
  interfaces only), `ClientResilience` (retry, then breaker, then `ServiceTokenResend` per attempt). Exchange
  decorators are applied only in `StratumClientGroups.decorateCalls`; order constants in `HttpClientConstants`.
- **No `ClientHttpRequestInterceptor`**: any interceptor makes Spring buffer the whole request body (ADR 0012, C1
  probe). Auth goes through `ClientHttpRequestInitializer`; headers and interceptors through a request-factory
  wrapper (A2).
- **Transport:** `ClientTransport` (`Pooled` with token set-up, `Mocked`), from #259.
- **Where new cross-cutting work goes:** the call log and meters go in `ClientResilience.call` or the client's
  request factory, never an exchange decorator, so the C2b beans get them (lanes B and C, 18:30Z).
- **Timeouts:** `timeout` is the gap between bytes on every call; `total-timeout` (8s) bounds attempts, waits and
  the wait for headers; `AttemptTimeLeft` carries the time left per attempt.
- **Errors:** `SX-HTTPC-0001` timeout 504, `-0002` unavailable or token fetch failure 503 (no fix text), `-0003`
  breaker open 503, `-0004` no caller token for relay 500. An uncaught downstream error answer gives the caller 500
  (Aashish). Statuses go through `StratumDefaults` (`stratum.web.error.statuses`). 64 KB error-body cap
  (`ErrorBodyLimit`, in the pool).
- **Code ledger:** startup codes used `SX-HTTPC-1000` to `-1008`; `-1009` free; new startup codes start at `-1010`.
- **Spring gotchas:** `Groups.filter` resets after each `forEach`, so call it before every `forEachClient` /
  `forEachProxyFactory`. `getHeaders()` is final on Spring's base request. A `RestTemplate` must get Stratum's
  factory through `RestTemplateBuilder.requestFactoryBuilder`, never `requestFactory` (C2b r2 probe).
- **C2b round 3:** lane C says the note "needs a fresh judge or Aashish's OK" before code; the coordinator reported
  it as passed, but no round-3 verdict file exists in `handoff/http-client/reviews/judge/`. Ask Aashish this one question first.

## 6. The issues in detail

### #263 Retry and circuit-breaker keys (lane B, Feature, start first)
- **Build:** groups `retry` (`max-retries`, `backoff-policy`, `delay`, `multiplier`, `max-delay`) and
  `circuit-breaker` (`enabled`, `failure-rate-threshold`, `sliding-window-size`, `minimum-number-of-calls`,
  `wait-duration-in-open-state`, `permitted-number-of-calls-in-half-open-state`, `slow-call-duration-threshold`,
  `slow-call-rate-threshold`) under `defaults` and each client; API records `ClientRetry`, `ClientCircuitBreaker`,
  enum `BackoffPolicy`; they replace `retries`, `retry-delay` and `circuit-breaker: true|false`.
- **Read:** `handoff/http-client/design/breaker-thresholds-design-note.md`; verdicts
  `handoff/http-client/reviews/judge/breaker-thresholds-note.md`, `-r2.md`, `-r2-confirm.md` (PASS).
- **Carry-overs for the code judge:** the docs sentence beside the Java example must match the YAML (`payments`
  sets its own `delay: 200ms`; only `max-retries` comes from `defaults`); builders on the ArchTest API list; tests
  for a bad `backoff-policy` value and a scalar under `circuit-breaker`; Resilience4j cap of
  `minimum-number-of-calls` tested or dropped from the docs. Record the key change in a new ADR, not ADR 0012.

### #261 C2b client beans (lane C, Feature, parallel with #263)
- **Build:** per client a `RestClient` and a `RestTemplate` bean, `@Qualifier("<name>")`, not default candidates,
  via an internal `StratumClientBeansRegistrar`; `ResilientRequestFactory` runs each attempt through
  `ClientResilience.call` (retry only without a body, shared breaker, initializers and 401 resend per attempt);
  `StratumClientCustomizer.customizeRestTemplate`; new ADR (0013 if first); new page
  `docs/modules/http-client-own-clients.md`; `ClientBeansIntegrationTest`.
- **Read:** `handoff/http-client/design/c2-own-clients/c2b-design-note.md`, `c2b-explained.md`,
  `lane-c-handover.md`; verdicts `handoff/http-client/reviews/judge/C2b-client-beans-note.md` and `-r2.md`.
- **Must also:** a 404 through each bean keeps Spring's `HttpClientErrorException.NotFound` (B2b judge note);
  401-then-200 test (A1b is merged); refresh the note's class names to `main` (#257 and #259 moved token set-up
  into `ClientTransport`); align `c2b-explained.md` with the final keys; use #263's keys if it merged first.
- Aashish decided: Spring-native beans, no wrapper, both beans, full parity (18:06Z to 18:26Z).

### #264 A2 headers and interceptors (lane A, Feature)
- **Build:** keys `headers` and `copy-headers`; API `ClientInterceptor`, `GlobalClientInterceptor`,
  `OutgoingRequest`, `StratumClient.interceptors()`; internal `OutgoingStepsRequestFactory`, `CopiedHeaders`,
  `ClientInterceptors`, `StreamedBodyMarker`; forbidden headers stop startup (`SX-CORE-1003`).
- **Read:** `handoff/http-client/design/a2-headers-interceptors-design-note.md`; verdicts
  `handoff/http-client/reviews/judge/A2-headers-note.md`, `-r2.md` (PASS, four code-judge notes listed in the issue).
- **Must also:** refresh the note against `main` (A1b and C3 merged since its base); expose the delegate's request
  attributes (mocks read `stratum.mock.method`); if #261 merged first, join its wrapper.

### #266 B3 call log, trace headers and metrics (lane B, Feature, needs a note)
- **Build (after the note):** one WARN per failed call by default with `uri` template and redacted `url` (template
  only without the logging starter's redaction, learned through a signal type, not by importing logging classes);
  four HC5 pool gauges per client; refused-call counter; retry and breaker meters; `trace-headers` (default true,
  `traceparent` and `X-Correlation-Id`). Fold in #237 if it fits.
- **Read:** ADR 0011 sections 2, 4.5 and 8; `handoff/http-client/design/design-v4.md` ("Logs, traces and
  metrics"); `gap-review-merged.md` A11, A12; issue #237.
- Aashish sees the note with full examples before its judge.

### #267 Mocked client fetches no token (lane C, Fix)
- One `@Nested` test group: a `mock: true` client with `auth.client-credentials` (and one with token relay) sends
  no token request; planted fault "mocked transport runs the token initializers". Source:
  `handoff/http-client/reviews/judge/PR-259-http-client-c3-r2-confirm.md`, Notes 1.

### #260 Jackson 2 parse error (lane B, Fix)
- Probe first whether a Stratum client can use Jackson 2's converter on Boot 4.1. If not, close it with the
  evidence. If yes, failing test, then fix in `RetryRules.ioFailureOf` (stop at `HttpMessageNotReadableException`).

### #250 Docs: Boot's global client timeouts (Docs track)
- Text only; no design note; one judge.

### Planned (only when Aashish asks)
- #262 own-engine retry and breaker: after #261; the C2b r1 probe shows a foreign exception is recorded as a
  breaker success today.
- #268 body debug: blocked on the logging starter's timed switch (#136, #203).
- #269 reactive starter: WebFlux is out of focus; after the blocking starter.
- #236, #237, #238, #243 to #249, #254: each issue holds its own context.

## 7. Where things live

- Repository: `exepex/stratum`; modules `libs/stratum-http-client` (API: `com.exepex.stratum.httpclient`) and
  `starters/stratum-starter-http-client` (`com.exepex.stratum.restclient`).
- Docs: `docs/modules/http-client.md`, `-auth.md`, `-errors.md`, `-mocks.md`; `docs/guides/error-codes.md`;
  `docs/configuration/reference.md`; ADRs 0011 and 0012.
- On this handover branch: design notes in `handoff/http-client/design/`; judge verdicts in `handoff/http-client/reviews/judge/`; lessons in
  `handoff/http-client/process/stratum-lessons.md`; judge process in `handoff/http-client/process/stratum-judge-process.md`.
- Lane memory (decisions with times, gotchas): `handoff/http-client/memory/http-client-*.md`.
