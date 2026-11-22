---
name: http-client-b2b-optional-404
description: B2b (404 read as empty Optional on client interfaces) design note judged PASS in round 2 on 2026-11-09; where the verdicts are and the build details the code PR must handle
metadata:
  type: project
  modified: 2026-11-09T19:00:17.635Z
---

B2b design note (`design/http-client-libraries/b2b-optional-404-design-note.md`, Feature track, lane B after B2 #258):
round 1 FAIL (Opus 5.5, `reviews/judge/B2b-optional-404-note.md`: F1 NPE on the three non-body Optional shapes,
F2 docs must say C2b beans keep NotFound); round 2 PASS (Fable 5.1, 2026-11-09 ~19:10Z,
`reviews/judge/B2b-optional-404-note-r2.md`). Code may start; next judge for the code review must be a different model
from Fable 5.1.

Design as passed: processor via `HttpServiceProxyFactory.Builder.httpRequestValuesProcessor` marks calls whose method
returns `Optional` with attribute `stratum.httpclient.optional-answer`; `ErrorTranslatingExchangeAdapter.around`
returns null on a marked 404 (mapError not asked); `AroundCallExchangeAdapter.answerOf` lets null through only for
marked calls, `requireNonNull` otherwise. Only client interfaces do this; C2b RestClient/RestTemplate beans keep Spring's
NotFound.

Build details found by the judge's probe: SpotBugs accepts `answerOf` with no suppression; use
`HttpStatus.NOT_FOUND.value()` not literal 404 (Checkstyle MagicNumber); write the processor as a lambda with unnamed
parameters `(method, _, _, builder) -> ...`, a method reference to a 4-arg method trips PMD UnusedFormalParameter.

**Why:** the code judge and the coordinator need the verdict chain and the model rotation. **How to apply:** code
review of the B2b PR gets both verdicts; the errors page's catch-404 example (`http-client-errors.md:55-67`) should
steer to Optional. Related: [[http-client-lane-b]] [[judge-build-environment]]

2026-11-09 ~19:55Z: code PR #265 opened at 7f6e74b (branch restarted from main after #258 merged). 5 planted faults caught. Not built: largeNotFoundBodyEmptyAndConnectionReused; observation-tag check not verified (named in Claims). Full verify here fails only at data-jpa (no Docker). Wrap-up near usage limit: resilience note (breaker-thresholds-design-note.md, rewritten to approved keys) and B3 go to handover issues.

CODE JUDGE r1 FAIL 2026-11-09 ~20:45Z (Opus 5.5, head e0447c0): reviews/judge/PR-265-http-client-b2b.md + PR comment. F1 should fix ST-ENG-54: processor calls decorators.orderedStream() per call (~300 ns, 888 B garbage) -> resolve list once in decorateCalls. F2 should fix: no non-404 4xx test on Optional method (plant is4xxClientError survived) -> add 403 endpoint. Codex getReturnType rejection ACCEPTED. Claims gaps closed (BodyLimit test covers reuse; Spring convention reads no attributes). Round 2 judge: different model from Opus 5.5.

2026-11-09 20:50Z: #265 code judge r1 FAIL (decorators sorted per call; no 4xx-widening test) -> fixed ba83919; r2 PASS, no findings. All threads resolved. Final shape: OptionalNotFound decorator (order between error translation and resilience) + ClientExchangeDecorator.prepare default hook. Handover note for C2b: add a test that the RestClient/RestTemplate beans keep NotFound.

2026-11-09 20:54Z: #265 MERGED. Lane B done for this session; remaining (resilience keys note awaiting Aashish check + judge, B3, #260, C2b NotFound test) goes to handover issues.
