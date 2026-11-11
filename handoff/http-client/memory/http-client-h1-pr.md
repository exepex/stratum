---
name: http-client-h1-pr
description: HTTP client H1 is PR #242 (2026-11-09); what was built, deviations from the note, and open points
metadata:
  type: project
  modified: 2026-11-09T08:09:01.973Z
---

H1 of ADR 0011 opened as PR #242 on branch claude/project-thread-l2n6gt (commit 17df44e). It supersedes PR #79, which Aashish closes. The coordinator was asked to start a fresh code judge after CI; the design-note judges were Fable (r1) and Opus (r2).

Deviations from the accepted note, stated in the Claims:
- a missing url stops startup with SX-HTTPC-1004;
- StratumClientCustomizer is in the starter package com.exepex.stratum.restclient;
- there is no starter README; the property table is on docs/modules/http-client.md (ST-DOC-04, shared prefix);
- Apache HC5 is built directly, not through Boot's builder.

Open points:
- SilentPort is copied into the starter's testsupport, because moving it to stratum-test main fails the SpotBugs rule UNENCRYPTED_SOCKET. Codex flagged the copy (ST-ENG-06); 09:19Z Aashish DECIDED: move to stratum-test with a by-name SpotBugs exclusion for that one class; he asked about WireMock first (answered: WireMock always accepts, cannot test connect timeout; not in repo). 10:59Z card "Keep it, move SilentPort"; DONE in cd26465 (libs/stratum-test/spotbugs-exclude.xml, one class, two bug patterns). WireMock not added (he did not say go). Coordinator starts code judge once CI green at final head.
- 11206b9 (2026-11-09): Sonar S7467 unnamed catches fixed; module page no longer lists ClientConnectionPools/StratumClientGroups as replaceable (they stay @StratumInternal; Codex ST-ENG-56 thread answered).
- Docker is not available in the cloud container, so data-jpa ITs fail locally and CI covers them.

State 2026-11-09 11:48Z: head 4765c6b, CI green, Codex clean, 10 threads resolved; code judge running (thread cmsg_0183M4FLDkiYRAp7MyRDM5gdHdMjmozgtcWmRDpXaGW9gy); no pushes until it reports. Proposal for Aashish later (ST-AG-19): build check for block lambdas / private static helpers (Codex found ST-ENG-43/46/47, ST-TEST-17 one round at a time). Answered Aashish: remaining list (steps 2-11) and 3 parallel lanes (A auth->headers/interceptors, B resilience->errors->log/metrics, C streaming/mocks/foreign); lanes need his OK since ADR says 'in order'.

**Why:** a later thread needs the PR state and these deviations.
**How to apply:** check #242's live state first. Related: [[stratum-agent-rules]]
State 2026-11-09 12:50Z: judge r1 FAILED at 4765c6b (tests only, F1-F3 + M1); fixed in f8f7da7 (tests only, all judge faults caught). Lesson: CallChecks.holdOneConnection must not use the common ForkJoinPool (3 threads on 4 CPUs); uses Thread::startVirtualThread. CI green at f8f7da7 (12:41Z), Codex+Sonar clean; coordinator told; awaiting judge r2 (fresh, different model). No pushes while it reviews.
State 2026-11-09 13:05Z: judge r2 (Fable 5.1) FAILED at f8f7da7: round-1 fixes all accepted (faults replanted, caught). New: F1 post-processor order untested (fault ORDER-1 survives; enabled=false in a profile file catches it), F2 page:240-241 Boot RestClientCustomizer-factory-replaced promise untested (StratumClientGroups ORDER=-1 survives), F3 scanner `AnnotationTypeFilter(..., false, true)` picks up an un-annotated sub-interface of a client -> raw NPE at startup (fix: considerInterfaces=false + test), M1 blank url untested, M2 overriding-defaults.md/reference.md/basics.md still say "planned". Verdict reviews/judge/PR-242-http-client-h1-r2.md. Per judge.md rounds rule 3, findings after two rounds go to the owner.
2026-11-09 13:30Z: r2 findings all fixed in 6a30794 (CI green), judge r3 started.
2026-11-09 13:40Z: judge r3 (Opus 5.5) PASS at 6a30794, no findings; all r2 faults replanted and caught; CI/Codex/Sonar clean, 10/10 threads resolved. Verdict reviews/judge/PR-242-http-client-h1-r3.md. Optional note: one sentence that Boot's global spring.http.clients.* timeouts don't reach Stratum clients.
2026-11-09 13:24Z: PR #242 (H1) MERGED. Next H items need Aashish's go; pending his answers: parallel lanes (ADR order) and WireMock offer; ST-AG-19 build-check proposal still to list for him. PR #79 to be closed by him.
2026-11-09 13:35Z: Aashish chose PARALLEL LANES (A auth+headers, B resilience/errors/log/metrics, C streaming/mocks/team clients; coordinator started 3 lane threads), work autonomously to finish the starter; PR #79 closed. ADR 0011 follow-up issues filed #243-#250 (egress proxy, mocked metric, afterReceive, health, graceful shutdown, discovery, JWT-bearer, Boot global timeouts doc). Reactive starter after lanes A and B merge.
