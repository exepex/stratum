---
name: http-client-b2-errors-judge
description: PR #258 (B2 HTTP client errors) code judge r1 FAIL (Fable, 1f81239), r2 PASS (Opus 5.5, 0f7576c) 2026-11-09
metadata:
  type: project
---

PR #258 (lane B step 2, DownstreamException + SX-HTTPC-0001..0003 + 64 KB cap + mapError), code judge round 1 at
head 1f81239, 2026-11-09 ~18:55Z, Fable 5.1: **FAIL**. Verdict: reviews/judge/PR-258-http-client-b2.md, posted
as PR comment. Gate passed (CI green, Codex P2 fixed, Sonar clean); build on JDK 25 passed; 18 faults planted, 17 caught.

- F1 should fix: `ClientErrors.noAnswer` and B1's `RetryRules.isBodyCutOff` check only the DIRECT cause
  (`getCause() instanceof IOException`). A JSON body cut off by the read timeout is
  RestClientException <- HttpMessageNotReadableException <- JacksonIOException <- SocketTimeoutException, so the
  caller gets 500 (not 504) and breaker/retry miss it. Probed through a real @StratumClient (Map return vs String
  return). Fix: one shared cause-chain IOException predicate for both classes + a JSON cut-off integration test.
- F2 minor: docs/modules/http-client-errors.md shows stratum.web.error.statuses without a Configure in Java line.
- F3 minor: `catch (IOException e)` in test ErrorDownstream (ST-ENG-13).
- Note for owner/coordinator: DOWNSTREAM_UNAVAILABLE (0002) has fix text, 0001/0003 none; ST-ENG-27 says runtime
  codes have no fix text; text is copied from A1b (#257), decide once for both lanes.
- ROUND 2 PASS 2026-11-09 ~19:35Z, Opus 5.5, head 0f7576c: F1-F3 fixed (RetryRules.ioFailureOf walks FailureCauses.causesOf), 0002 fix text ""; 4 plants caught. Verdict reviews/judge/PR-258-http-client-b2-r2.md + PR comment. Notes: Claims' rebase line stale (main ba0e42b, clean merge); Jackson 2 IOException parse errors would read as 0002 (deprecated opt-in).

**Why:** the r2 judge and lane B need the findings and the probe recipe. **How to apply:** r2 judge re-runs the
JSON cut-off probe (server sends `{"a":1,` then stalls; client timeout 500ms) and checks RetryRules too.
Related: [[http-client-lane-b]] [[judge-build-environment]]
