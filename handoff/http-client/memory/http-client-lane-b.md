---
name: http-client-lane-b
description: HTTP client lane B (resilience -> errors -> call log/metrics), thread cmsg_0183M4FLDkiYRAp7MyRDM5gdGxxN37jg742SgpHEzTXxkr; decisions and state
metadata:
  type: project
  modified: 2026-11-09T17:28:03.416Z
---

Lane B of ADR 0011 (owner chose parallel lanes A/B/C 2026-11-09 13:26Z, autonomous under agreed process).
Branch claude/project-thread-ex55r3. Steps: B1 resilience, B2 errors (SX-HTTPC-0001..0004, DownstreamException), B3 call log + pool/retry/breaker meters (+#237 if fits).

- B1 note: /mnt/project-files/design/http-client-libraries/b1-resilience-design-note.md. Retry+breaker are one
  HttpExchangeAdapterDecorator (ClientResilience, order 0) per Stratum group via forEachProxyFactory, NOT a request
  interceptor (Spring 7 buffers bodies). Per-attempt timeout via setHttpContextFactory + ScopedValue; connect timeout
  cut via Apache setConnectionConfigResolver. Refused-call counting moved to B3.
- B2 DECIDED (Aashish 13:33Z card): an uncaught downstream error answer (404, 500...) gives our caller 500, as
  today; no new code SX-HTTPC-0005 (rejected), no copying the downstream status (rejected).
- B2 inputs: SX-HTTPC-0004 -> 500 on the status list; lane A token-fetch failure is StratumException SX-HTTPC-0002
  (503), never retried, never counted by the breaker (a non-RestClientException escapes ClientResilience.attempt
  and the breaker records it as a success call); error mapping must run OUTSIDE retry/breaker/resend.
- B1 PR #256: CODE JUDGE PASS round 1 (Fable 5.1) at head fe6a360, 2026-11-09 ~17:45Z; verdict
  reviews/judge/PR-256-http-client-b1.md, posted as PR comment. 14 faults planted, 14 caught. Notes only:
  ClientAttempt.getFailure Javadoc lists two exception types as examples; lane A should add a test that
  SX-HTTPC-0002 is not retried/counted once its code lands. MERGED 2026-11-09 17:36Z.
- B2 note: design/http-client-libraries/b2-errors-design-note.md; r1 FAIL (Opus), r2 PASS (Fable 5.1)
  reviews/judge/B2-errors-note-r2.md. Statuses via StratumDefaults in RestClientEnvironmentPostProcessor
  (stratum.web.error.statuses[SX-HTTPC-000x]), not ErrorCode.getHttpStatus. 64 KB cap = Apache ExecChainHandler
  addExecInterceptorFirst (outside gzip). Breaker open -> SX-HTTPC-0003 in ClientResilience.rethrow. Codes carry NO
  detail. 404-as-empty Optional = B2b. PR #258 opened 2026-11-09 at dabc0fd, head 1f81239 (Codex P2 fix: whole-URI
  endpoint = method only); mapError is void and THROWS; AroundCallExchangeAdapter base. CODE JUDGE r1 FAIL
  2026-11-09 ~18:55Z: see [[http-client-b2-errors-judge]]. Then B2b, B3.
- Code ledger: 1005-1006 lane A, 1007-1008 lane C (1009 free); B2 new startup codes start at 1010.

**Why:** later threads continue the lane. **How to apply:** build B2 with 500 for uncaught downstream answers;
error mapping outside the decorators.
Related: [[http-client-library-survey]] [[http-client-h1-pr]] [[http-client-lane-b-resilience]] [[judge-build-environment]]

- B3 placement (lane C note 2026-11-09 18:29Z, C2b): call log line + meters go in ClientResilience.call or the request factory, NOT an exchange decorator, so the RestClient/RestTemplate beans get them too. The 64 KB cap is the Apache ErrorBodyLimit in ClientConnectionPools (reaches every pooled client). #258 fix 1f81239: endpoint of a call given a whole URI = method only.
- 2026-11-09 18:45Z: #258 green at 1f81239 (Build+Wiki pass; earlier red runs had 0 jobs = never ran); code judge requested from coordinator. B2b note at design/http-client-libraries/b2b-optional-404-design-note.md: httpRequestValuesProcessor marks Optional-returning methods (attribute), ErrorTranslatingExchangeAdapter returns null on marked 404 -> Spring Optional.ofNullable; mapError not asked for that 404. Note judge requested.
- 19:05Z: #258 judge r1 FAIL fixed in 4779625 (RetryRules.ioFailureOf walks causes; 0002 no fix text, lane A same in #257 85e73ff). B2b note PASSED r2 (reviews/judge/B2b-optional-404-note-r2.md): use HttpStatus.NOT_FOUND.value(), processor lambda with unnamed params, steer errors page catch-404 example to Optional. B2b code stacks on #258.
- 19:18Z: #258 head 0f7576c (Sonar S7467 fix); CI green at 4779625. B2b code committed locally in worktree branch b2b-local (scratchpad/b2b, 1c89807), verify passes; push after #258 merges (new PR from same branch restarted on main). Breaker-thresholds note at design/http-client-libraries/breaker-thresholds-design-note.md (5 flat keys circuit-breaker-*), judge running; naming decision card to Aashish (flat recommended vs nested). Lane C confirmed beans use ClientResilience only.
- 19:35Z: #258 judge r2 PASS at 0f7576c, awaiting Aashish merge. Issue #260 logged (Jackson 2 JsonParseException read as cut-off). Breaker note rewritten nested (Aashish chose nested on lane C card; names from resilience.md: enabled, failure-rate-threshold, sliding-window-size, minimum-calls, wait-in-open-state, half-open-calls; Stratum defaults = R4j defaults); judge ON HOLD until Aashish's resilience gap review (retry/backoff knobs may join).

2026-11-09 21:44Z: Aashish APPROVED the retry/circuit-breaker design note (breaker-thresholds-design-note.md, keys retry.{max-retries,backoff-policy,delay,multiplier,max-delay} + circuit-breaker.{enabled,failure-rate-threshold,sliding-window-size,minimum-number-of-calls,wait-duration-in-open-state,permitted-number-of-calls-in-half-open-state,slow-call-*}). Next: judge, then code (issue #263).
