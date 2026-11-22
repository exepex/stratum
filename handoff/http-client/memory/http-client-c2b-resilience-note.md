---
name: http-client-c2b-resilience-note
description: C2b (stratumHttpClients.resilience(name)) design-note judge r1 (Opus 5.5) 2026-11-09: FAIL, own-engine clients get no retry/breaker
metadata:
  type: project
  modified: 2026-11-09T17:44:14.576Z
---

C2b note: design/http-client-libraries/c2-own-clients/c2b-design-note.md. Verdict r1 (Opus 5.5, 2026-11-09 ~18:00Z):
FAIL, reviews/judge/C2b-resilient-calls-note.md. Round 2 needs a fresh judge on another model (Fable).

- F1 blocking: ClientResilience on main understands only Spring RestClientException; a vendor SDK's own exception
  is not retried and the breaker records it as a SUCCESS (probe: UncheckedIOException(ConnectException) -> 1 attempt,
  breaker CLOSED ok=2). ADR 0011 9.3 promises resilience(name) for own-engine clients. Suggested fix: scope to
  restClient(name) calls, own-engine part Planned in an issue, owner OK (narrows accepted ADR).
- F2 minor: cite ADR 0012 decision 3 (resilience gives retry, breaker AND 401 resend); say who wires
  ServiceTokenResend.call inside ClientResilience.call (A1 note F5 commits lane A, test plainCallThroughResilience);
  name package; cover all 8 ST-AG-14 params; pick cached vs per-call object.
- Verified OK: restClient(name) gets AttemptTimeLeft (ClientConnectionPools:101); guards keyed by name only, no
  interface needed.

**Why:** round-2 and code judges need the open findings. **How to apply:** check F1 scope decision first.
Related: [[http-client-lane-c]] [[http-client-lane-b]] [[judge-build-environment]]
