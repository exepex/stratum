# Judge review

Part of the agent rules in [AGENTS.md](../../AGENTS.md); every agent follows them. Adapted from the judge process of
the Omenix F&O repository (`exepex/trading`, `docs/JUDGE.md`).

An agent can report work as finished when part of it is missing, untested or wrong. The judge exists to catch this
before merge. It is an independent agent that checks the work itself and accepts nothing it has not verified.

## Which changes need a judge

Every pull request needs a judge PASS before it merges. The only exception is a pull request that adds nothing but
a judge verdict file.

How the judge checks depends on the change:

- **Code, build or configuration** (Java, POMs, YAML and `.properties` files, CI, dependencies, samples): the judge
  builds the change, runs its tests and plants faults.
- **Text only** (Markdown, plain text, AsciiDoc): the judge reads every changed document against the code and the
  request. No build is needed.

## Who starts the judge, and when

1. The implementer finishes the work, pushes it, and completes the Claims in the pull request description at that
   head commit.
2. The implementer asks the coordinator to start the judge, or the owner when there is no coordinator. Until a PASS
   arrives, the implementer reports the work as "waiting for judge", never as done.
3. The coordinator (or the owner) starts the judge as a new agent session with no access to the implementer's
   conversation, using this prompt:

   > You are the independent judge for pull request #N in exepex/stratum. Follow `AGENTS.md` and
   > `docs/agents/judge.md` exactly. Verify every claim yourself; a claim you have not verified is NOT MET.
   > Write only your verdict file.

The implementer never starts its own judge and never judges its own work. An agent that took part in building the
change, or read the conversation in which it was built, cannot be its judge.

## The Claims

The pull request description contains a **Claims** section, written by the implementer and updated each round:

1. **The request.** The owner's request, quoted or linked.
2. **Requirements.** Every requirement in the request, one per line. Each is either **met**, with its evidence, or
   **out of scope**, with the reason and the owner's agreement if the owner decided it. Evidence is a `file:line`
   for the code, the test that proves it, and the command that was run with its result, for example
   `mvn -pl starters/stratum-starter-webmvc -am verify` → `BUILD SUCCESS, Tests run: 42, Failures: 0`.
3. **Broken-code proof.** For each requirement with code, the fault the implementer planted and the test that
   caught it.
4. **Not done or not verified.** Anything left open, stated plainly.
5. **Files read.** Confirmation that the implementer read `AGENTS.md` and all six topic files before starting.
6. **Line counts.** Each changed file with its line count.

A claim without evidence counts as not made.

## What the judge does

1. **Build the gap list first.** Read the owner's request and write your own list of every requirement in it,
   before reading the implementer's list. Then compare. A requirement that is neither met nor declared out of
   scope is a **blocking** finding. Something built that the request did not ask for is a **should fix** finding.
2. Read the whole diff (`git diff <base>..<head>`), each changed file in full where needed, and the callers, tests
   and documents the change affects.
3. Check each requirement yourself. For code, run the build and the tests, and any small check you need. A
   requirement is MET only with your own evidence (`file:line`, command output). If you could not verify it, it is
   NOT MET.
4. Look for what the Claims do not mention: bugs, edge cases (empty input, a property unset, an optional library
   missing, a timeout, concurrent calls, a service replacing the bean), missing or hollow tests, secrets or personal
   data in logs, documentation left out of date, dead code, files the request did not ask for.
5. **Plant faults.** For each important requirement, break the code on purpose (invert a condition, remove a bean,
   change a default) and check that a test fails. Restore the code afterwards. Record how many faults you planted
   and how many the tests caught.
6. Check the change against every rule: [AGENTS.md](../../AGENTS.md), [engineering](engineering.md),
   [testing](testing.md), [security](security.md), [documentation](documentation.md) and [review](review.md).
   Check the line count of every changed file against the limits in AGENTS.md.
7. Check simplicity in both directions. For each new type, method, option and branch, name its use today. One with
   no use, or Javadoc that explains internals or restates the code, is a should-fix finding (ST-ENG-05, ST-ENG-15).
   Then check that each changed class does one job, that no logic is copied within the change or from existing code
   in any module, that an existing helper or `testsupport` method was not bypassed, and that tests follow ST-TEST-13
   to ST-TEST-17. A class with a second job, copied logic or repeated test setup is a should-fix finding (ST-ENG-01,
   ST-ENG-06, ST-ENG-16 to ST-ENG-18).
8. Give the verdict: **PASS**, or **FAIL** with numbered findings, each **blocking**, **should fix** or **minor**
   (as defined in [review](review.md)), with evidence and the fix expected. A change passes only when no blocking
   or should-fix finding remains.

Judge honestly in both directions. Do not invent findings to look thorough, and do not pass work to be agreeable.

## The verdict file

The judge writes `reviews/judge/PR-<number>-<short-name>.md` and commits it to the pull request branch. This is the
judge's only change to the repository. If the judge can comment on the pull request, it also posts the verdict line
with a link to the file. The file follows this template and is at most 60 lines long:

1. **Title:** pull request number, branch, head commit. Then one line: PASS or FAIL.
2. **Gap list:** each requirement from the request, MET, PARTLY or NOT MET, with the judge's evidence in a few words.
3. **Findings** (FAIL only): numbered, with severity, evidence and the fix expected.
4. **Planted faults:** how many were planted, how many were caught, and which survived.
5. **Notes** that are not findings, if any.

Quote the implementer only where a claim is wrong. No praise and no summary of the change.

## Rounds

1. After a FAIL, the implementer fixes each finding and shows the proof (commit, test, command output), or answers it
   with evidence that the requirement is already met. Disagreement without evidence is not an answer.
2. The judge reviews once more, in a `## Round 2` section of the same file, at the new head commit. It accepts or
   rejects each fix and each answer with its own evidence. An answer is accepted only when the judge reproduces it.
3. If findings remain after two rounds, the owner decides. Both sides' evidence goes to the owner; there is no third
   round.

## Merging

A pull request merges only when the judge's PASS is for its current head commit. Any later commit other than the
verdict file needs the judge to confirm that the PASS still holds.
