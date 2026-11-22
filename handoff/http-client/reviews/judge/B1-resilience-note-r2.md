# Judge verdict: design note of B1 (resilience of the blocking HTTP client), Feature track, review steps 1, 2, 3 and 5, round 2

Note: `design/http-client-libraries/b1-resilience-design-note.md`, round-2 revision (210 lines, read 2026-11-09 13:45Z).
Inputs: round-1 verdict `reviews/judge/B1-resilience-note.md`; ADR 0011 and the H1 code at `origin/main` aabdae8;
`gap-review-merged.md`; Resilience4j 2.4.0 sources and jars and Spring Web 7.0.10 sources from Maven Central, with two
small probes run on JDK 21. Fresh judge, no part in building the note, no owner decision reopened.
Model: Claude Opus 5.5 (round 1 was Fable 5.1).

**FAIL.** Two should-fix findings and two minor ones, all text fixes to the note. Round-1 F2, M1 and M2 are fixed;
F1's rule is fixed, but the note does not say how Resilience4j is made to stop, and the obvious way breaks (N-F1).

I read AGENTS.md and all six topic files. Text-only item: step 4 (build) skipped. Nothing changed in the repo; nothing
posted on GitHub; not merged.

## Round-1 findings

| Round 1 | Result | Evidence |
|---|---|---|
| F1 back-off past the deadline | Rule FIXED; mechanism not stated, see F1 below | note:104-105 "The back-off wait is held to the same rule"; "When it is longer than the time left less 100ms, there is no retry"; row `RetryRulesTest.waitNeverPassesTheDeadline` (note:156) |
| F2 three pages say "planned" | MOSTLY FIXED, one page missed, see F2 below | note:188-191 lists `reference.md:175, :200`, `installation.md:100`, `quick-start.md:318` (all checked at aabdae8) |
| M1 streamed-download wording | FIXED | note:106-110: full `timeout` per attempt, headers up to `timeout` after the deadline; matches HC5's one socket timeout |
| M2 HEAD and OPTIONS | FIXED | note:94-95 widens ADR 4.2 on purpose with the RFC 9110 reason |
| Notes carried to the code step | Recorded | note:201-205 |

## Gap list

| Requirement | Result | Evidence |
|---|---|---|
| Gate: four answers, note complete | MET (see M2) | note:24-43 |
| Owner: no request interceptors | MET | note:31-33, 71-72; test `StratumClientGroupsTest.noRequestInterceptor` |
| Owner: retry and breaker are exchange decorators | MET | note:60-66; `forEachProxyFactory` is on `Groups` next to `forEachClient` used at `StratumClientGroups.java:48` |
| Owner: `restClient(name)` does not retry by itself | MET | note:21-22, 134: C2 wraps with `resilience(name).call` |
| Owner: same group predicate as `StratumClientGroups` | MET | note:67-70; today `isStratumClient` (`StratumClientGroups.java:73-76`), widened by C2a |
| Keys and defaults (ADR 2.4) | MET | note:78-86 |
| Retry list, POST/PATCH only after connect failure (ADR 4.2) | MET (see M1) | note:94-98 |
| `Retry-After` within the time left; back-off within the time left (ADR 4.3, 4.4) | PARTLY | rule stated; F1 |
| Back-off doubles with jitter, at most 2s | MET | probe of `IntervalFunction.ofExponentialRandomBackoff(200ms, 2.0, 0.5, 2s)`: waits 100-300, 200-600, 400-1200, 800-2000, then 2000; cap is applied after jitter |
| Breaker rules (ADR 4.5) | MET | note:111-114 |
| Java rules hook (ADR 1.4) | MET | note:115-118; `customize` stays the one abstract method (`StratumClientCustomizer.java:31`) |
| Documents (ST-DOC-01, -08) | PARTLY | F2 |
| Thread and virtual threads (ST-ENG-53) | MET | `RetryImpl` sleeps with `Thread::sleep` on the caller's thread (`RetryImpl.java:49, 268`) and holds no `synchronized` or lock (grep: 0 hits) |
| Size (ST-AG-10), secrets (ST-SEC-01) | MET | 210 of 500 lines; no values |

## Findings

**F1. Should fix (ADR 4.3, 4.4; note:53-54, 99-105, 153-157).** The note says when there is no retry ("wait longer than
the time left less 100ms", and the same for `Retry-After`) but not where Resilience4j is told. Resilience4j asks the
retry predicate first and only then computes the wait (`RetryImpl.java:188-198, 231-242, 259`), so "retry this
attempt?" cannot see the jittered wait; the only stop after the wait is known is a negative interval. That works on
the exception path (the last failure is rethrown) but not on the result path, which the note's
`RetryConfig.Builder<ClientAttempt>` with the failure in `ClientAttempt` (note:51-52, 201) points to. Probe on
Resilience4j 2.4.0, `retryOnResult(a -> a.status() == 503)` and an interval of -1: the caller gets
`java.lang.IllegalStateException: Unexpected exception during retry wait interval` instead of the 503
(`RetryImpl.java:300-305`). Real case with the defaults: a 503 with `Retry-After: 30`. The listed tests
(`RetryRulesTest.*`) test the pure functions only, so they would not catch it. **Fix:** say where the stop is decided
(for example: failures stay exceptions and the interval function returns a negative value, or the predicate decides
with the largest possible wait), and add an integration row such as
`RetryIntegrationTest.retryAfterTooLongReturnsTheAnswer`: a 503 with `Retry-After` longer than the time left is sent
once and the caller gets the 503's own exception.

**F2. Should fix (ST-DOC-01, ST-DOC-08; round-1 F2).** `README.md:269` still says "tracing, tokens and retries are
planned" for `stratum-starter-http-client`, and it is not in "Documents" (note:183-193), although the note's own search
covers `README.md`. **Fix:** add `README.md:269` (retries built; tracing and tokens stay as they are) to "Documents".

**Minor**
- M1. note:94 "Retried, every method but POST and PATCH" is a deny list, so a custom method on `@HttpExchange(method =
  ...)` would be retried after a write, against ST-ENG-51 "retry only idempotent operations". Fix: name the retried
  methods (GET, HEAD, OPTIONS, PUT, DELETE) and treat every other method like POST.
- M2. Answer 1 is four lines (note:25-28); judge.md allows three. Fix: trim one line.

## Planted faults

Not applicable to a text-only note. Checked instead with two probes against the Resilience4j 2.4.0 jars (negative
interval on the result and exception paths; the jitter range), and by reading the Resilience4j and Spring Web 7.0.10
sources and the H1 code on main.

## Notes (not findings)

- Jitter disappears at the 2s cap (attempt 6 and later always 2000ms; with `retry-delay: 1s`, from the third wait).
  With the default 3 retries and 200ms the waits keep their jitter; no change asked.
- A multipart body is a `MultiValueMap` of parts in `HttpRequestValues` (`HttpRequestValues.java:536-539`). A PUT
  whose part is an `InputStreamResource` cannot be sent twice; `StreamedBody` could look at parts as well. Uncommon;
  the code judge may check it.
- "Not verified" item 2 (sleep pinning) is answered: no `synchronized` in `RetryImpl`, and JDK 24+ does not pin on it
  anyway (JEP 491).
- `ByteArrayResource` counts as streamed under "any `Resource`" (note:92-93), so it is never retried though it could be.
  Safe direction; no change asked.

## Not verified

Resilience4j 2.4.0 on JDK 25 and Boot 4.1.2 (probes ran on JDK 21). Owner decisions were checked against the
coordinator's brief, `gap-review-merged.md` and the ADR, not the chat. Not merged; nothing posted on GitHub.
