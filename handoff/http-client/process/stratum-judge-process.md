# Stratum: how the judge reviews a pull request

Written 2026-11-02 for a new session in another account that has none of this project's history. It describes
what the judge actually did in this project, not only what the rules say. Main is at `7b65c52`.

**Sources.** `AGENTS.md` and `docs/agents/judge.md` and `review.md` on main; the judge threads for PRs #86, #95,
#98, #105 and #106 read in full; the GitHub comments and reviews on those five PRs; the verdict files
`reviews/judge/PR-26`, `-86`, `-95`, `-98`, `-102`, `-105`, `-106` in the shared folder; `handoff/stratum-lessons.md`;
the project memory.

**Marks.** *(inferred)* means I did not see it directly. Everything else has a source named next to it.

---

## 1. Correction first: where the judge's verdict actually goes

The owner's message says the judge "does not write any file" and "responds back to the comments on the PR". The
record shows something different:

- **The judge never posted on GitHub.** PRs #86, #95, #98, #105 and #106 have no judge comment and no judge review.
  The only comments come from the Codex bot (`chatgpt-codex-connector[bot]`), SonarCloud (`sonarqubecloud[bot]`)
  and inline replies to Codex findings posted under `contactexepex`. Those replies say "Accepted and fixed in
  7e6fc9e" and similar, so the implementer wrote them, not the judge.
- **The judge writes exactly one file:** its verdict, `/mnt/project-files/reviews/judge/PR-<n>-<short-name>.md`, in
  the project's shared folder. It attaches that file to its thread and updates it each round.
- **The verdict is never committed.** `git log --all -- 'reviews/*'` returns nothing, and main has no `reviews/`
  folder.
- **The judge reports in three places:** a short reply in its own thread, its findings sent to the implementer's
  thread *(inferred: the #86 judge wrote "I've sent the finding to the implementer thread")*, and the coordinator,
  which reads the judge's reply and posts it in the project chat.

This does not match `judge.md` ("commits it to the pull request branch", "posts the verdict line" on the PR) and
it is listed as a known weakness in section 14.

---

## 2. The rules in the repo

| Where | What it says about the judge |
|---|---|
| `AGENTS.md` "Roles" | The judge verifies a finished change on its own and writes only its verdict. It can never hold another role on the same change. Every agent reads `AGENTS.md` and all six topic files and says so in its report. |
| `AGENTS.md` ST-AG-01, 02 | Report only what you verified. Work in a PR is not done until the judge has passed it. |
| `AGENTS.md` ST-AG-10 | 400 lines for code and config, 500 for text. "The judge checks the counts." |
| `AGENTS.md` "Git and pull requests" | The PR body holds the Claims. A PASS at the current head is needed before merge. The coordinator starts the judge; the implementer never judges its own work. |
| `docs/agents/judge.md` | The whole process: when a judge is needed, the Claims, the 8 steps, the verdict file, rounds and merging. |
| `docs/agents/review.md` | The three severities and the review order. |
| `docs/agents/testing.md` ST-TEST-01 | The implementer breaks the code on purpose; "The judge repeats this with its own faults." |
| `docs/agents/security.md` (end) | The judge checks the security rules by reading the change. |

**Owner rules kept outside the repo** (project memory). The judge applied them anyway:

- **Existing-contract rule.** Stratum never breaks an existing service's API contract.
- **MVC and WebFlux parity.** Both stacks behave the same for every status and error, in every mode.
- **Realistic findings only** (Aashish, 2026-10-30). Raise a finding only if you can show it on a real setup.
  Theoretical cases go in Notes.
- **One judge per PR** unless he asks for two (2026-10-30 18:50).
- **Never merge.** Aashish merges.
- **Check the ST-ENG code rules one by one** and put a rule table in every code verdict (added after PR #11
  passed twice without Lombok).

---

## 3. How a judge is started and what it gets

1. The implementer pushes, runs the full `./mvnw verify`, completes the Claims in the PR body, and asks the
   coordinator for a judge with the PR number and head commit.
2. The coordinator starts a **new thread**, titled "Judge <change> PR #n". It is a separate session that cannot see
   the implementer's conversation.
3. The coordinator's first note to the judge is **not kept in the thread record**: the thread root holds only the
   title. Its likely content *(inferred from the coordinator's later notes in the same threads)*:
   - the PR link, branch and head commit;
   - the owner's request and the decisions this PR must follow;
   - what changed, and the test count the implementer reports;
   - which Codex findings were fixed or declined;
   - specific risks to check, such as "check no double logging and parity across stacks" (#86 round 5);
   - the verdict file name, "be its one judge" and "Never merge."
4. For a small fix the owner can ask for a **light pass**. For #106 the coordinator wrote: "keep your judge pass
   light: confirm the fix works and causes no false alarms, then give the verdict. Nothing more. Never merge."

---

## 4. What the judge reads first

Each verdict opens with "I read AGENTS.md and all six topic files". Then, in this order (seen in the threads):

1. The rules: `AGENTS.md` and the six topic files.
2. The decisions the PR implements. #86 read `reviews/core-round2/independent-review.md` and
   `response-to-reviewer.md`. #95 judged against "Aashish's final spec (19:30–19:33Z)".
3. The PR diff (`git diff <base>..<head>`), the changed files in full, and their callers, tests and docs.
4. The Claims in the PR body.

---

## 5. How the judge checks the Claims (the gap list)

- The judge writes its own list of requirements from the request and the owner's decisions, then compares it with
  the Claims (`judge.md` step 1).
- Each verdict has a **gap list** table: requirement, result (MET, PARTLY or NOT MET), and the judge's own evidence:
  a `file:line`, a test name, a command and its output, or a probe result.
- A claim the judge did not reproduce counts as not met. Example from #105: "the test counts (993, 994, 995) were
  recounted from the reports after a clean verify."
- When the Claims put something out of scope that the owner never agreed to, it becomes a finding. In #98 the
  Claims called a 499 status split "Boot's behaviour", and the judge made it "Blocking until Aashish decides".

---

## 6. Builds, tests and probes

- **JDK 25.** The container ships JDK 21. Install 25 with
  `apt-get update && apt-get install -y openjdk-25-jdk-headless`, then
  `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 PATH=$JAVA_HOME/bin:$PATH`. Adoptium downloads are blocked.
- **Full build at every judged head:** `./mvnw -B clean verify` (or `-q -B verify`). The verdict records the exit
  code or BUILD SUCCESS, and often the test count.
- **Smaller builds** were used only for a test-only PR: #102 ran
  `./mvnw -pl starters/stratum-starter-webmvc,starters/stratum-starter-webflux -am verify`.
- **No build** for a text-only change. The #98 confirmation round: "Only docs/... changed (no build needed)."
- **Probes.** The judge writes throwaway tests or small apps to show real behaviour, and deletes them afterwards.
  Typical probes:
  - the same request on MVC and on WebFlux, in plain Boot (`stratum.web.enabled=false`), compatible mode and
    standard mode (#95: 19 paths × 4 `Accept` values);
  - hand-made types run through a rule (#105: 22 types, then 4 more for inheritance).
  A few probes were kept in `reviews/probes/`.
- **Merge check.** Once, `git merge-tree` against main (#98).

---

## 7. Planted faults

The judge breaks the code on purpose to prove the tests protect it (`judge.md` step 5, ST-TEST-01).

- **Where:** each important branch, guard, default or bean in the change. Examples: invert a condition, drop a
  clause, remove a `setStatus` call, change a fallback string, move a resolver's order.
- **How many:** depends on the change. Seen: 22 (#26), 7 (#95 round 1), 5 (#86, #102), 4 (#98 per round), 2–3 in
  later rounds. A light pass may plant none (#106).
- **Revert:** the code is restored after each fault, and the verdict says "`git status` clean".
- **Record:** "N planted, M caught", then one line per fault naming the test that caught it, or **nothing**.
- **A fault no test catches is a finding.** How severe it is depends on the risk:
  - #95 F2: a WebFlux parity line had no test, so the PR FAILED.
  - #95 G1: no test protected an owner decision that had already been broken twice, so the PR FAILED.
  - #26 F08: one name missing from a list survived, so it was only **minor**.
- **Each new round** plants faults on the new fix as well.

---

## 8. Rule checks

Each code verdict has these tables:

- **ST-ENG code rules.** Lombok (`@RequiredArgsConstructor`, `@UtilityClass`, `@Getter`), records, `var`, builders,
  `@NullMarked`/`@Nullable`, Javadoc on every member (ST-ENG-14/15), statics only in utilities (ST-ENG-47), one job
  per class (ST-ENG-16), `@StratumInternal` labels (ST-ENG-56).
- **Line counts (ST-AG-10).** Every changed file, its count and its limit. Files near the limit are named
  (#105: "`StarterTestSupport.java` 379 of 400").
- **Simplicity in both directions** (`judge.md` step 7). Over-engineering, and copied logic or tests
  (#98 F2: a copied test helper while a shared one existed).
- **Owner standing rules,** checked by reproducing them. Examples: the existing-contract rule (#95 F1, #86 round 3)
  and MVC/WebFlux parity (#86 F1, #98 F1).
- **Security rules.** Secrets and personal data in logs and bodies (#26, #95 binding errors).

---

## 9. Codex, CI and Sonar

- **Codex** reviews every push. The **implementer** answers each Codex comment inline: it accepts and fixes, or
  declines with evidence. The judge does not reply to Codex. What the judge does:
  - judges each new head that a Codex fix creates (#105 was judged at 4 heads, one per Codex fix);
  - checks the declined Codex findings and says whether it agrees (#95: "Declined Codex findings (checked)").
- **CI.** Some verdicts note the GitHub checks: "CI on d15dde2: Verify and SBOM, CodeQL, Sonar all success"
  (#102, also #100 and #34). Most verdicts rely on the judge's own local build. This is not consistent.
- **Sonar.** Sessions cannot reach sonarcloud.io. Its result is visible only through the GitHub check and the
  Sonar bot comment on the PR. For issue details, ask Aashish for screenshots.

---

## 10. Severities, PASS and FAIL

| Severity | Meaning (`review.md`) | Effect |
|---|---|---|
| Blocking | Wrong behaviour, a security flaw, a broken rule, or a requirement not met | No merge until fixed |
| Should fix | A real defect with limited impact, such as a missing test for an edge case | Fixed before merge unless the owner accepts it |
| Minor | Clarity or style that no tool checks | Never holds a merge |

- **PASS** only when no blocking and no should-fix finding is open (`judge.md` step 8).
- **An owner decision is blocking until he decides.** The judge explains the choice in plain words with options
  and a recommendation (#95: A, B or C on stripping `message`), and then judges against his answer.
- **Notes** hold real but unlikely or older issues. They are never findings.

---

## 11. The verdict

**The file:** `/mnt/project-files/reviews/judge/PR-<n>-<short-name>.md`. Its shape in practice:

1. Title: PR number, branch, head commit. Then the verdict line, **PASS** or **FAIL**, with one sentence.
2. "I read AGENTS.md and all six topic files."
3. "What I ran": each command and its result, and the probes.
4. Gap list.
5. Findings, numbered, each with severity, rule ID, evidence, and the fix expected.
6. Planted faults: N planted, M caught, one line each.
7. ST-ENG rule table and line counts.
8. Notes (not findings), and "Not verified".
9. Later rounds as `## Round N, head <sha>: PASS/FAIL` sections, and "Not merged."

**The thread reply.** It leads with the verdict, the head and "not merged", then short evidence. A real example
(#105):

> **PASS** for PR #105 at head `74bd8b3`. Nothing is open now. I did not merge it.
> **The fix works.** The rule now fails when an internal type appears only in a `throws` clause. [...]
> **Nothing else changed.** Since `7fe458b`, the only changes are that fix, its test and two small fixtures.
> A clean verify on JDK 25 is green with 994 tests. I updated the verdict file.

A real FAIL reply (#86 round 3) starts: "**PR #86 round 3 (commit fed823a): fails, on one should-fix finding.**
Please don't merge yet." Then it gives the finding as a real case: "a Spring MVC request never gets an answer,
and its thread runs at full CPU... I reproduced it on a real server."

**PR comment format.** There is no real example, because no judge posted on a PR (section 1). If the new session
wants one, `judge.md` asks only for "the verdict line with a link to the file".

---

## 12. Rounds and head changes

- **A new head** arrives through the coordinator, with the commit, what changed and the implementer's evidence.
  The judge then:
  - diffs from the last judged head;
  - runs the full verify again;
  - re-runs earlier probes and plants faults on the fix;
  - adds a Round section to the same file;
  - replies in its thread.
- **The head moved while the judge was working:** #95 (0da8073 to 753060f) and #86 (7e6fc9e to fed823a). The judge
  switched to the newest head and said so.
- **A confirmation round** is used when only docs or tests changed. #98: a doc-only change, no build, "the round 2
  PASS of the code still holds".
- **The two-round cap is not followed.** `judge.md` says the owner decides after round 2. #86 had 6 rounds and #105
  had 4. The coordinator kept sending new heads.

## 13. Light reviews, one judge, never merge

- **Light review** happens only when the owner asks, for a small fix. #106 checked that the fix worked, looked for
  false alarms and ran a clean verify. The verdict was 33 lines: no gap list, no planted faults, no rule table.
- **One judge per PR** is the default since 2026-10-30. Before that, PRs #47 and #50 to #56 had two judges, A and B,
  with separate files.
- **Never merge.** Every verdict and reply says "not merged". The one exception was #47 (2026-10-29), where Aashish
  explicitly allowed the merge.

---

## 14. Known weaknesses

Most come from `handoff/stratum-lessons.md` (point numbers in brackets).

1. **The judge starts from the Claims, not the owner's rules** [19]. It passed PR #11 twice without Lombok, and
   PR #16 and #19 before the owner rejected them as over-engineered. Owner rules held only in chat or memory cannot
   be checked [0, 2].
2. **The practice differs from `judge.md`.** The verdict goes to the shared folder, not the PR branch, and there is
   no PR comment. Files run to 149 lines (#95), not 60. More than 2 rounds happen. A new account will not have the
   shared folder.
3. **The first instruction to each judge is not recorded,** so each judge may be briefed differently *(inferred)*.
4. **Tests passed with the code broken** [11]. 54 of 104 judge reports needed 2 or more rounds, mostly because
   planted faults survived.
5. **The head moved during judging, and PRs merged before the verdict** [18]. #33 and #45 merged mid-judge, and both
   later needed fixes.
6. **CI and Sonar are not checked every time** [17]. The judge cannot reach sonarcloud.io.
7. **"Realistic findings only" is a chat rule** [20]. A judge can still raise theoretical findings, and an
   implementer can still build them (#54).
8. **Codex kept finding gaps after a PASS** [15]. #105 passed and then needed fixes for bounds, `throws`, inherited
   members and nested types.

---

## 15. Ready-to-paste judge prompt

Fill in the `<...>` parts. Send it to a new session that has not seen the implementer's work.

```text
You are the one independent judge for PR #<n> in exepex/stratum, head <full sha>, base <base sha>.
You hold no other role on this change. Never merge, push code, or comment on GitHub.

Request and decisions this PR must meet:
<owner's request, quoted, and each owner decision with its date>

Owner standing rules (check each one even if the Claims do not mention it):
- Never break an existing service's API contract with its consumers.
- MVC and WebFlux behave the same for every status and error, in every mode.
- Simple first: no type, option or test without a use today; no copied logic or test setup.
- Raise a finding only if you can show it on a real setup; theoretical cases go in Notes.
<any other standing rule that applies>

Do this, in order:
1. Read AGENTS.md and all six files in docs/agents/. Say in the verdict that you did.
2. Write your own requirement list from the request and decisions above. Only then read the Claims in the
   PR body, and compare.
3. Read the full diff, the changed files, and their callers, tests and docs.
4. Install JDK 25 (apt-get install -y openjdk-25-jdk-headless) and run ./mvnw -B clean verify at the head.
   Record the exit code and the test count. For a text-only change, skip the build and say so.
5. Probe the behaviour that matters with throwaway tests or apps (both stacks where it applies). Delete them after.
6. Plant faults in each important branch, guard, default or bean. Restore after each; confirm git status is clean.
   Record "N planted, M caught" and which test caught each. A survivor is a finding.
7. Check every rule: ST-AG, ST-ENG (with a table), ST-TEST, ST-SEC, ST-DOC, and every changed file's line count.
8. Check the GitHub checks (CI, CodeQL, Sonar) at this head, and every Codex finding the implementer declined.
9. Verdict: PASS, or FAIL with numbered findings, each blocking, should fix or minor, with evidence and the fix.
   PASS only if no blocking or should-fix finding is open. A choice that belongs to the owner is blocking
   until he decides: explain it simply, with options and your recommendation.

Write only your verdict, to <verdict location>: title with PR, branch and head; PASS/FAIL line; what you ran;
gap list; findings; planted faults; rule table; line counts; notes; not verified.
Reply in your thread with the verdict, the head, "not merged", and the key evidence in a few lines.
If a new head arrives, judge the diff since your last head, re-run verify, probes and faults on the fix,
and add a "Round N, head <sha>" section.
<For a light pass, replace steps 2 to 8 with: confirm the fix works and causes no false alarms; run verify.>
```
