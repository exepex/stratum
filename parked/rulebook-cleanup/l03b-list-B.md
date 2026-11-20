# 0.3b list B — docs/agents/judge.md at 54f4ce1

Read in full: `git show origin/main:docs/agents/judge.md | cat -n` (218 lines). Searched for each requirement in
`AGENTS.md`, `docs/agents/{engineering,testing,security,documentation,review}.md` and `docs/adr/*.md` on origin/main.
Line numbers below are from `cat -n` on origin/main (54f4ce1). "Internal" = the same requirement stated twice
inside judge.md.

| # | judge.md line(s) | Section | Short quote (≤15 words) | Also stated at | Same / different words / conflict |
|---|---|---|---|---|---|
| 1 | 7 | Intro | judge "accepts nothing it has not verified" | Internal judge.md:41, 138-139; AGENTS.md:59-61 (ST-AG-01) | different words, same meaning |
| 2 | 11 | Which changes need a judge | "Every pull request needs a judge PASS before it merges" | AGENTS.md:232-233, 63-64 (ST-AG-02); internal judge.md:216; ADR 0004:33-41 (Dependabot review steps list no judge) | AGENTS.md: different words, same meaning; ADR 0004: possible conflict (see Totals) |
| 3 | 11-12 | Which changes need a judge | "The track ... sets how many reviews it gets ... and which steps" | AGENTS.md:104 ("The track sets the design note and the judge reviews") | different words, same meaning |
| 4 | 16 | The judge pool | "Each review is done by a fresh judge agent, ... same judge prompt and the same checklist" | AGENTS.md:50, 124-125; internal judge.md:206 | different words, same meaning |
| 5 | 21 | The judge pool (table) | Docs: "1: a judge reads the changed text" | none found | — |
| 6 | 22 | The judge pool (table) | Fix: "1: a judge checks the code" | none found | — |
| 7 | 23 | The judge pool (table) | Feature: "2: ... design note before any code ...; then ... the code, and receives the approved note and the first verdict" | AGENTS.md:124-125 (ST-AG-16); internal judge.md:46-47, 76-77 | different words, same meaning |
| 8 | 25 | The judge pool | "each review runs on a different model from the one before it" | none found | — |
| 9 | 26 | The judge pool | "One judge per review step. Several judges ... in parallel only when the owner asks" | AGENTS.md:49 ("verifies one review step") partial | different words, same meaning (parallel-only-on-request stated nowhere else) |
| 10 | 27 | The judge pool | "A judge never reviews work it helped build" | AGENTS.md:52-53, 233-234; internal judge.md:51-52 | different words, same meaning |
| 11 | 27 | The judge pool | "never reads the conversation in which the work was built" | internal judge.md:36 | different words, same meaning; none in other files |
| 12 | 31-32 | Who starts the judge | implementer "finishes the work and its self-review ..., pushes it, and completes the Claims ... at that head commit" | AGENTS.md:44, 126-127; internal judge.md:69 | different words, same meaning |
| 13 | 32-33 | Who starts the judge | design note on Feature: implementer "gives the note to the coordinator ..., who posts it in the one place the owner reads" | AGENTS.md:124 (ST-AG-16) | near-same words, same meaning |
| 14 | 34 | Who starts the judge | "The implementer asks the coordinator to start the judge" | AGENTS.md:44; internal judge.md:64, 49 | different words, same meaning |
| 15 | 34-35 | Who starts the judge | "Until a PASS arrives, the implementer reports the work as 'waiting for judge', never as done" | AGENTS.md:63-64 (ST-AG-02) | different words, same meaning |
| 16 | 35 | Who starts the judge | "and pushes nothing" (until a PASS arrives) | AGENTS.md:235 ("Nothing is pushed ... while a judge reviews it"); internal judge.md:204-207 (Rounds) | AGENTS.md: different words, narrower scope; internal: conflict with Rounds (see Totals) |
| 17 | 36-37 | Who starts the judge | coordinator "starts the judge as a new agent session with no access to the implementer's conversation, using this prompt" | AGENTS.md:50, 232-233; internal judge.md:27 | different words, same meaning |
| 18 | 39-40 | Judge prompt (template) | prompt names "<pull request #N \| the design note of <item>...>", repo and "<Docs \| Fix \| Feature> track" | none found | — |
| 19 | 40 | Judge prompt (template) | "Follow `AGENTS.md` and `docs/agents/judge.md` exactly" | AGENTS.md:35-37 (every agent reads all six topic files) | different words; prompt names two files, AGENTS.md requires all six (not a conflict: AGENTS.md itself requires the rest) |
| 20 | 41 | Judge prompt (template) | "Verify every claim yourself; a claim you have not verified is NOT MET" | internal judge.md:7, 138-139; AGENTS.md:59-61 (ST-AG-01) | different words, same meaning |
| 21 | 41-42 | Judge prompt (template) | "Post your verdict as one comment there and change nothing else" | internal judge.md:188-189; AGENTS.md:49 ("writes only its verdict") | different words, same meaning |
| 22 | 42-43 | Judge prompt (template) | "If you cannot post a comment, give the verdict to the coordinator, who posts it unchanged" | none found | — |
| 23 | 45-46 | Who starts the judge | coordinator "adds the owner's request, the owner's decisions from the implementer's brief and any judge check written under ST-AG-19" | AGENTS.md:145-146 (ST-AG-19 adds judge checks "to the briefs") partial | different words; complementary (brief vs judge prompt), not a conflict |
| 24 | 46-47 | Who starts the judge | code review on Feature: "also adds the links to the approved note and to the first verdict" | internal judge.md:23 | different words, same meaning |
| 25 | 47 | Who starts the judge | "It adds nothing else" | none found | — |
| 26 | 49-50 | Who starts the judge | no coordinator: "the implementer asks the owner for the judge, and the owner starts it with this prompt, filled in the same way" | AGENTS.md:99-100, 233 | different words, same meaning |
| 27 | 50-51 | Who starts the judge | implementer "may do the other coordinator duties ... but it never starts or chooses its own judge" | AGENTS.md:99-101 | same words ("never starts or chooses its own judge") |
| 28 | 51-52 | Who starts the judge | "The implementer never judges its own work" | AGENTS.md:233-234; internal judge.md:27 | same words (AGENTS.md); different words (judge.md:27) |
| 29 | 56 | The implementer brief | "The coordinator starts every implementer with this template, filled in" | AGENTS.md:139-140 (ST-AG-18), 50 | different words, same meaning |
| 30 | 56-57 | The implementer brief | "It cites rules; it never restates or replaces them" | AGENTS.md:140-141 | near-same words, same meaning |
| 31 | 59-60 | Brief (template) | brief names item, track and "The owner's request: '<quote or link>'" | none found (AGENTS.md:140 lists only rule IDs and decisions) | — |
| 32 | 60 | Brief (template) | "Read `AGENTS.md` and every file in `docs/agents/` in full, and follow them" | AGENTS.md:35-37; internal judge.md:84 | different words, same meaning |
| 33 | 60-61 | Brief (template) | "The rules this item touches most: <rule IDs>" | AGENTS.md:140 ("cites rule IDs") | different words, same meaning |
| 34 | 61-62 | Brief (template) | "owner's decisions not yet written into a rule (ST-AG-13): <each decision with its date, or none>" | AGENTS.md:140-141 | different words; judge.md adds "with its date" (extra detail, no conflict) |
| 35 | 62 | Brief (template) | "Checks added after earlier misses (ST-AG-19): <each, or none>" | AGENTS.md:145-146 | different words, same meaning |
| 36 | 62-63 | Brief (template) | "Answer design-note questions <1, 2 and 4 \| 1 to 4 \| none> before any code" | AGENTS.md:122-124; internal judge.md:91-92 | different words, same meaning |
| 37 | 63-64 | Brief (template) | "review your own change ..., write the Claims and ask the coordinator for the judge" | AGENTS.md:44, 126-127; internal judge.md:31-34 | different words, same meaning |
| 38 | 64-65 | Brief (template) | "No branch, commit or push before the owner's go" | AGENTS.md:81-82 (ST-AG-11), 223 | different words; AGENTS.md also lists "pull request"; same meaning |
| 39 | 69 | The Claims | PR description "contains a **Claims** section, written by the implementer and updated each round" | AGENTS.md:225-227; internal judge.md:31, 127 | different words, same meaning (AGENTS.md's content list is shorter but points here) |
| 40 | 71 | The Claims | 1 "The owner's request, quoted or linked, and the track of the change" | AGENTS.md:225-226 | different words, same meaning |
| 41 | 72-73 | The Claims | 2 "Every requirement ..., one per line. Each is either met, with its evidence, or out of scope, with the reason and the owner's agreement" | AGENTS.md:226 | different words, same meaning |
| 42 | 73-75 | The Claims | 2 "Evidence is a `file:line` ..., the test ..., and the command that was run with its result" | AGENTS.md:59-60 (ST-AG-01) | different words, same meaning |
| 43 | 76-77 | The Claims | 3 "On Fix and Feature, the answers to the design note ... On Feature, also the link to the judge's PASS of the note" | AGENTS.md:226; internal judge.md:91-92, 127 | different words, same meaning |
| 44 | 78-79 | The Claims | 4 "For each requirement with code, the fault the implementer planted and the test that caught it" | AGENTS.md:128 (ST-AG-17); testing.md:8-10 (ST-TEST-01) | different words, same meaning |
| 45 | 80-82 | The Claims | 5 "each search command with its number of hits, the most-broken rules checked, the rebase and the CI result ..., and each class or file added to fix a finding" | AGENTS.md:126-138 (ST-AG-17), 87 (ST-AG-12) | different words; judge.md's list omits ST-AG-17's module re-verify, leak search and rule/ADR search bullets, but says "the evidence of ST-AG-17" (drift, not conflict) |
| 46 | 83 | The Claims | 6 "Anything left open, stated plainly" | AGENTS.md:60 (ST-AG-01), 63 (ST-AG-02), 243 | different words, same meaning |
| 47 | 84 | The Claims | 7 "Confirmation that the implementer read `AGENTS.md` and all six topic files before starting" | AGENTS.md:38 | different words, same meaning |
| 48 | 85 | The Claims | 8 "Each changed file with its line count" | AGENTS.md:161-162, 227 | different words, same meaning |
| 49 | 87 | The Claims | "A claim without evidence counts as not made" | AGENTS.md:62-63 (ST-AG-02); internal judge.md:41 | different words, same meaning |
| 50 | 91 | The design note | "The implementer answers these questions before any code" | AGENTS.md:122-123; internal judge.md:62-63 | different words, same meaning |
| 51 | 91-92 | The design note | "Fix answers questions 1, 2 and 4, Feature answers all four, and Docs has no note" | AGENTS.md:123-124; internal judge.md:62-63 | same words (AGENTS.md) |
| 52 | 92 | The design note | "Tests are not a question here: ST-TEST-10 to ST-TEST-34 decide them" | none found | — |
| 53 | 94 | The design note | "Each answer is at most 3 lines and gives concrete facts" | none found | — |
| 54 | 94-95 | The design note | "'None' is an answer only with the fact that makes it so" | none found | — |
| 55 | 95-96 | The design note | "The judge fails a 'none' that the diff contradicts" | none found | — |
| 56 | 98-101 | The design note (Q1) | consumers, adoption with Stratum parent or company framework, "each change a consumer sees", API vs `@StratumInternal`, lines a service writes | engineering.md:282 (ST-ENG-56 "decide which one each new type is before you write it"); ADR 0009:58-59 | different words, partial |
| 57 | 102-105 | The design note (Q2) | what is already provided and reused, simplest way and one alternative, class modelled on, module/package and no cycle, each key with default, Spring Boot key first, Java customizer | engineering.md:110-111 (ST-ENG-18), 287-289 (ST-ENG-57), 208-211 (ST-ENG-34) | different words, partial |
| 58 | 106-108 | The design note (Q3) | eight parameters, hostile inputs as tests, web cases tested on both stacks, each thread hop with context, large-input test for parsing/regex/masking | AGENTS.md:94-95 (ST-AG-14); ADR 0006:43; engineering.md:267-268 (ST-ENG-53); testing.md:31-32 | different words, partial |
| 59 | 109-110 | The design note (Q4) | rule IDs touched, each owner decision with its protecting test, each Boot/library fact with source, documents updated | documentation.md:28-30 (ST-DOC-01) partial | different words, partial |
| 60 | 114 | What the judge does | "One judge works through this checklist in order" | none found | — |
| 61 | 114-115 | What the judge does | "It collects every finding before it gives the verdict" | none found | — |
| 62 | 119 | What the judge does (table) | Docs review: steps "1, 2, 3 and 5" | internal judge.md:124-125 | different words, same meaning |
| 63 | 120 | What the judge does (table) | Fix review: steps "1 to 5" | none found | — |
| 64 | 121 | What the judge does (table) | Feature, design note: "1, 2, 3 and 5, applied to the note" | none found | — |
| 65 | 122 | What the judge does (table) | Feature, code: "1 to 5" | none found | — |
| 66 | 124-125 | What the judge does | all changed files Markdown/text/AsciiDoc: "the judge skips step 4: a text-only change needs no build" | AGENTS.md:217; internal judge.md:119 | near-same words, same meaning |
| 67 | 127-128 | Step 1 Gate | "The Claims are complete, with the design note on Fix and Feature; for a design-note review, the note is complete" | AGENTS.md:225-227; internal judge.md:130-131 | different words, same meaning |
| 68 | 128-129 | Step 1 Gate | "CI is green at the head commit, and every Codex and Sonar finding is answered in its thread" | AGENTS.md:236-237, 138 | different words, same meaning |
| 69 | 129 | Step 1 Gate | "the judge reads Sonar through the pull request's GitHub checks" | none found | — |
| 70 | 129-130 | Step 1 Gate | "When the gate fails, the verdict is FAIL with that one finding, and the judge checks nothing else" | internal judge.md:212 | different words, same meaning |
| 71 | 130-131 | Step 1 Gate | "A missing design note on Fix or Feature is a blocking finding that fails the gate" | internal judge.md:127; AGENTS.md:122 (ST-AG-16); review.md:14 | different words, same meaning |
| 72 | 133-134 | Step 2 | "Build the gap list first. Read the owner's request and write your own list ... before reading the implementer's list. Then compare" | internal judge.md:195 | different words, same meaning |
| 73 | 134-135 | Step 2 | "A requirement that is neither met nor declared out of scope is a blocking finding" | review.md:14 ("a requirement not met" = Blocking) | different words, same meaning |
| 74 | 135 | Step 2 | "Something built that the request did not ask for is a should fix finding" | AGENTS.md:74-76 (ST-AG-06); review.md:14 (broken AGENTS.md rule = Blocking) | conflict on severity (see Totals) |
| 75 | 136-137 | Step 2 | "Read the whole diff ..., each changed file in full where needed, and the callers, tests and documents the change affects" | review.md:20-21, 30 | different words, same meaning |
| 76 | 138-139 | Step 2 | "Check each requirement and each owner decision in the brief yourself. A requirement is MET only with your own evidence ... If you could not verify it, it is NOT MET" | internal judge.md:7, 41; AGENTS.md:59-61 (ST-AG-01) | different words, same meaning |
| 77 | 141 | Step 3 | "The code matches the design note" | none found | — |
| 78 | 142-143 | Step 3 | "Check the change against every rule: AGENTS.md, engineering (ST-ENG-01 to ST-ENG-64), testing, documentation and review" | none found (security covered by step 5) | — |
| 79 | 143-144 | Step 3 | "Check the line count of every changed file against the limits in AGENTS.md" | AGENTS.md:162 ("The judge checks the counts"), 170 | different words, same meaning |
| 80 | 145-146 | Step 3 | "Check simplicity in both directions. For each new type, method, option and branch, name its use today" | engineering.md:19-20 (ST-ENG-05); AGENTS.md:178-179 | different words, same meaning |
| 81 | 146-147 | Step 3 | "One with no use, or Javadoc that explains internals or restates the code, is a should-fix finding" | engineering.md:19-22, 88-90 (rules, no severity); review.md:15 | different words; severity stated only here (see Unsure) |
| 82 | 147 | Step 3 | "Each class or file added to fix a finding must be the simplest fix" | AGENTS.md:86-87 (ST-AG-12 "the judge checks that it is the simplest fix") | different words, same meaning |
| 83 | 148-150 | Step 3 | "each changed class does one job, ... no logic is copied ..., an existing helper or `testsupport` method was not bypassed, and that tests follow ST-TEST-13 to ST-TEST-17" | review.md:35-37; AGENTS.md:182-186 | different words, same meaning |
| 84 | 150-151 | Step 3 | "A class with a second job, copied logic or repeated test setup is a should-fix finding" | review.md:15 (should-fix definition only) | severity stated only here (see Unsure) |
| 85 | 152 | Step 3 | "Read each changed document against the code at the head commit" | documentation.md:61-62; AGENTS.md:46 | near-same words, same meaning |
| 86 | 153-154 | Step 3 | rule or ADR change: "check that every file that states or depends on that rule was updated" | AGENTS.md:136-137 (ST-AG-17) | different words; AGENTS.md also allows a file "named with the reason it stays", judge.md does not (drift, see Unsure) |
| 87 | 156 | Step 4 | "Run the build and the tests, and any small check or probe you need" | none found (AGENTS.md:209 is the implementer's duty) | — |
| 88 | 157-159 | Step 4 | "Look for what the Claims do not mention: bugs, edge cases (...), missing or hollow tests, documentation left out of date, dead code, files the request did not ask for" | review.md:31-32; testing.md:11-12 (ST-TEST-02) | different words, overlapping lists |
| 89 | 160-162 | Step 4 | "Plant faults. ... break the code on purpose ... check that a test fails. Restore ... Record how many faults you planted and how many the tests caught" | testing.md:8-10 (ST-TEST-01 "The judge repeats this with its own faults"); AGENTS.md:128; internal judge.md:197 | different words, same meaning |
| 90 | 163-164 | Step 5 | "Check ST-SEC-01 to ST-SEC-06 on every change, and each other rule in security that the change touches" | security.md:59 | same words |
| 91 | 164-165 | Step 5 | "Only when the change is on the trigger list ..., also follow each untrusted value from where it enters to where it is used" | security.md:61-64; AGENTS.md:112 | different words, same meaning ("Only" added in judge.md) |
| 92 | 167-168 | Verdict | "PASS, or FAIL with numbered findings, each blocking, should fix or minor ..., with evidence and the fix expected" | review.md:10, 46; internal judge.md:193-196 | different words, same meaning |
| 93 | 168-169 | Verdict | "A change passes only when no blocking or should-fix finding remains, apart from a finding the judge has deferred to a GitHub issue" | review.md:14-16; internal judge.md:184 | conflict with review.md:15 "unless the owner accepts it" (see Totals) |
| 94 | 171 | Verdict | "Do not invent findings to look thorough, and do not pass work to be agreeable" | none found | — |
| 95 | 175 | The finding bar | "A behaviour finding comes with a failing test that the judge ran: the test, or its steps, and the wrong result" | review.md:38-39 ("a failing test or a reproduced wrong result") | different words; review.md accepts a reproduced wrong result without a test, judge.md asks for the test or its steps plus the result (drift, no conflict) |
| 96 | 176 | The finding bar | "A structure, naming or documentation finding quotes the exact lines" | review.md:39-40 | same words |
| 97 | 177 | The finding bar | "A case that is only theoretical is a Note, not a finding, and is never built" | review.md:40 (same words); AGENTS.md:70-71 (ST-AG-04); security.md:66 | same words (review.md); different words (AGENTS.md, security.md), same meaning |
| 98 | 181-182 | Deferring a finding | "Only the judge may move a real finding that is not blocking to a GitHub issue, when its fix is large compared with its harm" | review.md:15 | same words |
| 99 | 182 | Deferring a finding | "The verdict says so, and the coordinator opens the issue and tells the owner in one line" | review.md:15 ("the owner is told in one line") | different words; review.md does not say who opens the issue |
| 100 | 182-183 | Deferring a finding | "The owner may object; the finding is then fixed in the pull request" | review.md:15 ("may object"); internal judge.md:184 | different words, same meaning |
| 101 | 183 | Deferring a finding | "The implementer never defers a finding" | review.md:15 ("The implementer cannot defer it") | different words, same meaning |
| 102 | 184 | Deferring a finding | "A deferred finding does not hold the PASS" | internal judge.md:168-169 | different words, same meaning; none in other files |
| 103 | 184 | Deferring a finding | "If the owner objects, the PASS waits until the finding is fixed" | internal judge.md:182-183 | different words, same meaning; none in other files |
| 104 | 188-189 | The verdict | "The judge posts its verdict as one comment: on the pull request, or, for a design-note review, where the note was posted" | internal judge.md:41-42; AGENTS.md:49 | different words, same meaning |
| 105 | 189 | The verdict | "It never writes the verdict as a file in the repository, and changes nothing else" | internal judge.md:42; AGENTS.md:49 | different words, same meaning |
| 106 | 189-191 | The verdict | "at most about 100 lines ...: each gap-list item takes one line and each finding at most three" | none found (review.md:44 "One line per finding" is the reviewer format) | — |
| 107 | 193-194 | Verdict (template) | 1 Title: "pull request number or design note, branch, head commit, track and review step. Then one line: PASS or FAIL" | none found | — |
| 108 | 195 | Verdict (template) | 2 Gap list: "each requirement from the request, MET, PARTLY or NOT MET, with the judge's evidence" | internal judge.md:133-135, 138-139 | different words; step 2 knows MET/NOT MET only, template adds PARTLY (see Unsure) |
| 109 | 196 | Verdict (template) | 3 Findings (FAIL only): "numbered, with severity, evidence and the fix expected" | internal judge.md:167-168; review.md:44-46 | different words, same meaning |
| 110 | 197 | Verdict (template) | 4 Planted faults: "how many were planted, how many were caught, and which survived" | internal judge.md:161-162 | different words; "which survived" only here |
| 111 | 198 | Verdict (template) | 5 "Notes that are not findings, if any" | internal judge.md:177; AGENTS.md:71 | different words, same meaning |
| 112 | 200 | The verdict | "Quote the implementer only where a claim is wrong" | none found | — |
| 113 | 200 | The verdict | "No praise and no summary of the change" | review.md:48 ("No praise and no restating the change") | different words, same meaning |
| 114 | 204-205 | Rounds | after FAIL "the implementer fixes each finding and shows the proof ..., or answers it with evidence that the requirement is already met" | AGENTS.md:85-86 (ST-AG-12), 67-69 (ST-AG-04) | different words, same meaning |
| 115 | 205 | Rounds | "Disagreement without evidence is not an answer" | AGENTS.md:69 ("Prove every accept and every reject with that evidence") | different words, same meaning |
| 116 | 206-207 | Rounds | "A fresh judge for the same review step, given the earlier verdict, reviews once more at the new head commit, in a new comment" | internal judge.md:16; AGENTS.md:50 | different words, same meaning |
| 117 | 207-208 | Rounds | "It accepts or rejects each fix and each answer with its own evidence. An answer is accepted only when the judge reproduces it" | AGENTS.md:67-69 (ST-AG-04, for every agent) partial | different words, partial |
| 118 | 209-211 | Rounds | "If findings remain after two rounds ..., the owner decides ... there is no third round, and the coordinator starts no judge on a new head commit until the owner has decided" | none found | — |
| 119 | 212 | Rounds | "A FAIL at the gate does not count as a round. Once the gate's finding is fixed, the next review is round 1" | internal judge.md:129-130 | different words, same meaning; none in other files |
| 120 | 216 | Merging | "The owner merges a pull request only when the judge's PASS is for its current head commit" | AGENTS.md:232, 238; ADR 0004:31, 41; internal judge.md:11 | different words, same meaning (ADR 0004 only says the owner merges) |
| 121 | 216-217 | Merging | "Any later commit needs the judge to confirm that the PASS still holds" | AGENTS.md:232 ("at the pull request's current head commit") | different words, same meaning |
| 122 | 217-218 | Merging | "The coordinator says 'Ready to merge at <sha>' once" | AGENTS.md:236-237, 50 | different words, same meaning (AGENTS.md adds the three conditions) |

## Totals

Items: 122.

Per section:

| Section | Lines | Items |
|---|---|---|
| Intro | 1-7 | 1 (#1) |
| Which changes need a judge | 9-12 | 2 (#2-3) |
| The judge pool | 14-27 | 8 (#4-11) |
| Who starts the judge, and when (incl. judge prompt template) | 29-52 | 17 (#12-28) |
| The implementer brief (incl. template) | 54-65 | 10 (#29-38) |
| The Claims | 67-87 | 11 (#39-49) |
| The design note | 89-110 | 10 (#50-59) |
| What the judge does (checklist, steps 1-5, verdict rule) | 112-171 | 35 (#60-94) |
| The finding bar | 173-177 | 3 (#95-97) |
| Deferring a finding | 179-184 | 6 (#98-103) |
| The verdict | 186-200 | 10 (#104-113) |
| Rounds | 202-212 | 6 (#114-119) |
| Merging | 214-218 | 3 (#120-122) |

Also stated elsewhere:

- Stated in another file (AGENTS.md, another topic file or an ADR), fully or partly: 83 items
  (#1-4, 7, 9, 10, 12-15, 16, 17, 19-21, 23, 26-30, 32-51, 56-59, 66-68, 71, 73-76, 79-86, 88-93, 95-101, 104, 105, 109, 111, 113-117, 120-122).
- Stated nowhere else, judge.md is the only source: 39 items
  (#5, 6, 8, 11, 18, 22, 24, 25, 31, 52-55, 60-65, 69, 70, 72, 77, 78, 87, 94, 102, 103, 106-108, 110, 112, 118, 119; of
  these, #11, 24, 62, 70, 72, 102, 103, 108, 110, 119 are stated twice inside judge.md).
- Same words in another file: #27, 28, 51, 90, 96, 97 (review.md), 98; near-same words: #13, 30, 66, 85.
- Stated twice inside judge.md (internal copies): #1/20/76, 4/116, 7/24, 10/28, 11/17, 12/39, 14/37, 16 vs Rounds, 21/104/105, 36/50/51, 43/67, 49/20, 62/66, 67/71, 70/119, 72/108, 89/110, 92/109, 93/102, 97/111, 100/103.

Conflicts (following one side breaks the other):

1. **Severity of unrequested work.** judge.md:135 "Something built that the request did not ask for is a **should fix** finding." vs review.md:14 "Blocking | Wrong behaviour, a security flaw, a broken rule in AGENTS.md, or a requirement not met", with AGENTS.md:74-76 ST-AG-06 "Do not add files, tools, scripts, dependencies or features the request did not call for" being a rule in AGENTS.md. A judge that follows review.md's table rates it blocking; one that follows judge.md rates it should fix.
2. **Who can let a should-fix finding stay unfixed.** review.md:15 Should fix "Fixed before merge, unless the owner accepts it. Only the judge may move a real one to a GitHub issue ..." vs judge.md:168-169 "A change passes only when no blocking or should-fix finding remains, apart from a finding the judge has deferred to a GitHub issue." review.md lets the owner accept an unfixed should-fix; judge.md gives the judge no way to PASS with it unless the judge itself deferred it.
3. **Pushing between rounds (internal to judge.md).** judge.md:34-35 "Until a PASS arrives, the implementer reports the work as 'waiting for judge', never as done, and pushes nothing." vs judge.md:204-206 "After a FAIL, the implementer fixes each finding and shows the proof (commit, ...) ... A fresh judge ... reviews once more at the new head commit". Read literally, line 35 forbids the push that Rounds requires after a FAIL. AGENTS.md:235 states the narrower version ("Nothing is pushed to a pull request while a judge reviews it"), which is what Rounds assumes.
4. **Possible: every pull request needs a judge.** judge.md:11 "Every pull request needs a judge PASS before it merges." vs ADR 0004:33-41 "Reviewing a Dependabot pull request: 1. The build is green. 2. Read the release notes ... 3. For a Spring Boot upgrade ... 4. The owner merges it." The ADR's procedure for a Dependabot pull request has no judge step. It is an omission rather than a statement that no judge is needed, so it may be a gap rather than a contradiction.

## Unsure

- #19 (judge.md:40 "Follow `AGENTS.md` and `docs/agents/judge.md` exactly"): counted as a template rule. AGENTS.md:35 requires every agent to read all six topic files; the prompt names only two. Not a conflict because AGENTS.md itself imposes the rest, but a prompt reader could miss the other four files.
- #37 (judge.md:63-64 "review your own change (ST-AG-17)"): borderline between "only points to an ID" and "states the requirement in its own words". Counted because "review your own change" and "write the Claims and ask the coordinator for the judge" are stated in words.
- #52 (judge.md:92 "ST-TEST-10 to ST-TEST-34 decide them"): counted as a rule because it fixes which rules decide tests; the range leaves out ST-TEST-01 to 04 and ST-TEST-40/41, which may be unintended drift rather than a rule.
- #56-59 (design-note questions): each question is a list of things to include; counted as one item per question rather than one per clause. Splitting each clause would add roughly 20 items.
- #81 and #84 (should-fix severities for ST-ENG-05/15 and ST-ENG-01/06/16-18 breaks): the rules live in engineering.md, but AGENTS.md:178-184 restates them ("Simple first", "One job per class"). If "a broken rule in AGENTS.md" in review.md:14 includes those summary bullets, these would be blocking, not should fix. Listed as unsure rather than as a conflict because AGENTS.md:175-176 says the topic file wins where they differ.
- #86 (judge.md:153-154): AGENTS.md:136-137 lets a file that states the rule stay when it is "named with the reason it stays"; judge.md says only "was updated". A judge following judge.md literally could fail a file the implementer legitimately left. Listed as drift, not a conflict, because judge.md cites ST-AG-17.
- #95 (judge.md:175 vs review.md:38-39): review.md accepts "a reproduced wrong result" without a test; judge.md requires the judge to have run a failing test ("the test, or its steps, and the wrong result"). review.md:38 points to judge.md's bar, so the two were meant to be the same; the wording has drifted.
- #108 (judge.md:195 "MET, PARTLY or NOT MET"): step 2 (judge.md:138-139) defines only MET and NOT MET ("If you could not verify it, it is NOT MET"); PARTLY exists only in the verdict template. Counted as a rule; the extra state is drift inside judge.md.
- #106 vs review.md:44 ("One line per finding"): review.md's one-line format is for reviewers, and review.md:4 says the judge shares only the severity scale, so not listed as a conflict.
- Not counted: judge.md:3-4 and 6-7 first sentence (description/provenance), 9/14/29/54/67/89/112/173/179/186/202/214 (headings), 114-115 "so that the implementer can fix them all in one round" (reason), 190 "so that every finding fits in one comment" (reason), 94-95 the example "no new key: ..." (example), 142 the links themselves.
