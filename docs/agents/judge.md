# Judge review

Agents sometimes report work as finished when it is not: a test that was never run, a criterion half met, a file
left out. The judge exists to catch that. It is an **independent, honest reviewer** that trusts nothing it has not
checked itself. Adapted from the judge process in the Omenix F&O repository. Index: [AGENTS.md](../../AGENTS.md).

## Which changes need a judge

| Lane | Covers | Review before merge |
|---|---|---|
| 1 | Any Java code, POM, build, CI or dependency change; auto-configuration, defaults and `stratum.*` properties; security, secrets, logging and personal data; public API; a change that deletes, skips or loosens a test or a gate; a rule document change that loosens a rule or a hard line | A judge PASS |
| 2 | Documentation and rule documents that describe existing behaviour without changing it, samples, comments | A judge PASS, judged by reading only (no build) |
| 3 | Judge verdict files, typo fixes that change no meaning | None |

When unsure, it is lane 1. The builder states the lane in the pull request; the judge may move it up.

## Who the judge is

- A **separate session or agent** that did not build the change and shares no context with the builder. The builder
  never judges its own work, and a second session of the builder that read the build conversation is not
  independent.
- The judge **never writes or edits code, tests or documents** in the change. The only file it writes is its verdict.
- The judge does not merge. It gives PASS or FAIL with evidence.

To start a judge, open a fresh session on the pull request branch with this prompt:

> You are the independent judge for pull request #N in this repository. Follow `docs/agents/judge.md` exactly.
> Verify every claim yourself; unverified is NOT MET. Write only your verdict file.

## What the builder provides

The pull request description has a **Claims** section, updated each round:

- the lane, and the base and head commits the claims refer to;
- each acceptance criterion, with the code (`file:line`) and the test that proves it, and the command that was run
  with its result (for example `mvn -pl starters/stratum-starter-web -am verify`: `BUILD SUCCESS`, `Tests run: 42`);
- what the builder did **not** do or could not verify, stated plainly;
- for each criterion, which fault would make its test fail (the builder's own broken-code proof);
- a real output sample where it helps (a log line, an error response, generated metadata), with no secrets or
  personal data.

A claim without evidence counts as not made.

## What the judge does

1. Read the pull request, its Claims section and the issue or request behind it.
2. Read the whole diff (`git diff <base>..<head>`) and every changed file in full where needed, plus the callers,
   tests and documents the change affects.
3. For each claim and each acceptance criterion, find the code and the test, and **run what can be run yourself**:
   the build, the tests, the file-size check, small scripts. A criterion is MET only with your own evidence
   (`file:line`, command output). Unverified is NOT MET.
4. **Hunt for what was not claimed:** bugs, edge cases (empty input, missing property, missing optional library,
   timeouts, concurrency, a service overriding the bean), missing tests, tests that pass without testing the claim,
   secrets or personal data in logs, missing documentation updates, dead code.
5. **Plant faults.** Break the code behind each important criterion on purpose (invert a condition, remove a bean,
   change a default) and check that a test fails. Restore it afterwards. Record how many plants the tests caught.
6. Check the rules: [engineering](engineering.md), [testing](testing.md), [security](security.md),
   [documentation](documentation.md) and the file-size limits in AGENTS.md.
7. Give the verdict: **PASS**, or **FAIL** with numbered findings, each marked **blocking**, **should fix** or
   **minor**, with its evidence and the fix expected. A change passes only with no blocking and no should-fix
   findings left.

Be honest in both directions: do not invent findings to look thorough, and do not pass work to be agreeable.

## The verdict file

Written to `reviews/judge/PR-<number>-<short-name>.md` and committed to the pull request branch (the judge's only
commit). If the judge can comment on the pull request, it also posts the verdict line and a link to the file.
Fixed template, at most about 60 lines:

1. Title: pull request, branch, head commit. One line: **PASS** or **FAIL**, lane.
2. Criteria table: one row per criterion: MET / PARTLY / NOT MET, with the judge's evidence in a few words.
3. Findings (FAIL only): numbered, severity, evidence, fix expected.
4. Plants: how many planted, how many caught, and any survivor.
5. Notes that are not findings, if any.

Quote a builder claim only when it is wrong. No praise and no restating the change.

## Rounds: at most two

1. On a FAIL, the builder fixes each finding with its proof (commit, test, command output), or pushes back with
   evidence that the criterion is already met. "I disagree" without evidence is not a pushback.
2. The judge re-judges once, in a `## Round 2` section of the same file, at the new head commit. Each fix or pushback
   is accepted or rejected with the judge's own evidence. A pushback is accepted only when the judge reproduces it.
3. **After two rounds the owner decides.** Both sides' evidence goes to the owner; there is no third round.

## Merging

A lane 1 or 2 pull request merges only when the judge's PASS is at the current head commit and the build is green.
Any new commit after the PASS, other than the verdict file itself, needs the judge to confirm the PASS still holds.
