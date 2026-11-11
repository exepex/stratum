# Judge verdict: B2 errors design note, round 2

**Design note:** `design/http-client-libraries/b2-errors-design-note.md` (round 2, revised), lane B, Feature track,
design-note review (judge steps 1, 2, 3 and 5). Checked against `main` at `da23adc` (B1, PR #256, merged).
Round 1 verdict: `reviews/judge/B2-errors-note.md` (Opus 5.5, FAIL). Judge model this round: Fable 5.1, 2026-11-09.

**PASS.** Each round-1 fix holds; no finding of any severity remains. Notes below for the code round.

I read AGENTS.md and all six topic files in `docs/agents/`.

## What I ran
- Read the note and the round-1 verdict; on `main`: `ClientResilience` (`call`, `attempt`, `rethrow` :115-186,
  `getOrder` Javadoc :71), `ClientConnectionPools.requestFactory` (:76-93, `HttpClients.custom()` builder),
  `StratumClientGroups.decorate` (:75-79, lowest order outermost), `ResilientExchangeAdapter`,
  `StratumClientCustomizer` (Javadoc :10 "runs once per client at startup, never per call"),
  `RestClientEnvironmentPostProcessor` (:48 `StratumDefaults.addBelowServiceValues`), `HttpClientConstants`,
  `HttpClientErrorCode`; core `StratumException` (:31 code-only ctor, :52 code+detail+cause) and `ErrorReport.of`
  (:87-92, blank detail leaves the message as the description alone); web `ErrorResponseFactory.answerAndLog`
  (:214-231, status only from `statusesByCode`) and `bodyOf` (:264, message becomes `detail`).
- Read ADR 0008 (decision 5, lines 49-53), ADR 0011 (6.1, 6.2, Consequences "64 KB error-body cap"), ADR 0012,
  gap review A10, feature list 8; the C2b note (:51-59) and the A1 note (:150); `docs/modules/http-client.md`
  (:251, :474), `docs/modules/web.md` (:108-120), `docs/guides/error-codes.md` (:294-306).
- No build and no probe: the review has no code. Round 1's gzip probe (`addExecInterceptorLast` reads 1 MB,
  `addExecInterceptorFirst` 64 KB) was not re-run; it matches HC5's chain, where `FIRST` is the outermost element
  and `LAST` is placed before `MAIN_TRANSPORT`, inside `COMPRESS`.

## Round-1 fixes
| Finding | Result | Evidence |
|---|---|---|
| F1 cap outside gzip | ACCEPTED | note: `addExecInterceptorFirst` in `ClientConnectionPools`, "outside `COMPRESS`"; new row `gzipErrorBodyCappedAfterInflate`; the pool builds the client with `HttpClients.custom()` (:83), so the call fits there |
| F2 breaker open on `resilience(name)` | ACCEPTED | note: `ClientResilience.rethrow` throws `SX-HTTPC-0003`; `main` :144 `lastAttempt.getFailure().ifPresent(this::rethrow)` is the one exit of `call`, which `ResilientExchangeAdapter.run` (:107) uses and C2b's note (:51-59) says `resilience(name)` will use; C2b note agrees word for word; test row with both orders of landing |
| F3 no downstream detail to the caller | ACCEPTED | note: "no detail, only its code's description"; `ErrorReport.of(code, "")` gives the description alone (:88-92), which `bodyOf` sends; downstream named in the log by the kept cause and B3's line |
| F4 ADR 0008 decision 5 | ACCEPTED | answer 4 and "Rejected" cite decision 5; ADR lines 49-53 are that rule |
| F5 existing and hybrid service | ACCEPTED | answer 1 (3 lines) and "Moving a client in": catch change, compatible-mode body of a 503/504 (ADR 0008 decision 2) |
| F6 no key for the cap | ACCEPTED | "No key" line: ADR 0011 2.4's 19 keys, A10 fixed 64 KB (gap review :53), constant in `HttpClientConstants` |
| F7 customizer Javadoc | ACCEPTED | "Error mapper" bullet names the Javadoc change; `main` :10 has the sentence to change |
| Note 5 `getOrder` Javadoc | ACCEPTED | `rethrow` bullet names it; `main` :71 has the sentence |

## Gap list (re-checked independently)
| Requirement (ADR 0011, feature list, owner) | Result | Evidence |
|---|---|---|
| 0001 504, 0002 503, 0003 503 (6.1, 8.3) | MET | table "What each final failure becomes"; defaults in `RestClientEnvironmentPostProcessor` below the service's values |
| Status only from `stratum.web.error.statuses`, no core change (ADR 0008 d5) | MET | `ErrorResponseFactory.java:221`; "Rejected: `getHttpStatus()` on core `ErrorCode`" |
| `DownstreamException` with status, client, endpoint, code, trace ID, body (6.2, 8.1, 8.2) | MET | API type extends `RestClientResponseException`; `getDownstreamCode()` `Optional`; transient Spring exception for conversion |
| Vendor body never to our caller (6.2); owner: uncaught stays 500 | MET | row 1 + `uncaughtDownstreamIs500WithoutItsBody` |
| Error body at most 64 KB (6.2, A10) | MET | `ErrorBodyLimit` first in the chain; plain, gzip and success tests |
| Error mapper per client (1.4, 2.4, 8.5) | MET | `mapError` default method, first customizer in order wins, codes never mapped, tests in `ClientCustomizerIntegrationTest.ErrorMapper` |
| Retry, breaker, lane A resend still see Spring's exceptions | MET | translation order `RESILIENCE_ORDER - 1`; `decorate` :76-79 puts the lowest order outermost |
| Breaker open 503 for interfaces and `resilience(name)` (4.5) | MET | F2 |
| Owner: token fetch fail stays lane A's `SX-HTTPC-0002` | MET | "Anything else ... unchanged" |
| 8.4 `Optional` 404 split to B2b; `restClient(name)` keeps Spring's exceptions (ADR 0012) | Accepted | `HttpServiceMethod` unwraps `Optional` (round 1 confirmed); ADR 0012 :39 |
| Documents: module page, error codes page, web page, planned list | MET | "Documents" section names each; `http-client.md:251` and `:474` are the lines to change |
| Design-note answers at most 3 lines, Feature answers all four | MET | counted: 3, 3, 3, 3 |

## Planted faults
None: a design-note review has no code.

## Notes (not findings; for the code judge and the coordinator)
1. **Coordinator:** the A1 note (:150) says A1b adds the default `stratum.web.error.statuses[SX-HTTPC-0002]=503`, and
   this note adds it too, with `DOWNSTREAM_UNAVAILABLE`. Whichever lands second rebases onto the other's constant
   and enum entry; neither note should end with the default declared twice.
2. **Code judge:** the cap wrapper must pass `close()` to the original stream, since it is HC5 (`ContentLengthInputStream`
   and `ChunkedInputStream.close`, via `ResponseEntityProxy.streamClosed`) that drains the rest and frees the
   connection, not Spring; the planned `largeErrorBodyCapped` row asserts the reuse, which is the right check.
3. **Code judge:** `StratumException` has no code-plus-cause constructor on `main` (:31, :41, :52); the note's
   "no detail, cause kept" means `new StratumException(code, "", cause)`, whose message is the description alone
   (`ErrorReport.of` :88-92). No new constructor is needed.
4. The kept cause of 0001/0002 (`ResourceAccessException`) carries the real URL with path and query values into the
   stack trace the web starter logs at ERROR; the same line is logged today for any uncaught one (round 1, Note 4).
5. `docs/guides/migrating-a-service.md:88` still says retries and the breaker are planned; that is B1's leftover, not
   B2's scope, and can go with B2's page edits or B3.

Not merged; nothing in the repository changed.
