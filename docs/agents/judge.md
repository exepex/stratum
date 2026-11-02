# Judge review

Part of the agent rules in [AGENTS.md](../../AGENTS.md); every agent follows them. Adapted from the judge process of
the Omenix F&O repository (`exepex/trading`, `docs/JUDGE.md`).

An agent can report work as finished when part of it is missing, untested or wrong. The judge exists to catch this
before merge. It is an independent agent that checks the work itself and accepts nothing it has not verified.

## Which changes need a judge

Every pull request needs a judge PASS before it merges. The track of the change (ST-AG-15) sets how many reviews it
gets ("The judge pool") and which steps each review checks ("What the judge does").

## The judge pool

- Each review is done by a fresh judge agent, started with the same judge prompt and the same checklist.
- The number of reviews depends on the track:

  | Track | Reviews |
  |---|---|
  | Docs | 1: a judge reads the changed text |
  | Fix | 1: a judge checks the code |
  | Feature | 2: a judge checks the design note before any code is written; then a judge checks the code, and receives the approved note and the first verdict |

- The reviews of one change rotate between models: each review runs on a different model from the one before it.
- One judge per review step. Several judges work on the same step in parallel only when the owner asks.
- A judge never reviews work it helped build, and never reads the conversation in which the work was built.

## Who starts the judge, and when

1. The implementer finishes the work and its self-review (ST-AG-17), pushes it, and completes the Claims in the pull
   request description at that head commit. For the design-note review on Feature, the implementer gives the note to
   the coordinator instead, who posts it in the one place the owner reads (ST-AG-16).
2. The implementer asks the coordinator to start the judge. Until a PASS arrives, the implementer reports the work as
   "waiting for judge", never as done, and pushes nothing.
3. The coordinator starts the judge as a new agent session with no access to the implementer's conversation, using
   this prompt:

   > You are the independent judge for <pull request #N | the design note of <item>, posted at <link>> in
   > exepex/stratum, on the <Docs | Fix | Feature> track. Follow `AGENTS.md` and `docs/agents/judge.md` exactly.
   > Verify every claim yourself; a claim you have not verified is NOT MET. Post your verdict as one comment there
   > and change nothing else. If you cannot post a comment, give the verdict to the coordinator, who posts it
   > unchanged.

   For every review, the coordinator adds the owner's request, the owner's decisions from the implementer's brief
   and any judge check written under ST-AG-19. For the code review on Feature, it also adds the links to the
   approved note and to the first verdict. It adds nothing else.

When there is no coordinator, the implementer asks the owner for the judge, and the owner starts it with this
prompt, filled in the same way, with "the owner" in place of "the coordinator". The implementer may do the other
coordinator duties, such as the track and the brief, but it never starts or chooses its own judge. The implementer
never judges its own work.

## The implementer brief

The coordinator starts every implementer with this template, filled in (ST-AG-18). It cites rules; it never restates
or replaces them.

> You are the implementer for <item> in exepex/stratum, on the <Docs | Fix | Feature> track. The owner's request:
> "<quote or link>". Read `AGENTS.md` and every file in `docs/agents/` in full, and follow them. The rules this item
> touches most: <rule IDs>. The owner's decisions not yet written into a rule (ST-AG-13): <each decision with its
> date, or none>. Checks added after earlier misses (ST-AG-19): <each, or none>. Answer design-note questions
> <1, 2 and 4 | 1 to 4 | none> before any code (ST-AG-16), review your
> own change (ST-AG-17), write the Claims and ask the coordinator for the judge. No branch, commit or push before the
> owner's go (ST-AG-11).

## The Claims

The pull request description contains a **Claims** section, written by the implementer and updated each round:

1. **The request.** The owner's request, quoted or linked, and the track of the change.
2. **Requirements.** Every requirement in the request, one per line. Each is either **met**, with its evidence, or
   **out of scope**, with the reason and the owner's agreement if the owner decided it. Evidence is a `file:line`
   for the code, the test that proves it, and the command that was run with its result, for example
   `mvn -pl starters/stratum-starter-webmvc -am verify` → `BUILD SUCCESS, Tests run: 42, Failures: 0`.
3. **Design note.** On Fix and Feature, the answers to the design note below. On Feature, also the link to the
   judge's PASS of the note.
4. **Broken-code proof.** For each requirement with code, the fault the implementer planted and the test that
   caught it.
5. **Self-review.** The evidence of ST-AG-17: each search command with its number of hits, the most-broken rules
   checked, the rebase and the CI and Sonar results at the head, and each class or file added to fix a finding
   (ST-AG-12), with that finding.
6. **Not done or not verified.** Anything left open, stated plainly.
7. **Files read.** Confirmation that the implementer read `AGENTS.md` and all six topic files before starting.
8. **Line counts.** Each changed file with its line count.

A claim without evidence counts as not made.

## The design note

The implementer answers these questions before any code (ST-AG-16). Fix answers questions 1, 2 and 4, Feature
answers all four, and Docs has no note. Tests are not a question here: ST-TEST-10 to ST-TEST-34 decide them.

Each answer is at most 3 lines and gives concrete facts. "None" is an answer only with the fact that makes it so,
such as "no new key: the change adds no field to the settings record". The judge fails a "none" that the diff
contradicts.

1. **Who uses it, and does anything change for them?** The consumers; a new, an existing and a hybrid service (old
   endpoints kept, new ones on Stratum); how a service adopts it, with Stratum as its parent or next to a company
   framework (ADR 0001); each change a consumer sees, or none with the test that proves it; which new types are API
   and which carry `@StratumInternal` (ST-ENG-56); the lines a service writes to use it.
2. **How is it built, and why this way?** What Stratum, Spring Boot or a library already provides, and whether it is
   reused; the simplest way and one alternative, with why it was not chosen; the existing class it is modelled on;
   its module and package, and why no package cycle results (ST-ENG-57); each key with its default and a real case
   that changes it, a Spring Boot key first, and the Java customizer (ST-ENG-34).
3. **Is it safe and fast?** The eight parameters of ST-AG-14; the hostile inputs, each written as a test; for web
   code, the table of cases, each tested on both stacks (ADR 0006); each thread hop and how the logging and tracing
   context moves with it (ST-ENG-53); for code that parses, matches a regex or masks, the test with a large input;
   for a matching rule that replaces another, which inputs the old rule matched that the new one does not.
4. **Which rules and owner decisions does it follow?** The rule IDs it touches; each owner decision with the test
   that protects it; each Spring Boot or library fact with its source; the documents it updates.

## What the judge does

One judge works through this checklist in order. It collects every finding before it gives the verdict, so that the
implementer can fix them all in one round. The track sets the steps:

| Review | Steps |
|---|---|
| Docs | 1, 2, 3 and 5 |
| Fix | 1 to 5 |
| Feature, design note | 1, 2, 3 and 5, applied to the note |
| Feature, code | 1 to 5 |

When every changed file is Markdown, text or AsciiDoc, as for a change to the agent rules or an ADR, the judge
skips step 4: a text-only change needs no build (AGENTS.md, Build).

1. **Gate.** The Claims are complete, with the design note on Fix and Feature; for a design-note review, the note is
   complete. For a pull request, it is open ready for review, not as a draft; CI is green at the head commit; and
   every Codex and Sonar finding is answered and its thread resolved. Where the answer says the finding does not
   apply (ST-AG-04), the judge accepts or rejects it with its own evidence. The judge reads Sonar through the pull
   request's GitHub checks. When the gate fails, the verdict is FAIL with that one finding, and the judge checks
   nothing else. A missing design note on Fix or Feature is a blocking finding that fails the gate.
2. **Requirements and owner decisions.**
   - **Build the gap list first.** Read the owner's request and write your own list of every requirement in it,
     before reading the implementer's list. Then compare. A requirement that is neither met nor declared out of
     scope is a **blocking** finding. Something built that the request did not ask for is a **should fix** finding.
   - Read the whole diff (`git diff <base>..<head>`), each changed file in full where needed, and the callers, tests
     and documents the change affects.
   - Check each requirement and each owner decision in the brief yourself. A requirement is MET only with your own
     evidence (`file:line`, command output). If you could not verify it, it is NOT MET.
3. **Design, rules and documents.**
   - The code matches the design note.
   - Check the change against every rule: [AGENTS.md](../../AGENTS.md), [engineering](engineering.md) (ST-ENG-01 to
     ST-ENG-64), [testing](testing.md), [documentation](documentation.md) and [review](review.md). Check the line
     count of every changed file against the limits in AGENTS.md.
   - Check simplicity in both directions. For each new type, method, option and branch, name its use today. One
     with no use, or Javadoc that explains internals or restates the code, is a should-fix finding (ST-ENG-05,
     ST-ENG-15). Each class or file added to fix a finding must be the simplest fix (ST-AG-12).
   - Check that each changed class does one job, that no logic is copied within the change or from existing code in
     any module, that an existing helper or `testsupport` method was not bypassed, and that tests follow ST-TEST-13
     to ST-TEST-17. A class with a second job, copied logic or repeated test setup is a should-fix finding
     (ST-ENG-01, ST-ENG-06, ST-ENG-16 to ST-ENG-18).
   - Read each changed document against the code at the head commit, and check each finding that documentation's
     "How documentation is checked" lists (ST-DOC-05 and the other documentation rules).
   - For a change to a rule or an ADR, check that every file that states or depends on that rule was updated
     (ST-AG-17).
4. **Behaviour.**
   - Run the build and the tests, and any small check or probe you need.
   - Look for what the Claims do not mention: bugs, edge cases (empty input, a property unset, an optional library
     missing, a timeout, concurrent calls, a service replacing the bean), missing or hollow tests, documentation
     left out of date, dead code, files the request did not ask for.
   - When the change replaces a rule that matches names, keys or text, run every input the old rule's tests covered
     against the new rule, including hostile casing such as `AUTHORIZATION` or `X-Api-KEY`.
   - **Plant faults.** For each important requirement, break the code on purpose (invert a condition, remove a bean,
     change a default) and check that a test fails. Restore the code afterwards. Record how many faults you planted
     and how many the tests caught.
5. **Security.** Check ST-SEC-01 to ST-SEC-06 on every change, and each other rule in [security](security.md) that
   the change touches. Only when the change is on the trigger list (ST-AG-15), also follow each untrusted value from
   where it enters to where it is used, and run its masking checks when the change touches masking or logged data,
   as security's "How to check a change" describes.

Then give the verdict: **PASS**, or **FAIL** with numbered findings, each **blocking**, **should fix** or **minor**
(as defined in [review](review.md)), with evidence and the fix expected. A change passes only when no finding of any
severity remains, apart from one the owner has accepted or the judge has deferred to a GitHub issue.

Judge honestly in both directions. Do not invent findings to look thorough, and do not pass work to be agreeable.

### The finding bar

- A behaviour finding comes with a failing test that the judge ran: the test, or its steps, and the wrong result.
- A structure, naming or documentation finding quotes the exact lines.
- A case that is only theoretical is a Note, not a finding, and is never built.

### Deferring a finding

Only the judge may move a real finding that is not blocking to a GitHub issue, when its fix is large compared with
its harm. The verdict says so, and the coordinator opens the issue and tells the owner in one line. The owner may
object; the finding is then fixed in the pull request. The implementer never defers a finding.
A deferred finding does not hold the PASS. If the owner objects, the PASS waits until the finding is fixed.

## The verdict

The judge posts its verdict as one comment: on the pull request, or, for a design-note review, where the note was
posted. It never writes the verdict as a file in the repository, and changes nothing else. The comment follows this
template. It is at most about 100 lines, so that every finding fits in one comment: each gap-list item takes one
line and each finding at most three.

1. **Title:** pull request number or design note, branch, head commit, track and review step. Then one line: PASS
   or FAIL.
2. **Gap list:** each requirement from the request, MET, PARTLY or NOT MET, with the judge's evidence in a few words.
3. **Findings** (FAIL only): numbered, with severity, evidence and the fix expected.
4. **Planted faults:** how many were planted, how many were caught, and which survived.
5. **Notes**, if any: only cases that are theoretical ([the finding bar](#the-finding-bar)); a real defect of any
   size is a numbered finding.

Quote the implementer only where a claim is wrong. No praise and no summary of the change.

## Rounds

1. After a FAIL, the implementer fixes each finding and shows the proof (commit, test, command output), or answers it
   with evidence that the requirement is already met. Disagreement without evidence is not an answer.
2. A fresh judge for the same review step, given the earlier verdict, reviews once more at the new head commit, in a
   new comment. It accepts or rejects each fix and each answer with its own evidence. An answer is accepted only when
   the judge reproduces it.
3. If findings remain after two rounds of one review step, the owner decides. Both sides' evidence goes to the
   owner; there is no third round, and the coordinator starts no judge on a new head commit until the owner has
   decided.
4. A FAIL at the gate does not count as a round. Once the gate's finding is fixed, the next review is round 1.

## Merging

The owner merges a pull request only when the judge's PASS is for its current head commit. Any later commit needs
the judge to confirm that the PASS still holds. The coordinator says "Ready to merge at <sha>" as AGENTS.md
describes under "Git and pull requests".
