# Design note: ADR 0011, HTTP client starters (Feature track), round 3

**Item:**
- `docs/adr/0011-http-client-starters.md` (source: `adr-0011-draft.md`) and its row in `docs/README.md`.
- Three rule lines the owner approved on 2026-11-09: ST-ENG-34 (00:46Z), ST-SEC-03 with ST-SEC-02 (00:24Z) and
  ST-ENG-51 (00:48Z).

It is text only. The go was given at 00:46Z, on the card "Open the ADR 0011 pull request". The ADR counts as
accepted when the owner merges it, which is what its Status line says. The round 1 verdict is
`reviews/judge/ADR-0011-design-note.md`; each finding is answered at the end.

## 1. Who uses it, and does anything change for them?
Readers are service teams calling other services, and the agents that build the starters. No service runs on Stratum,
and the starter is "Planned" (`docs/modules/http-client.md:3`). Nothing changes at run time: no class, key or
default is built. API versus `@StratumInternal` for the named types is decided in the H1 rework's note.

## 2. How is it built, and why this way?
It follows ADR 0010's format. The alternative not chosen is to keep Boot's `spring.http.serviceclient.*` keys, as
PR #79 does; the spike showed they are ignored once Stratum sets the factory. There are 19 keys. Java goes through
`HttpClientPropertiesCustomizer` (ST-ENG-34), and a `StratumClientCustomizer` bean holds only what has no key.

## 3. Is it safe and fast?
The ADR's Consequences weigh all eight ST-AG-14 parameters. The security choices (no host check, the call-log URL,
the 401 resend) are named with their rules: ST-SEC-17, ST-SEC-03 and ST-ENG-51. No input is parsed: nothing is built.

## 4. Which rules and owner decisions does it follow?
ST-ENG-10, -11, -24, -34 and -51; ST-SEC-02, -03 and -17; ST-DOC-01 and -08; ST-AG-10 and -13. The decisions are
those in `gap-review-merged.md` and `design-v4.md`, and the facts come from `spike-result.md`. The documents changed
and the 14-hit dependent-rule search (4 changed, 10 cite the rule by ID only) go in the Claims.

## Round 1 findings
- **F1, accepted:** ADR 1.4 and `design-v4.md` now route Java settings through `HttpClientPropertiesCustomizer`.
- **F2, accepted:** ADR 3.5 states the ST-SEC-17 duty of the service.
- **F3, accepted:** ST-ENG-51 now reads "never sent ... or rejected before any work (401, retried once)".
- **F4, accepted:** the Status line now reads "Accepted by the owner when this record merges", with three rule
  changes named.
- **F5, accepted:**
  - the count is now 14;
  - the resilience starter is in ADR 4.1 (no dependency, its switch has no effect, `resilience-policy` dropped)
    and in Status;
  - `architecture.md` is added;
  - the span URL attribute is in ADR 8.2: it stays as the observability starter keeps it, and the ST-SEC-03 line
    covers only the call log. Whether that span attribute needs its own ST-SEC-03 line is raised to the owner,
    not decided here.
- **F6, accepted:** the reactive stack and Apache's own retry on 429 are added to Not verified.
- **M1, accepted:** shorter answers.
- **M2, accepted:** "19 keys, `url` or `urls` counted as one".
- **M3, accepted:** the alternative is now Boot's keys.

## Round 2 findings
- **F1, accepted:** ADR 2.1 now has the merge rule for `headers` and `copy-headers` (A15).
- **F2, accepted:** rule change 2 quotes the whole new ST-SEC-03 sentence ("Two exceptions: ...").
- **F3, accepted, owner confirmed 01:01Z:** without the logging starter the line carries `uri` only.
- **M1, accepted:** Status reads "Accepted, 2026-11-09, by the owner".
- **M2, accepted:** the answers are shorter, and the file list and the search moved to the Claims.
- **M3, accepted:** `docs/modules/resilience.md:59-72` and `:192` are rewritten in the PR, not only given a
  pointer. The PR also changes `docs/modules/http-client.md` (a pointer), the ADR, `docs/README.md`,
  `engineering.md`, `security.md`, `AGENTS.md:231` and `docs/guides/architecture.md:19`.
- **Also:** ADR 8.2 now points to the owner's 00:54Z span decision (a separate observability item).

## Round 3 findings
- **Blocking, accepted:** ADR 8.1 now says the call log carries `uri` only when the logging starter is absent
  or switched off. The signal must keep ST-ENG-11: no reading of the logging starter's settings and no import of
  its classes. The call-log item's note picks it; the candidate is a marker bean type in `stratum-core`.
- **Minor, accepted:** the title now says round 3.
- **Minor, accepted:** ADR 4.2 now says a read timeout is retried on GET, PUT and DELETE, and never on POST or
  PATCH. This follows A7 ("read timeout never retried on POST") and ST-ENG-51 ("timeouts" are transient).
