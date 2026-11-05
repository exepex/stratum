# 0.3b list A — docs/agents/judge.md at 54f4ce1

Source: `git show origin/main:<path>` at origin/main = 54f4ce1. judge.md read in full (218 lines). Compared against
AGENTS.md, docs/agents/{engineering,testing,security,documentation,review}.md and docs/adr/0001-0009.
"internal" = the same requirement appears elsewhere inside judge.md.

| # | judge.md line(s) | Section | Short quote (≤15 words) | Also stated at | Same / different words / conflict |
|---|---|---|---|---|---|
| 1 | 3 | Intro | "Part of the agent rules ...; every agent follows them." | AGENTS.md:8; engineering.md:3, testing.md:3, security.md:3, documentation.md:3 | Same words in topic files; different words in AGENTS.md |
| 2 | 11 | Which changes need a judge | "Every pull request needs a judge PASS before it merges." | AGENTS.md:232; AGENTS.md:63-64 (ST-AG-02) | Different words |
| 3 | 11-12 | Which changes need a judge | "The track ... sets how many reviews it gets ... and which steps" | AGENTS.md:104 | Different words |
| 4 | 16 | The judge pool | "Each review is done by a fresh judge agent, ... same judge prompt ... checklist." | AGENTS.md:50, AGENTS.md:124-125; internal 206 | Different words |
| 5 | 21 | The judge pool (table) | "Docs \| 1: a judge reads the changed text" | none found | — |
| 6 | 22 | The judge pool (table) | "Fix \| 1: a judge checks the code" | none found | — |
| 7 | 23 | The judge pool (table) | "Feature \| 2: ... design note before any code ...; then ... the code" | AGENTS.md:124-125 (ST-AG-16); internal 46-47 | Different words |
| 8 | 25 | The judge pool | "each review runs on a different model from the one before it" | none found | — |
| 9 | 26 | The judge pool | "One judge per review step. ... in parallel only when the owner asks." | AGENTS.md:49, AGENTS.md:50 (one review step / fresh judge per step) | Different words; parallel clause none found |
| 10 | 27 | The judge pool | "A judge never reviews work it helped build" | AGENTS.md:53 (judge role never combined); AGENTS.md:233-234; internal 51-52 | Different words |
| 11 | 27 | The judge pool | "never reads the conversation in which the work was built" | internal 36 | — |
| 12 | 31-32 | Who starts the judge | "The implementer finishes the work and its self-review ..., pushes it, ... completes the Claims" | AGENTS.md:126-127 (ST-AG-17), AGENTS.md:44 | Different words |
| 13 | 32-33 | Who starts the judge | "the implementer gives the note to the coordinator ..., who posts it" | AGENTS.md:124 (ST-AG-16) | Different words, same meaning |
| 14 | 34 | Who starts the judge | "The implementer asks the coordinator to start the judge." | AGENTS.md:44; internal 63-64 | Different words |
| 15 | 34-35 | Who starts the judge | "reports the work as "waiting for judge", never as done" | AGENTS.md:62-64 (ST-AG-02) | Different words |
| 16 | 35 | Who starts the judge | "Until a PASS arrives, the implementer ... pushes nothing." | AGENTS.md:235; internal 204-206 (Rounds) | **Conflict** (see Totals C1) |
| 17 | 36-37 | Who starts the judge | "coordinator starts the judge as a new agent session with no access" | AGENTS.md:50, AGENTS.md:232-233; internal 27 | Different words |
| 18 | 39-40 | Who starts the judge (prompt) | "independent judge for <pull request #N \| the design note ...> ... <track>" | none found | — |
| 19 | 40 | Who starts the judge (prompt) | "Follow `AGENTS.md` and `docs/agents/judge.md` exactly." | AGENTS.md:8 | Different words |
| 20 | 41 | Who starts the judge (prompt) | "Verify every claim yourself; a claim you have not verified is NOT MET." | AGENTS.md:59-61 (ST-AG-01); internal 87, 138-139 | Different words |
| 21 | 41-42 | Who starts the judge (prompt) | "Post your verdict as one comment there and change nothing else." | AGENTS.md:49 ("writes only its verdict"); internal 188-189 | Different words |
| 22 | 42-43 | Who starts the judge (prompt) | "If you cannot post ..., give the verdict to the coordinator, who posts it unchanged." | none found | — |
| 23 | 45-46 | Who starts the judge | "coordinator adds the owner's request, the owner's decisions ... and any judge check" | AGENTS.md:144-146 (ST-AG-19 judge check) | Different words |
| 24 | 46-47 | Who starts the judge | "For the code review on Feature, it also adds the links to the approved note" | internal 23 | — |
| 25 | 47 | Who starts the judge | "It adds nothing else." | none found | — |
| 26 | 49-50 | Who starts the judge | "When there is no coordinator, ... the owner starts it with this prompt" | AGENTS.md:99-100, AGENTS.md:232-233 | Different words |
| 27 | 50-51 | Who starts the judge | "The implementer may do the other coordinator duties, such as the track and the brief" | AGENTS.md:99-100 ("the implementer does the coordinator's duties except one") | **Conflict** (C2) |
| 28 | 51 | Who starts the judge | "it never starts or chooses its own judge" | AGENTS.md:100-101 | Same words |
| 29 | 51-52 | Who starts the judge | "The implementer never judges its own work." | AGENTS.md:233-234 | Same words |
| 30 | 56 | The implementer brief | "The coordinator starts every implementer with this template, filled in" | AGENTS.md:139-140 (ST-AG-18), AGENTS.md:50 | Different words, same meaning |
| 31 | 56-57 | The implementer brief | "It cites rules; it never restates or replaces them." | AGENTS.md:140-141 | Nearly same words |
| 32 | 59 | The implementer brief (template) | "You are the implementer for <item> ..., on the <Docs \| Fix \| Feature> track." | none found | — |
| 33 | 59-60 | The implementer brief (template) | "The owner's request: "<quote or link>"." | none found outside (internal 71 for Claims) | — |
| 34 | 60 | The implementer brief (template) | "Read `AGENTS.md` and every file in `docs/agents/` in full, and follow them." | AGENTS.md:35; internal 84 | Different words |
| 35 | 60-61 | The implementer brief (template) | "The rules this item touches most: <rule IDs>." | AGENTS.md:140 ("The brief cites rule IDs") | Different words |
| 36 | 61-62 | The implementer brief (template) | "The owner's decisions not yet written into a rule ...: <each decision with its date" | AGENTS.md:140-141 | Different words (date only in judge.md) |
| 37 | 62 | The implementer brief (template) | "Checks added after earlier misses (ST-AG-19): <each, or none>." | AGENTS.md:145-146 | Different words |
| 38 | 62-63 | The implementer brief (template) | "Answer design-note questions <1, 2 and 4 \| 1 to 4 \| none> before any code" | AGENTS.md:122-124 (ST-AG-16); internal 91-92 | Different words, same meaning |
| 39 | 63-64 | The implementer brief (template) | "review your own change ..., write the Claims and ask the coordinator for the judge" | AGENTS.md:44, AGENTS.md:126-127; internal 31-34 | Different words |
| 40 | 64-65 | The implementer brief (template) | "No branch, commit or push before the owner's go (ST-AG-11)." | AGENTS.md:81-82 (ST-AG-11), AGENTS.md:223 | Different words (brief omits "pull request") |
| 41 | 69 | The Claims | "a **Claims** section, written by the implementer and updated each round" | AGENTS.md:225, AGENTS.md:44 | Different words ("updated each round" only here) |
| 42 | 71 | The Claims | "The owner's request, quoted or linked, and the track of the change." | AGENTS.md:225-226 | Different words, same meaning |
| 43 | 72-73 | The Claims | "Every requirement ... either **met**, with its evidence, or **out of scope**, with the reason" | AGENTS.md:226 | Different words (owner's agreement only here) |
| 44 | 73-75 | The Claims | "Evidence is a `file:line` ..., the test ..., and the command ... with its result" | AGENTS.md:59-60 (ST-AG-01) | Different words |
| 45 | 76-77 | The Claims | "On Fix and Feature, the answers to the design note ... On Feature, ... link to the judge's PASS" | AGENTS.md:226 ("the design note") | Different words (no track qualifier in AGENTS) |
| 46 | 78-79 | The Claims | "For each requirement with code, the fault the implementer planted and the test that caught it." | AGENTS.md:128 (ST-AG-17); testing.md:8-10 (ST-TEST-01) | Different words |
| 47 | 80-82 | The Claims | "Self-review. The evidence of ST-AG-17: each search command with its number of hits ..." | AGENTS.md:126-138 (ST-AG-17), AGENTS.md:86-87 (ST-AG-12) | Different words (judge.md lists a subset of ST-AG-17's items) |
| 48 | 83 | The Claims | "Not done or not verified. Anything left open, stated plainly." | AGENTS.md:60-61, AGENTS.md:63, AGENTS.md:243 | Different words |
| 49 | 84 | The Claims | "Confirmation that the implementer read `AGENTS.md` and all six topic files" | AGENTS.md:35, AGENTS.md:38 | Different words ("states in its report") |
| 50 | 85 | The Claims | "Each changed file with its line count." | AGENTS.md:161-162, AGENTS.md:227 | Different words, same meaning |
| 51 | 87 | The Claims | "A claim without evidence counts as not made." | AGENTS.md:62-63 (ST-AG-02); internal 41, 138-139 | Different words |
| 52 | 91 | The design note | "The implementer answers these questions before any code" | AGENTS.md:122-123 (ST-AG-16) | Different words, same meaning |
| 53 | 91-92 | The design note | "Fix answers questions 1, 2 and 4, Feature answers all four, and Docs has no note." | AGENTS.md:123-124; internal 63 | Same words |
| 54 | 92 | The design note | "Tests are not a question here: ST-TEST-10 to ST-TEST-34 decide them." | internal 100, 106-108, 109-110 | **Conflict** inside judge.md (C3) |
| 55 | 94 | The design note | "Each answer is at most 3 lines and gives concrete facts." | none found | — |
| 56 | 94-95 | The design note | ""None" is an answer only with the fact that makes it so" | none found | — |
| 57 | 95-96 | The design note | "The judge fails a "none" that the diff contradicts." | none found | — |
| 58 | 98-99 | The design note Q1 | "The consumers; a new, an existing and a hybrid service" | none found | — |
| 59 | 99-100 | The design note Q1 | "how a service adopts it, with Stratum as its parent or next to a company framework" | none found as a requirement (ADR 0001 defines the setups) | — |
| 60 | 100 | The design note Q1 | "each change a consumer sees, or none with the test that proves it" | none found | — |
| 61 | 100-101 | The design note Q1 | "which new types are API and which carry `@StratumInternal`" | engineering.md:279 (ST-ENG-56); AGENTS.md:187-188; ADR 0009:58-59 | Different words |
| 62 | 101 | The design note Q1 | "the lines a service writes to use it" | none found | — |
| 63 | 102-103 | The design note Q2 | "What Stratum, Spring Boot or a library already provides, and whether it is reused" | AGENTS.md:191-192, AGENTS.md:198-199; engineering.md:26-28 (ST-ENG-06) | Different words |
| 64 | 103 | The design note Q2 | "the simplest way and one alternative, with why it was not chosen" | AGENTS.md:178 (ST-ENG-05), engineering.md:19-20 | Different words (alternative only here) |
| 65 | 103-104 | The design note Q2 | "the existing class it is modelled on" | none found | — |
| 66 | 104-105 | The design note Q2 | "its module and package, and why no package cycle results" | AGENTS.md:188-190; engineering.md:287 (ST-ENG-57) | Different words |
| 67 | 105 | The design note Q2 | "each key with its default and a real case ..., a Spring Boot key first" | engineering.md:208-212 (ST-ENG-34); AGENTS.md:193-195 | Different words |
| 68 | 105 | The design note Q2 | "and the Java customizer (ST-ENG-34)" | engineering.md:196-198; documentation.md:44-47 (ST-DOC-05) | Different words |
| 69 | 106 | The design note Q3 | "The eight parameters of ST-AG-14" | AGENTS.md:94-95 | Pointer to same rule; AGENTS states the list |
| 70 | 106-107 | The design note Q3 | "the hostile inputs, each written as a test" | none found (security.md:61-64 is related taint tracking, not a test requirement) | — |
| 71 | 107-108 | The design note Q3 | "for web code, the table of cases, each tested on both stacks" | ADR 0006:43 | Different words |
| 72 | 108 | The design note Q3 | "each thread hop and how the logging and tracing context moves with it" | engineering.md:268 (ST-ENG-53) | Different words |
| 73 | 108 | The design note Q3 | "for code that parses, matches a regex or masks, the test with a large input" | none found (testing.md:31-32 only sets the data form; engineering.md:270 related) | — |
| 74 | 109 | The design note Q4 | "The rule IDs it touches" | internal 60-61 (brief) | — |
| 75 | 109-110 | The design note Q4 | "each owner decision with the test that protects it" | none found | — |
| 76 | 110 | The design note Q4 | "each Spring Boot or library fact with its source" | none found | — |
| 77 | 110 | The design note Q4 | "the documents it updates" | documentation.md:28-30 (ST-DOC-01); AGENTS.md:228-229 | Different words |
| 78 | 114 | What the judge does | "One judge works through this checklist in order." | none found | — |
| 79 | 114-115 | What the judge does | "It collects every finding before it gives the verdict" | none found (internal exception 130) | — |
| 80 | 119 | What the judge does (table) | "Docs \| 1, 2, 3 and 5" | none found | — |
| 81 | 120 | What the judge does (table) | "Fix \| 1 to 5" | none found | — |
| 82 | 121 | What the judge does (table) | "Feature, design note \| 1, 2, 3 and 5, applied to the note" | none found | — |
| 83 | 122 | What the judge does (table) | "Feature, code \| 1 to 5" | none found | — |
| 84 | 124-125 | What the judge does | "When every changed file is Markdown, text or AsciiDoc ... the judge skips step 4" | AGENTS.md:217 | Different words |
| 85 | 127-128 | Step 1 Gate | "The Claims are complete, with the design note on Fix and Feature" | AGENTS.md:225-227; internal 130-131 | Different words |
| 86 | 128-129 | Step 1 Gate | "CI is green at the head commit, and every Codex and Sonar finding is answered" | AGENTS.md:236-237 (merge readiness), AGENTS.md:138 | Different words |
| 87 | 129 | Step 1 Gate | "the judge reads Sonar through the pull request's GitHub checks" | none found | — |
| 88 | 129-130 | Step 1 Gate | "When the gate fails, the verdict is FAIL with that one finding, ... checks nothing else" | none found | — |
| 89 | 130-131 | Step 1 Gate | "A missing design note on Fix or Feature is a blocking finding that fails the gate." | internal 127 | — |
| 90 | 133-134 | Step 2 Requirements | "write your own list of every requirement ... before reading the implementer's list" | none found | — |
| 91 | 134-135 | Step 2 Requirements | "neither met nor declared out of scope is a **blocking** finding" | review.md:14 ("a requirement not met") | Different words, same meaning |
| 92 | 135 | Step 2 Requirements | "Something built that the request did not ask for is a **should fix** finding." | AGENTS.md:74-75 (ST-AG-06); review.md:14 | **Conflict** (C4) |
| 93 | 136-137 | Step 2 Requirements | "Read the whole diff ..., each changed file in full where needed, and the callers" | review.md:20-21, review.md:30 | Different words |
| 94 | 138-139 | Step 2 Requirements | "A requirement is MET only with your own evidence ... If you could not verify it, it is NOT MET." | AGENTS.md:59-61 (ST-AG-01); internal 41 | Different words |
| 95 | 141 | Step 3 Design, rules | "The code matches the design note." | none found | — |
| 96 | 142-143 | Step 3 Design, rules | "Check the change against every rule: AGENTS.md, engineering ..., testing, documentation and review" | AGENTS.md:8 | Different words |
| 97 | 143-144 | Step 3 Design, rules | "Check the line count of every changed file against the limits in AGENTS.md." | AGENTS.md:161-162 ("The judge checks the counts.") | Different words, same meaning |
| 98 | 145-147 | Step 3 Design, rules | "For each new type, method, option and branch, name its use today ... should-fix" | engineering.md:19-22 (ST-ENG-05), engineering.md:88-90 (ST-ENG-15); AGENTS.md:178-181; review.md:14 | **Conflict** on severity (C5, see Unsure) |
| 99 | 147 | Step 3 Design, rules | "Each class or file added to fix a finding must be the simplest fix" | AGENTS.md:86-87 (ST-AG-12) | Different words, same meaning |
| 100 | 148-151 | Step 3 Design, rules | "each changed class does one job, ... no logic is copied ... is a should-fix finding" | review.md:35-37; AGENTS.md:182-186; engineering.md:10-11, 26-28 | Different words; **conflict** on severity (C6) |
| 101 | 152 | Step 3 Design, rules | "Read each changed document against the code at the head commit" | documentation.md:61-62 | Different words, same meaning |
| 102 | 153-154 | Step 3 Design, rules | "check that every file that states or depends on that rule was updated" | AGENTS.md:136-137 (ST-AG-17) | **Conflict** (C7) |
| 103 | 156 | Step 4 Behaviour | "Run the build and the tests, and any small check or probe you need." | none found | — |
| 104 | 157-159 | Step 4 Behaviour | "Look for what the Claims do not mention: bugs, edge cases ..." | review.md:31-32; testing.md:11-12 (ST-TEST-02) | Different words |
| 105 | 160-161 | Step 4 Behaviour | "Plant faults ... break the code on purpose ... check that a test fails. Restore" | testing.md:8-10 (ST-TEST-01, "The judge repeats this with its own faults") | Different words (same examples) |
| 106 | 161-162 | Step 4 Behaviour | "Record how many faults you planted and how many the tests caught." | internal 197 | — |
| 107 | 163-164 | Step 5 Security | "Check ST-SEC-01 to ST-SEC-06 on every change, and each other rule ... the change touches" | security.md:59 | Same words |
| 108 | 164-165 | Step 5 Security | "on the trigger list ..., also follow each untrusted value from where it enters" | security.md:61-64 | Different words, same meaning |
| 109 | 167-168 | What the judge does (verdict) | "**PASS**, or **FAIL** with numbered findings, each blocking, should fix or minor" | review.md:10; internal 196 | Different words |
| 110 | 168-169 | What the judge does (verdict) | "passes only when no blocking or should-fix finding remains, apart from ... deferred" | review.md:14-16 | **Conflict** (C8) |
| 111 | 171 | What the judge does | "Do not invent findings to look thorough, and do not pass work to be agreeable." | none found | — |
| 112 | 175 | The finding bar | "A behaviour finding comes with a failing test that the judge ran" | review.md:38-39 ("a failing test or a reproduced wrong result") | Different words (see Unsure) |
| 113 | 176 | The finding bar | "A structure, naming or documentation finding quotes the exact lines." | review.md:39-40 | Same words |
| 114 | 177 | The finding bar | "A case that is only theoretical is a Note, not a finding, and is never built." | review.md:40 (same words); AGENTS.md:70-71 (ST-AG-04) | Same words in review.md; different words in AGENTS.md |
| 115 | 181-182 | Deferring a finding | "Only the judge may move a real finding that is not blocking to a GitHub issue" | review.md:15 | Different words (review.md: should-fix only) |
| 116 | 182 | Deferring a finding | "The verdict says so, and the coordinator opens the issue and tells the owner in one line." | review.md:15 ("the owner is told in one line") | Different words |
| 117 | 182-183 | Deferring a finding | "The owner may object; the finding is then fixed in the pull request." | review.md:15 ("may object") | Different words |
| 118 | 183 | Deferring a finding | "The implementer never defers a finding." | review.md:15 ("The implementer cannot defer it") | Different words |
| 119 | 184 | Deferring a finding | "A deferred finding does not hold the PASS. If the owner objects, the PASS waits" | internal 169 | — |
| 120 | 188-189 | The verdict | "posts its verdict as one comment: on the pull request, or ... where the note was posted" | internal 41-42 | — |
| 121 | 189 | The verdict | "It never writes the verdict as a file ..., and changes nothing else." | AGENTS.md:49; internal 42 | Different words |
| 122 | 190-191 | The verdict | "at most about 100 lines ... each gap-list item takes one line and each finding at most three" | none found | — |
| 123 | 193-194 | The verdict (template) | "Title: pull request number or design note, branch, head commit, track and review step" | none found | — |
| 124 | 195 | The verdict (template) | "Gap list: each requirement ..., MET, PARTLY or NOT MET, with the judge's evidence" | internal 138-139 (MET / NOT MET only) | — |
| 125 | 196 | The verdict (template) | "Findings (FAIL only): numbered, with severity, evidence and the fix expected." | internal 167-168 | — |
| 126 | 197 | The verdict (template) | "Planted faults: how many were planted, how many were caught, and which survived." | internal 161-162 | — |
| 127 | 198 | The verdict (template) | "Notes that are not findings, if any." | AGENTS.md:71; internal 177 | Different words |
| 128 | 200 | The verdict | "Quote the implementer only where a claim is wrong." | none found | — |
| 129 | 200 | The verdict | "No praise and no summary of the change." | review.md:48 ("No praise and no restating the change.") | Different words, same meaning |
| 130 | 204-205 | Rounds | "the implementer fixes each finding and shows the proof ... Disagreement without evidence is not an answer." | AGENTS.md:67-69 (ST-AG-04), AGENTS.md:85-86 (ST-AG-12) | Different words |
| 131 | 206-207 | Rounds | "A fresh judge for the same review step, given the earlier verdict, ... new comment." | AGENTS.md:50; internal 16 | Different words |
| 132 | 207-208 | Rounds | "An answer is accepted only when the judge reproduces it." | none found | — |
| 133 | 209-211 | Rounds | "If findings remain after two rounds ..., the owner decides ... there is no third round" | none found | — |
| 134 | 212 | Rounds | "A FAIL at the gate does not count as a round." | none found | — |
| 135 | 216 | Merging | "The owner merges a pull request only when the judge's PASS is for its current head commit." | AGENTS.md:232, AGENTS.md:238 | Different words, same meaning |
| 136 | 216-217 | Merging | "Any later commit needs the judge to confirm that the PASS still holds." | AGENTS.md:232 | Different words |
| 137 | 217-218 | Merging | "The coordinator says "Ready to merge at <sha>" once" | AGENTS.md:236-237 | Same words (judge.md omits the conditions and points to AGENTS.md) |

## Totals

Total items: **137**.

| Section | Items | # |
|---|---|---|
| Intro | 1 | 1 |
| Which changes need a judge | 2 | 2-3 |
| The judge pool | 8 | 4-11 |
| Who starts the judge, and when (incl. judge prompt) | 18 | 12-29 |
| The implementer brief | 11 | 30-40 |
| The Claims | 11 | 41-51 |
| The design note | 26 | 52-77 |
| What the judge does (table, steps 1-5, verdict paragraph, honesty) | 34 | 78-111 |
| The finding bar | 3 | 112-114 |
| Deferring a finding | 5 | 115-119 |
| The verdict | 10 | 120-129 |
| Rounds | 5 | 130-134 |
| Merging | 3 | 135-137 |

- Also stated outside judge.md (AGENTS.md, the other five topic files or an ADR): **89** items.
- Stated only in judge.md (none found, or only an internal repeat): **48** items (5, 6, 8, 11, 18, 22, 24, 25, 32, 33,
  55-60, 62, 65, 70, 73-76, 78-83, 87-90, 95, 103, 106, 111, 119, 120, 122-126, 128, 132-134).
- Repeated inside judge.md: 4/131, 7/24, 10/11/17, 14/39, 20/51/94, 21/120/121, 38/53, 85/89, 106/126, 109/125,
  110/119, 114/127.

### Conflicts (following one side breaks the other)

- **C1 (#16)** judge.md:34-35 "Until a PASS arrives, the implementer ... pushes nothing." vs judge.md:204-206 "After
  a FAIL, the implementer fixes each finding ... reviews once more at the new head commit" (a fix needs a push before
  any PASS) and AGENTS.md:235 "Nothing is pushed to a pull request while a judge reviews it." (narrower window).
- **C2 (#27)** judge.md:50-51 "The implementer may do the other coordinator duties, such as the track and the brief"
  vs AGENTS.md:99-100 "When there is no coordinator, the implementer does the coordinator's duties except one".
- **C3 (#54, internal)** judge.md:92 "Tests are not a question here: ST-TEST-10 to ST-TEST-34 decide them." vs
  judge.md:100 "or none with the test that proves it", :106-108 "the hostile inputs, each written as a test ... each
  tested on both stacks ... the test with a large input", :109-110 "each owner decision with the test that protects
  it". (Also: the range excludes ST-TEST-01 to 04 and 40 to 41.)
- **C4 (#92)** judge.md:135 "Something built that the request did not ask for is a **should fix** finding." vs
  review.md:14 Blocking = "a broken rule in AGENTS.md" with AGENTS.md:74-75 (ST-AG-06) "Do not add files, tools,
  scripts, dependencies or features the request did not call for".
- **C5 (#98, weaker)** judge.md:145-147 a type/option "with no use ... is a should-fix finding" vs review.md:14
  Blocking = "a broken rule in AGENTS.md" with AGENTS.md:180-181 "no dead code, no abstraction without a second use".
- **C6 (#100)** judge.md:150-151 "A class with a second job, copied logic or repeated test setup is a should-fix
  finding" vs review.md:14 Blocking = "a broken rule in AGENTS.md" with AGENTS.md:182-183 "When a class holds a second
  job, move that job ... Logic written in two places moves into one."
- **C7 (#102)** judge.md:153-154 "check that every file that states or depends on that rule was updated" vs
  AGENTS.md:136-137 "with every file that states or depends on that rule updated, or named with the reason it stays".
- **C8 (#110)** judge.md:168-169 "A change passes only when no blocking or should-fix finding remains, apart from a
  finding the judge has deferred" vs review.md:15 Should fix "Fixed before merge, unless the owner accepts it."

## Unsure

- **#1 (line 3)** "every agent follows them" is part of the file's framing sentence; counted because it requires
  something and is copied in four other topic files, but it could be read as a description.
- **Line 7** "It ... accepts nothing it has not verified." Not counted: it is in the descriptive intro; the same
  requirement is counted at #20 and #94.
- **#9 vs #131** "One judge per review step" (26) and "A fresh judge for the same review step" in each round (206)
  read together as one judge at a time per step; listed as two items, not as a conflict.
- **#79 vs #88** "collects every finding before it gives the verdict" vs "When the gate fails ... checks nothing
  else": an explicit exception, not counted as a conflict.
- **C5, C6** depend on reading review.md:14 "a broken rule in AGENTS.md" as covering the summary bullets of
  AGENTS.md "Engineering rules" (AGENTS.md:175-176 says the topic file wins where they differ, and the topic rules
  ST-ENG-05/ST-ENG-16 carry no severity). C4 is firmer because ST-AG-06 is a numbered AGENTS.md rule.
- **#112** review.md:38-39 admits "a failing test or a reproduced wrong result"; judge.md:175 requires "a failing
  test that the judge ran: the test, or its steps". "Its steps" may make them equivalent; marked different words,
  not conflict.
- **#115** review.md:15 attaches deferral to Should fix only; judge.md:181 says "a real finding that is not blocking"
  (also Minor). Minor never holds a merge (review.md:16), so no practical conflict; marked different words.
- **#58-#62, #63-#68, #69-#73, #74-#77** Each design-note question was split into its semicolon-separated parts
  because the parts have different counterparts elsewhere; a lister that counts one item per question gets 22
  fewer items (137 - 22 = 115 with 4 question items).
- **Pool table (#5-#7) and step table (#80-#83)** counted one item per row.
- **#18, #32** template fill-in fields (PR/note, track; item, track) counted as rules because they tell the reader
  what to include.
- **#96** step 3's list of rule files omits security.md (covered by step 5) and judge.md; noted, not a conflict.
