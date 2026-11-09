# Judge verdict: design note of ADR 0011 (HTTP client starters), Feature track, review steps 1, 2, 3 and 5, round 3

Note: `design/http-client-libraries/adr-0011-design-note.md` (59 lines, 01:01Z). Sources: `adr-0011-draft.md`
(233 lines, 00:59Z), `design-v4.md`, `gap-review-merged.md` "Decisions" (to 01:01Z), `spike-result.md`. Earlier
verdicts: `ADR-0011-design-note.md` (round 1), `-r2.md` (round 2). Repo at `origin/main` 8b111f3; no branch or PR
exists. Fresh judge, no part in building the note. Round 3 was approved by the owner (gap-review-merged.md:67).

**FAIL.** One blocking finding and two minor ones, each a one- or two-sentence text fix. All six round-2 findings are
fixed. No owner decision is reopened. Under judge.md "Rounds" 3, the owner decides what happens next.

I read AGENTS.md and all six topic files. Text-only item, so step 4 (build) is skipped. Nothing was changed in the repo.

## Round-2 fixes, verified

| Finding | Result | My evidence |
|---|---|---|
| F1 A15 header merge | Fixed | ADR 2.1 (draft:51-53): headers added, client wins per name, empty value drops a default, `copy-headers` joined. Matches gap-review-merged.md:58. |
| F2 ST-SEC-03 sentence | Fixed | Rule change 2 (draft:171-175) replaces the whole "The one exception" sentence of `security.md:19-20`; reads correctly. ST-SEC-02 insertion reads correctly. |
| F3 no logging starter | Partly | ADR 8.1 (draft:139-140): no logging starter on the classpath = `uri` only, as decided 01:01Z. The switched-off case is open (F1 below). |
| M1 Status | Fixed | draft:3 "Accepted, 2026-11-09, by the owner". |
| M2 answer length | Fixed | Answers 1 to 4 are 3, 3, 2 and 3 lines (note:13-29). |
| M3 resilience.md | Fixed | Note:56-58 says `resilience.md:59-72` and `:192` are rewritten, and lists the other changed files. |

## Gap list

| Requirement | Result | Evidence |
|---|---|---|
| ADR in ADR 0010's format, linked from `docs/README.md` | MET | Status, Context, Decision, Consequences, Follow-up; README row named in note:4. |
| Records the owner's decisions (11-07 survey, pool, A1-A16, B2-B5, ADR open 1-2, ST-ENG-51 line, 00:54Z spans, 01:01Z call log) | PARTLY | All present; A7 wording narrowed (M2). |
| Three rule lines, exact text | MET | draft:166-179, consistent with ADR 2, 3.3, 4.2 and 8.1. |
| Rules hold, ST-SEC-03 as changed | NOT MET | F1. |
| Dependents (ST-AG-17) | MET | `git grep -n "ST-ENG-34\|ST-SEC-03\|ST-SEC-02\|ST-ENG-51" -- AGENTS.md docs/agents docs/adr` = 14, as note:29 says; `AGENTS.md:231` and `architecture.md:19` named. |
| Eight ST-AG-14 parameters | MET | draft:191-200. |
| Facts sourced (ST-AG-01) | MET | Spike facts match `spike-result.md`; span URL fact matches observability README:111. |
| Sizes (ST-AG-10) | MET | Note 59, draft 233 (limit 500). |
| Secrets (ST-SEC-01) | MET | Only `${...}` placeholders. |

## Findings

**F1. Blocking (ST-SEC-03, always blocking). The call log with the logging starter switched off logs the URL
unmasked.** ADR 8.1 (draft:139-140): "Without the logging starter on the classpath there is no redaction, so the
line carries the `uri` template only". With the logging starter present and `stratum.logging.enabled=false`, there is
also no redaction: `LoggingEnvironmentPostProcessor.java:61-63` returns at once, and the logging README says
"`stratum.logging.enabled=false` keeps Spring Boot's default plain-text logging" (:428) and "Logs are plain, unmasked
text" (:449). This is a real setup: README:448 tells a team that keeps its own log format to do exactly that, and
ADR 0001's company-framework service is a named case. ADR 8.1 then sends the real `url` (emails, query values) to a
plain log, which the new ST-SEC-03 text ("only after the logging starter's redaction") forbids. Also, ST-ENG-11 says
"A starter never changes its behaviour based on another starter's settings", so the ADR must say how the client
starter knows. **Fix:** in 8.1, "Without the logging starter, or with it switched off, the line carries the `uri`
template only", and one clause on how this is known within ST-ENG-11 (for example a signal through Spring that the
logging starter publishes), with the mechanism left to the call-log item's note. This is the plain reading of the
01:01Z decision ("no redaction, so `uri` only"); if the implementer reads it otherwise, one card to the owner.

**Minor**
- M1. note:1 still reads "Design note: ADR 0011, ... round 2" though the note now carries the round-2 answers.
  Fix: "round 3", or drop the round from the title.
- M2. ADR 4.2 (draft:105) "a dropped connection before any answer" is narrower than decision A7
  (gap-review-merged.md:50, "I/O-before-answer"; table row A7, "read timeout never retried on POST"), which implies a
  read timeout on GET, PUT or DELETE is retried. `design-v4.md:163-166` has the same gap. A reader of the ADR cannot
  tell whether a GET read timeout is retried. Fix: name it, e.g. "connect failure, an I/O error or read timeout
  before any answer (not on POST or PATCH), 502, ...".

## Planted faults

Not applicable to a text-only note. Checked by search and reading instead: the 14 rule dependents; the logging
switch-off path (`LoggingEnvironmentPostProcessor.java:61`, README:428, :448, :449); the span URL fact (observability
README:111); `Redactor` carries `@StratumInternal` (`Redactor.java:34`), so the client starter cannot use it
directly (ST-ENG-56), which is why F1 asks for the signal to be named.

## Notes (not findings)

- `retry-delay` jitter (ST-ENG-51 "backoff with jitter") is in `design-v4.md:18`, not in the ADR; the resilience
  item's brief must carry it, like the other design-v4-only details round 2 listed.
- judge.md "Rounds" 3 says no third round of one review step; the owner approved this one (gap-review-merged.md:67).

## Not verified

Library behaviour was taken from `spike-result.md`, not re-run. Owner decisions were checked against
`gap-review-merged.md` and `design-v4.md`, not the chat. Not merged; nothing posted on GitHub.
