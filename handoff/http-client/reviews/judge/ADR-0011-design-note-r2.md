# Judge verdict: design note of ADR 0011 (HTTP client starters), Feature track, review steps 1, 2, 3 and 5, round 2

Note: `design/http-client-libraries/adr-0011-design-note.md` (4208 bytes, 00:53Z). Sources: `adr-0011-draft.md`
(00:52Z), `design-v4.md`, `gap-review-merged.md` "Decisions", `spike-result.md`. Round 1: `ADR-0011-design-note.md`.
Repo at `origin/main` 8b111f3; no branch or PR exists. Fresh judge, different model from round 1.

**FAIL.** Three should-fix findings and three minor ones, all text fixes of one to three lines. No owner decision is
reopened. All nine round-1 findings are fixed.

I read AGENTS.md and all six topic files. Text-only item, so step 4 (build) is skipped. Nothing was changed in the repo.

## Round-1 fixes, verified

| Finding | Result | My evidence |
|---|---|---|
| F1 Java path vs ST-ENG-34/24 | Fixed | ADR 1.4: `HttpClientPropertiesCustomizer` in `spring.factories`, wins over YAML; `StratumClientCustomizer` bean only for what has no key. `design-v4.md` "Java" says the same. Note §2 too. |
| F2 ST-SEC-17 | Fixed | ADR 3.5 last two sentences: the service checks its own allow-list; Stratum does not enforce it. |
| F3 ST-ENG-51 wording | Fixed | Rule change 3: "never sent (a connect failure) ... retried once ... that is not a retried write". Matches ADR 4.2. |
| F4 Status line | Fixed | Status names three rule changes and the `resilience-policy` replacement. Acceptance = merge (see M1). |
| F5 dependents | Fixed | `git grep -n "ST-ENG-34\|ST-SEC-03\|ST-SEC-02\|ST-ENG-51" -- AGENTS.md docs/agents docs/adr` = 14 hits; the note's 4 changed + 10 unchanged match them line by line. ADR 4.1 covers the resilience starter; `architecture.md:19` listed; ADR 8.2 covers the span URL. |
| F6 Not verified | Fixed | ADR "Not verified" lists the reactive stack and Apache's retry on 429. |
| M1, M2, M3 | Fixed / partly / fixed | Answers shorter (still over 3 lines, see M2); "19 keys, `url` or `urls` counted as one" (I count 19 in ADR 2.4 and the design-v4 table); alternative = Boot's keys. |

## Gap list

| Requirement | Result | Evidence |
|---|---|---|
| ADR in ADR 0010's format, linked from `docs/README.md` | PARTLY | Headings match ADR 0010. Status line format differs (M1). `docs/README.md:111` is the row to extend. |
| Records the owner's decisions (survey 11-07, pool 11-08, gaps A1-A16 and B2-B5, ADR open 1-2, ST-ENG-51 line) | PARTLY | Each appears in the ADR or design-v4, except A15's merge rule, which ADR 2.1 contradicts (F1). |
| Three rule lines, ST-ENG-34, ST-SEC-03 with ST-SEC-02, ST-ENG-51 | PARTLY | Present and approved (00:24Z, 00:46Z, 00:48Z). ST-SEC-03 text as quoted yields a contradictory sentence (F2). |
| Consistent with the other rules | PARTLY | ST-ENG-10, -11, -24, -25, -27, -34, -50, -52, ST-SEC-01, -05, -06, -17 hold. ST-SEC-03 without the logging starter is not covered (F3). |
| Dependent files found and listed with a reason (ST-AG-17) | MET | 14 hits verified; `resilience.md`, `http-client.md`, `architecture.md`, `AGENTS.md:231` named. |
| Eight ST-AG-14 parameters | MET | All eight in Consequences. |
| Library facts sourced or marked (ST-AG-01) | MET | Spike facts match `spike-result.md`; OpenFeign, Resilience4j 2.4 and Spring Cloud 2025.1 are in `survey.md:32,36,51,68`. |
| Size limits (ST-AG-10, ST-DOC-09) | MET | Draft 228; `engineering.md` 336, `security.md` 81, `AGENTS.md` 287, `architecture.md` 207, `docs/README.md` 128, `resilience.md` 202, `http-client.md` 217, each plus a few lines. |
| Nothing beyond the request | MET | Every decision traces to an owner decision; no new key or mechanism. |
| Secrets (ST-SEC-01) | MET | Examples use `${...}` placeholders only. |

## Findings

**F1. Should fix. ADR 2.1 contradicts decision A15 (gap 17, agreed 00:29Z).** ADR 2.1: "Shared values live under
`stratum.http-client.defaults.*`, and the client's value wins." For `headers` the decision is: client headers add to
`defaults.headers`, the client wins per name, an empty value drops a default; `copy-headers` lists are joined.
`design-v4.md` "Auth and headers" has it; the ADR does not, and 2.1 as written means the client map replaces the
default map. Fix: one sentence in 2.1 or 3.4 with the merge rule.

**F2. Should fix. The ST-SEC-03 text as quoted breaks the sentence it joins.** `security.md:19-20` reads "The one
exception: the web module logs the client's IP address (`clientIp`) for security use, and no other personal data."
Appending "and the HTTP client starters log the called URL ..." gives two exceptions under "The one exception" and
puts the new one right after "no other personal data". Fix: quote the whole replacement sentence in rule change 2,
for example "Two exceptions: the web module logs the client's IP address (`clientIp`) for security use; and the HTTP
client starters log the called URL with its path and query values in the call log line, after the logging starter's
redaction (ADR 0011). The documentation tells teams never to put personal data in a URL."

**F3. Should fix. The call log without the logging starter is not decided.** ADR 8.1 and rule change 2 rest on "the
logging starter's always-on redaction runs over it". The redaction lives in `stratum-starter-logging`
(`redaction/KeyValuePairMasker.java`, `SensitiveFieldNames.java`); no starter depends on another (ST-ENG-10), so a
service with the client starter and without the logging starter logs the real URL unredacted, which no rule allows.
A real setup: the starters are optional and the bundles are not required. Fix: one sentence in 8.1 saying what the
line carries then. Simplest reading of the owner's decision: the `url` field is logged only when the logging starter
is on the classpath, otherwise the line has the `uri` template alone; the documentation says so. If the implementer
reads it otherwise, one card to the owner (ST-AG-12: it sets a default).

**Minor**
- M1. Status: "Accepted by the owner when this record merges." ADR 0010 reads "Accepted, 2026-11-03, by the owner."
  An accepted record is never edited (documentation.md), so write the finished fact: "Accepted, 2026-11-09, by the
  owner." The go of 00:46Z on the ADR and its three rule texts is that acceptance; the merge records it.
- M2. judge.md "The design note": each answer at most 3 lines. Answers 2 and 4 run 6 and 10 lines; the dependent-rule
  block can point to the Claims.
- M3. `docs/modules/resilience.md:192` says `stratum.resilience.enabled=false` makes HTTP clients single-attempt, and
  59-72 shows `resilience-policy` on a client. ADR 4.1 says the opposite. A pointer line beside text that still states
  the old behaviour leaves the page describing it (ST-DOC-01); change or remove those lines, with the pointer.

## Planted faults

Not applicable to a text-only note. Checked by search instead: the 14 dependents (above); `git grep "resilience-policy"`
hits `docs/modules/resilience.md` and `docs/modules/http-client.md` only; `SensitiveFieldNames` lists `token`,
`password` and the API-key names, so the query-name masking the ADR relies on exists when the logging starter is on.

## Notes (not findings)

- Decisions carried by `design-v4.md` and the brief, not by the ADR: the ~100 ms floor for a new attempt (A8), the
  second 401 logged once per client per minute and the relay 401 passed back (B3), a failed address in `urls` skipped
  for a short time, `copy-headers` with nothing to copy (A9). The ADR is a decision record; the H1 brief must carry them.
- The owner decided at 00:54Z that the observability starter masks query values on client span URLs, as a separate
  item; ADR 8.2 gets a pointer after this verdict, as planned. Not counted against the note.
- ADR 2.2 stops startup on a Boot `spring.http.serviceclient.<name>.*` key: within the 11-06 boot-check boundary
  (reads configuration only) and ST-ENG-35 (a mistake, not a choice).

## Not verified

Library behaviour was taken from `spike-result.md`, not re-run. Owner decisions were checked against
`gap-review-merged.md` and `design-v4.md`, not the chat. Not merged; nothing posted on GitHub.
