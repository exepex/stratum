---
name: http-client-lane-c
description: HTTP client lane C (streaming, teams' own clients, mocks) status after 2026-11-09: what merged, what is left for handover, owner naming lesson
metadata:
  type: project
  modified: 2026-11-09T22:08:14.709Z
---

Lane C thread cmsg_0183M4FLDkiYRAp7MyRDM5gdMafaHL1i4QgkwU4ZiS9oef. Handover file: design/http-client-libraries/c2-own-clients/lane-c-handover.md (project files).

- MERGED: C1 streaming #253, C2a own clients #252, C3 mocks #259 (merged 2026-11-09 22:07Z, after judge r1 FAIL minor, r2 one minor fixed).
- C3 shape: `mock: true` per client; `ClientTransport` sealed (Pooled = pool + credential incl. A1b TokenInitializers; Mocked = MockRequestFactory, no credential); MockClients/MockClient API; docs/modules/http-client-mocks.md.
- NOT STARTED (Aashish 19:42Z: no C2b code; handover issues): C2b injectable RestClient + RestTemplate beans per client with @Qualifier, retry+breaker via request-factory wrapper, ADR 0013; note round 3 needs fresh judge or Aashish OK. c2b-explained.md must switch to the final resilience keys.
- Follow-ups: test that a mocked client with client-credentials fetches no token; issues for mocked client in metric/actuator info and mocks in reactive starter; B2b Optional-on-404 docs line on own-clients page.
- Final resilience keys (Aashish 19:38Z, lane B builds): retry max-retries, backoff-policy exponential|fixed, delay, multiplier, max-delay; circuit-breaker enabled, failure-rate-threshold, sliding-window-size, minimum-number-of-calls, wait-duration-in-open-state, permitted-number-of-calls-in-half-open-state, slow-call-duration-threshold, slow-call-rate-threshold (slow rule off unless set). Under defaults and per client, yml/properties/Java.
- LESSON: Aashish rejects invented "plain" names; use Spring/Resilience4j names with a one-line comment each.
- Merge practice: merge main into the branch (force push refused); a merged file over 400 lines gets split (e.g. HttpClientPropertiesCustomizerTest).
