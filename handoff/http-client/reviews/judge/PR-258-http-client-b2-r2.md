# Judge verdict: PR #258, B2 errors of the blocking HTTP client, code review round 2

**Pull request:** #258, branch `claude/project-thread-ex55r3`, head `0f7576c`, Feature track, code review round 2
(judge steps 1 to 5), given the round 1 verdict `reviews/judge/PR-258-http-client-b2.md`. Judge model: Opus 5.5,
2026-11-09.

**PASS.** Each round 1 finding is fixed and I reproduced each fix; no new finding.

I read AGENTS.md and all six topic files in `docs/agents/`.

## Gate
- Open, not a draft. Claims updated for this round: round 1 findings and their fixes, plant F13, line counts at the
  head (I counted all 9 files changed since `1f81239`; they match, largest `RetryRulesTest.java` 400, at the limit).
- CI at `0f7576c`: Verify and SBOM (run 42681857265), CodeQL (actions, java-kotlin), wiki sync, SonarCloud all
  `success`. Codex: no findings at `0f7576c`; its one earlier thread resolved. Sonar: quality gate passed, 0 new
  issues, 0 hotspots.

## What I ran
- JDK 25 (`openjdk-25-jdk-headless`), Maven 3.10.0 (wrapper zip, sha256 checked):
  `mvn -B -pl starters/stratum-starter-http-client -am install` → `BUILD SUCCESS`, exit 0; the starter's
  `TEST-*.xml`: 93 unit and 91 integration testcases, 0 failures; Spotless, Checkstyle, PMD, SpotBugs, JaCoCo passed.
- Read `git diff 1f81239..0f7576c` (9 files, +125 −17), `FailureCauses`, `RetryRules`, `ClientErrors`,
  `WebProperties`, `WebPropertiesCustomizer`, `StratumPropertiesResolver`.
- One probe and 4 planted faults (below), each run on the module's tests and restored.

## Round 1 findings
| # | Result | Evidence |
|---|---|---|
| F1 JSON body cut off by the timeout → 500 | FIXED | `RetryRules.ioFailureOf` :225-230 walks the chain with core's `FailureCauses.causesOf` (loop-safe, already shared); used by `ClientErrors.noAnswer` :82 and `isBodyCutOff` :233-235, so code, retry and breaker agree. New IT `ClientErrorsIntegrationTest.cutOffJsonIsTimeout` goes through a real `Map` return and asserts `SX-HTTPC-0001` with root `SocketTimeoutException`; plants Q1, Q3 make it fail |
| F1 no false positive | MET | Probe: real 200 `application/json` bodies `{"id":` and `<html>` through the real stack stay Spring's `RestClientException` (chain `HttpMessageNotReadableException ← UnexpectedEndOfInputException`, no I/O cause); `invalidBodyUnchanged`, `invalidBodyNotRetried` catch plant Q4 |
| F2 errors page lacks Java | FIXED | `http-client-errors.md` :37-44 names `WebPropertiesCustomizer`, links `web.md#configure-in-java` (anchor at web.md :457); `ErrorSettings` has `@Builder(toBuilder = true)` (`WebProperties` :69); the customizer gets the bound settings (`StratumPropertiesResolver` :67), which hold Stratum's defaults, so "start from the statuses already set" is right |
| F3 variable `e` | FIXED | `ErrorDownstream.java` :119 `catch (IOException startFailure)`; new `catch (InterruptedException _)` :187 |
| Coordinator: 0002 without fix text (ST-ENG-27) | MET | `HttpClientErrorCode` :25-29 fix `""`, as 0001 and 0003; the operator's fix stays in `error-codes.md` :304 |

## Rule checks
ST-ENG-06/18: one predicate, reusing `FailureCauses` rather than a new walk. ST-ENG-47: `ioFailureOf` is static in
the `@UtilityClass` `RetryRules`. ST-ENG-46: one-expression lambdas. ST-ENG-13: named variables. ST-ENG-15: Javadoc
on the new method, constant, latch and test helpers. ST-DOC-05: YAML, properties and Java. ST-TEST-16: the IT uses
`CallChecks.assertEndsWith`. AGENTS.md line limits: all ≤ 400 / 500, each ends with a newline.

## Planted faults: 4 planted, 4 caught
| # | Fault | Caught by |
|---|---|---|
| Q1 | `ioFailureOf` reads only the direct cause (round 1 code) | `jsonBodyCutOffByTimeout`, `RetryRulesTest.counted`, `bodyCutOffRetriedOnlyWhenIdempotent` ×2, IT `cutOffJsonIsTimeout` |
| Q2 | `isBodyCutOff` alone back to the direct cause (retry and breaker) | `RetryRulesTest.counted`, `bodyCutOffRetriedOnlyWhenIdempotent` ×2 |
| Q3 | `ClientErrors.noAnswer` alone back to the direct cause | `jsonBodyCutOffByTimeout`, IT `cutOffJsonIsTimeout` |
| Q4 | a not-readable body treated as an I/O failure | `invalidBodyUnchanged`, `invalidBodyNotRetried`, `counted`, and 2 more |

## Notes (not findings)
1. The Claims say "Rebased on `main` at da23adc (no newer commit)"; `main` is now `ba0e42b` (#255). `git merge-tree`
   with `main` is clean and #255 changes no HTTP client file (redaction, logging, observability only). Update the
   line; no rebase needed for this review.
2. The walk takes any `IOException` in the chain. A converter whose parse error is itself an `IOException` (Jackson
   2's `JsonProcessingException`, which Boot 4 keeps only as a deprecated opt-in) would read bad JSON as
   `SX-HTTPC-0002` and retry a `GET`. Boot 4's default Jackson 3 is not affected (probe above). Not probed with
   Jackson 2, which is not on the module's classpath.
3. Retry of a cut-off JSON body is pinned by `RetryRulesTest` only, not by an IT; Q2 shows the unit tests catch it.

Not merged; nothing in the repository changed.
