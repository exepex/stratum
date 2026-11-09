---
name: http-client-breaker-keys-note
description: Retry/breaker keys design note (lane B, breaker-thresholds-design-note.md) judge r1 (Fable 5.1) 2026-11-09: FAIL, cross-key checks vs merged Stratum defaults
metadata:
  type: project
  modified: 2026-11-09T21:51:36.009Z
---

Note: design/http-client-libraries/breaker-thresholds-design-note.md (keys Aashish approved 19:38Z: `retry.*` and
`circuit-breaker.*` groups under defaults and per client). Verdict r1 (Fable 5.1, 2026-11-09 ~22:00Z): FAIL,
reviews/judge/breaker-thresholds-note.md. Round 2 needs a fresh judge on another model (Opus).

- F1 blocking: the checks "multiplier/max-delay with fixed" and "slow-call-rate without duration" cannot hold once
  STRATUM_DEFAULTS (multiplier 2.0, slow rate 100) is merged into `defaults` (HttpClientProperties constructor) and
  into clientSettings. `defaults.retry.backoff-policy=fixed` would always fail startup. Suggested: drop the fixed
  check (ST-ENG-05); slow-call check on merged settings with both slow-call keys unset in STRATUM_DEFAULTS.
- F2 should fix: do not edit ADR 0012 (accepted ADRs are never edited); record in ADR 0013 (C2b) or a new ADR.
- F3 should fix: docs/README.md row for new page docs/modules/http-client-resilience.md.
- F4 minor: IDE deprecation entries can only cover `defaults.<old key>`; no per-client metadata exists.
- F5 minor: module page is 486 lines, not 484.

Verified facts (Resilience4j 2.4.0 sources): minimumNumberOfCalls IS capped at slidingWindowSize for count-based
windows (CircuitBreakerMetrics:50-51); no multiplier check in IntervalFunction; ofRandomized(Duration,double) exists;
builder ranges: rates (0,100], window/min/half-open >= 1, open wait >= 1ms.

**Why:** the lane B implementer and the round-2 judge need the open findings. **How to apply:** check F1's
resolution (which object each @AssertTrue reads) before code.
Round 2 (Opus 5.5, 2026-11-09 ~22:10Z): FAIL, reviews/judge/breaker-thresholds-note-r2.md. All 5 r1 findings FIXED;
keys/values unchanged. New: F1 should-fix Java example builds payments from clientSettings() BEFORE replacing defaults,
so merged old defaults freeze into the client (max-retries 3 vs YAML 2); F2 minor max-delay<delay check on merged
settings fails a fixed client although max-delay is "ignored with fixed"; F3 minor "as checks do today" (range checks
read raw clients) and module page line 253 not 251. After 2 rounds remaining findings go to the owner.
Aashish 22:08Z said fix them; note r4 fixed all 3; CONFIRM = PASS (Opus 5.5), reviews/judge/breaker-thresholds-note-r2-confirm.md. Code may start. Code judge: Java example sentence says payments delay 100ms "as in the YAML" but YAML payments sets 200ms; check docs example matches YAML.
Related: [[http-client-lane-b]] [[http-client-lane-b-resilience]] [[http-client-c2b-resilience-note]]
