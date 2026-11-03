# Code review

Part of the agent rules in [AGENTS.md](../../AGENTS.md). It applies to any agent asked to review a change, including
the security role. The judge also uses the severity scale below. Adapted from the CodeGuard AI review method.

A reviewer reads and reports. It does not edit the change.

## Severity

Every finding, from a reviewer, the security role or the judge, uses one of three severities:

| Severity | Meaning | Effect |
|---|---|---|
| Blocking | Wrong behaviour, a security flaw, a broken rule in AGENTS.md, or a requirement not met | The change does not merge until it is fixed |
| Should fix | A real defect or rule break with limited impact, such as a missing test for an edge case | Fixed before merge, unless the owner accepts it. Only the judge may move a real should-fix or minor finding to a GitHub issue, when its fix is large compared with its harm; the owner is told in one line and may object. The implementer cannot defer it ([judge](judge.md#deferring-a-finding)) |
| Minor | Clarity or style that no tool checks | Same as should fix |

## What to review

- Only the lines the change adds or alters. Read surrounding code, callers, tests and configuration as evidence, but
  attach each finding to a changed line.
- Skip anything the build already enforces (formatting, Checkstyle, PMD, SpotBugs) unless the change weakens that
  check.
- In this order: security and correctness, then design, then duplication and reuse, then tests, then the rules in
  [engineering](engineering.md), then naming.

## How to review

1. Work out what the change is meant to do, from the request, the Claims and the tests.
2. List what it affects: callers, beans, properties, defaults, headers, metrics, error responses, documentation.
3. Run concrete cases through it: normal, empty, invalid, minimum and maximum, duplicate, concurrent, timed out, a
   dependency down, a service replacing the bean, a property unset.
4. For Spring code, check proxies (`@Transactional`, `@Async` and caching do not apply to calls within the same
   class), bean scope and shared state, and thread safety under virtual threads.
5. Check structure: each changed class does one job, no logic is copied from elsewhere, existing helpers and
   `testsupport` methods are used, and a design pattern is used where it would remove branching or copying
   (ST-ENG-06, ST-ENG-07, ST-ENG-16 to ST-ENG-18, ST-TEST-13 to ST-TEST-17). Name the second job or the copied lines.
6. Keep a finding only when it meets the finding bar in [judge](judge.md#the-finding-bar). A behaviour finding comes
   with a failing test or a reproduced wrong result. A structure, naming or documentation finding quotes the exact
   lines. A case that is only theoretical is a Note, not a finding, and is never built.

## Reporting

One line per finding, most severe first:

`path:line` — **severity** RULE-ID: the problem. **Fix:** what to change.

No praise and no restating the change. End with a one-line verdict: "no open findings" or the number of open
findings of each severity.

## Auditing a module

Before a module's gaps are planned, one reviewer audits the whole module, not only the lines a change adds or alters,
against this checklist (ST-AG-20). For each line, give `file:line`, a command and its output, a probe, or the parked
issue. Mark each line met, missing or parked. Do not build the scope from earlier findings.

- **A. Capabilities.** List what a service at high volume needs from the module. Compare each with Spring Boot 4.1 and
  the libraries it builds on, and record what the module adds, reuses or lacks, with the reason.
- **B. Whole bounds** (ST-ENG-52): every collection, size and count, for each part and for the whole output; one probe
  of the worst case for each.
- **C. Hostile input** (design-note question 3 in [judge](judge.md#the-design-note)): every value from outside that the
  module reads. Measure its worst-case cost and size, and check that the guard test fails on a 10× slowdown.
- **D. Volume and back pressure:** what happens when a downstream system is down or slow? Check the queue limit, what is
  dropped, whether the drops are counted, and whether any request thread blocks (ST-ENG-53, ST-ENG-54).
- **E. Parity:** MVC and WebFlux (ADR 0006), virtual threads, `@Async`, executors, Reactor, and each backend the page
  names. A missing feature is stated on the module page with its issue (ST-DOC-08).
- **F. Secrets and personal data** (ST-SEC-02, ST-SEC-03), checked by a probe.
- **G. Promises:** every "always", "never", "every", "only" and "cannot" on the module page and in `docs/`, checked by a
  probe or a named test, and searched across pages ([documentation](documentation.md#how-documentation-is-checked)).
- **H. Switches and replacement:** the module off (ADR 0010), each bean backing off, the Spring Boot keys used instead
  of new keys (ST-ENG-34).
- **I. Parked issues:** each open issue of the module, with its measured risk.
- **J. Checkable rules:** run the most-broken list of ST-AG-17 on the module's public nested types, records with three
  or more fields, and lambdas.
- **K. Facts:** each Spring Boot or library fact the audit states, with its jar or document (ST-AG-01).
