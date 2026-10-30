# Code review

Part of the agent rules in [AGENTS.md](../../AGENTS.md). It applies to any agent asked to review a change, including
the security role. The judge also uses the severity scale below. Adapted from the CodeGuard AI review method.

A reviewer reads and reports. It does not edit the change.

## Severity

Every finding, from a reviewer, the security role or the judge, uses one of three severities:

| Severity | Meaning | Effect |
|---|---|---|
| Blocking | Wrong behaviour, a security flaw, a broken rule in AGENTS.md, or a requirement not met | The change does not merge until it is fixed |
| Should fix | A real defect or rule break with limited impact, such as a missing test for an edge case | Fixed before merge, unless the owner accepts it |
| Minor | Clarity or style that no tool checks | Fixed if cheap; never holds a merge |

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
6. Keep a finding only when you can name the input or state that triggers it and the wrong result it causes, or, for
   structure, the lines that do a second job or are copied. Drop anything you cannot show.

## Reporting

One line per finding, most severe first:

`path:line` — **severity** RULE-ID: the problem. **Fix:** what to change.

No praise and no restating the change. End with a one-line verdict: "no blocking findings" or the number of
blocking findings.
