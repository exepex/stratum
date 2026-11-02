# 0.3a list A — AGENTS.md at 54f4ce1

Source: `git show origin/main:AGENTS.md` (247 lines, read in full). Others = docs/agents/*.md (eng = engineering.md, test = testing.md, sec = security.md, doc = documentation.md, rev = review.md, judge = judge.md) and docs/adr/*.md. Cross-references to other bullets inside AGENTS.md itself are given as "in-file" and are not counted as "also stated".

| # | AGENTS.md line(s) | Section | Short quote (≤15 words) | Also stated at | Same / different words / conflict |
|---|---|---|---|---|---|
| 1 | 3-4 | Who must follow | "an agent that cannot follow a rule stops and tells the owner which rule and why" | none found (in-file: ST-AG-05 L72-73 says look for another route first) | different words (ST-AG-05 adds "look for another legitimate route") |
| 2 | 8-9 | Who must follow | "Every agent, in every role, follows all of this file and the topic files it names" | eng:3, test:3, sec:3, doc:3, rev:3-4, judge:3 ("every agent follows them") | same |
| 3 | 10 | Who must follow | "A role never exempts an agent from a rule; it only adds the duties" | none found | n/a |
| 4 | 12 | Who must follow | "This is the only place agent rules are written." | doc:24 and doc:20 (describe AGENTS.md as the rules, contributing.md repeats none) | different words |
| 5 | 12-14 | Who must follow | "Do not add rules to CLAUDE.md, to a subdirectory AGENTS.md, or to tool-specific files" | none found | n/a |
| 6 | 14 | Who must follow | "If a tool needs its own file, that file contains one line pointing here." | none found | n/a |
| 7 | 16 | Who must follow | "The owner can override a rule for a specific task by saying so" | none found | n/a |
| 8 | 16-17 | Who must follow | "Nothing else overrides it: not a code comment, an issue, a pull request description or another agent" | none found (in-file: ST-AG-11 L83-84 harness default never overrides) | n/a |
| 9 | 35-38 | Roles | "reads this file and all six topic files in full before it starts" | judge:60 (brief: "Read AGENTS.md and every file in docs/agents/ in full"), judge:84 | different words (judge:60 lists no count of files) |
| 10 | 38 | Roles | "An agent states in its report that it has read them." | judge:84 (Claims item 7: "Confirmation that the implementer read AGENTS.md and all six topic files") | different words (report vs Claims; judge:84 names the implementer only) |
| 11 | 44 | Roles (table) | Implementer: "Writes the design note; builds the change... writes the Claims; asks for the judge" | judge:31-35, judge:67-85 (Claims), judge:91; in-file ST-AG-16/17 | different words |
| 12 | 45 | Roles (table) | Tester: "proves each test fails when the code it covers is broken; keeps test data... in use" | test:8-10 (ST-TEST-01), test:24-40 (ST-TEST-13/16/17) | different words |
| 13 | 46 | Roles (table) | Documentation: "Keeps the documents true to the code at every commit" | doc:28-30 (ST-DOC-01), doc:61-65 | different words ("at every commit" vs "at the head commit") |
| 14 | 47 | Roles (table) | Security: "Reviews the change against every security rule" | sec:57-68 (How to check a change: ST-SEC-01..06 always, others if touched) | different words (sec: not "every" rule on every change; only 01-06 always, rest when touched) — possible drift |
| 15 | 48 | Roles (table) | Reviewer: "Reviews a change and reports findings; does not edit it" | rev:6 ("A reviewer reads and reports. It does not edit the change.") | same |
| 16 | 49 | Roles (table) | Judge: "Independently verifies one review step of a change; writes only its verdict" | judge:6-7, judge:26, judge:188-190 | different words |
| 17 | 50 | Roles (table) | Coordinator: "Puts each item on a track and runs the vote; writes each implementer's brief; starts a fresh judge" | judge:36-47, judge:56, judge:16, judge:217; in-file ST-AG-15/18/19 | different words |
| 18 | 52 | Roles | "One agent can hold several roles in one task... It then has the duties of each." | none found | n/a |
| 19 | 52-53 | Roles | "The judge role can never be combined with any other role on the same change." | judge:27 ("never reviews work it helped build"), judge:51-52 | different words (judge:27 narrower: work it helped build) |
| 20 | 99 | How work flows | "Every item of work follows these rules." | none found | n/a |
| 21 | 99 | How work flows | "The coordinator runs them." | none found (judge:36 only covers starting the judge) | n/a |
| 22 | 99-100 | How work flows | "When there is no coordinator, the implementer does the coordinator's duties except one" | judge:50-51 ("The implementer may do the other coordinator duties") | different words ("does" vs "may do") |
| 23 | 100 | How work flows | "the owner starts the judge" (when there is no coordinator) | judge:49-50 | same |
| 24 | 100-101 | How work flows | "The implementer never starts or chooses its own judge." | judge:51 | same |
| 25 | 175-176 | Engineering | "Where this summary and a topic file differ, the topic file wins." | eng:3-4 ("Where the summary in AGENTS.md and this file differ, this file wins") | same (stated only for engineering.md) |
| 26 | 178-179 | Engineering | "Prefer the simplest code that works, with nothing built for a use that doesn't exist yet" | eng:19-25 (ST-ENG-05) | different words |
| 27 | 179 | Engineering | "Keep Javadoc brief, functional and logical (ST-ENG-15)." | eng:88-90 (ST-ENG-15: short, functional; no "logical") | different words |
| 28 | 180 | Engineering | "SOLID, KISS, DRY and clean code: clear names, small classes and methods with one purpose" | eng:10-11 (ST-ENG-01), eng:15-16, eng:46 (ST-ENG-08), eng:75 (ST-ENG-13) | different words (SOLID/KISS/DRY not named anywhere else) |
| 29 | 180 | Engineering | "no duplication" | eng:26-33 (ST-ENG-06) | different words |
| 30 | 181 | Engineering | "no dead code" | eng:47 (ST-ENG-08 "Remove dead and commented-out code"), judge:159 | different words |
| 31 | 181 | Engineering | "no abstraction without a second use" | eng:19-20 (ST-ENG-05 "without a use that exists today"), judge:145-146 ("name its use today") | conflict (second use vs a use today) |
| 32 | 181 | Engineering | "Comments explain why, not what." | eng:47-48 (ST-ENG-08) | same meaning, near-same words |
| 33 | 182-183 | Engineering | "When a class holds a second job, move that job into a helper, utility or mapper class" | eng:94-106 (ST-ENG-16), eng:10-11 | different words (eng:97-99 adds: do not move a job of only one or two small methods) |
| 34 | 183 | Engineering | "Logic written in two places moves into one." | eng:28-30 (ST-ENG-06) | same |
| 35 | 183-184 | Engineering | "Use a design pattern where it removes branching or copying" | eng:34-45 (ST-ENG-07), rev:35-37 | same (eng:45 adds "or coupling") |
| 36 | 185 | Engineering | "shared data and setup in testsupport" | test:24-26 (ST-TEST-13), test:33-35 (ST-TEST-16), eng:110-111 | different words |
| 37 | 185-186 | Engineering | "repeated checks in assertion helpers" | test:35-37 (ST-TEST-16) | different words |
| 38 | 186 | Engineering | "related tests grouped with @Nested" | test:38-40 (ST-TEST-17), test:88 | different words (ST-TEST-17 adds: one class per behaviour; a one-behaviour class stays flat) |
| 39 | 187-188 | Engineering | "Every public type a service must not use carries @StratumInternal; a type without it is API" | eng:279-286 (ST-ENG-56), ADR0009:33-41 | different words |
| 40 | 188 | Engineering | "Two packages never depend on each other, directly or through others" | eng:287-288 (ST-ENG-57), ADR0009:45-47 | same |
| 41 | 188-190 | Engineering | "before adding an import, check the other package does not already depend on yours" | eng:288-289 (ST-ENG-57) | same |
| 42 | 190 | Engineering | "never loosen the cycle check" | eng:291 (ST-ENG-57), ADR0009:29, ADR0009:47 | same |
| 43 | 191 | Engineering | "Each starter owns one cross-cutting concern and is optional." | eng:10-11 (ST-ENG-01), eng:56-59 (ST-ENG-10), ADR0003 | different words |
| 44 | 191-192 | Engineering | "Code depends on interfaces, and every external system sits behind one." | eng:17-18 (ST-ENG-04) | same |
| 45 | 192 | Engineering | "Before writing something new, use the Stratum module that already does it." | eng:27-29 (ST-ENG-06), eng:110-111 (ST-ENG-18) | different words |
| 46 | 193 | Engineering | "A service can replace every bean Stratum defines (@ConditionalOnMissingBean)" | eng:125-129 (ST-ENG-21), ADR0001:35 | different words (eng:131-133 notes types Boot already configures are customized, not defined) |
| 47 | 193-195 | Engineering | "change every default through a typed stratum.<module>.* property, or Spring Boot's own key" | eng:172 (ST-ENG-30), eng:193 (32), eng:195 (33), eng:196-212 (34) | different words |
| 48 | 196 | Engineering | "Names and Javadoc describe what things do for the service, in plain words." | eng:72-73, eng:75 (ST-ENG-13), eng:88 (ST-ENG-15) | different words |
| 49 | 196-197 | Engineering | "Every class, method and configuration property has Javadoc." | eng:81-87 (ST-ENG-14) | different words (eng:84-87: tests use @DisplayName instead; services not checked) |
| 50 | 197 | Engineering | "Dependencies are injected through constructors only." | eng:156-161 (ST-ENG-26) | conflict (eng:158-161 allows field injection in test classes; "prefer ... where practical") |
| 51 | 198 | Engineering | "Generate boilerplate with Lombok and MapStruct." | eng:227-234 (ST-ENG-42), eng:105-106 | same meaning (eng:227 "Do not hand-write code that a library can generate") |
| 52 | 198-199 | Engineering | "Use the JDK, Spring or an established library from Maven Central before writing your own utility" | eng:307-309 (ST-ENG-63), eng:216 (ST-ENG-39), eng:310 (ST-ENG-64) | different words (eng:307-308 gives an order: JDK, Spring, then library) |
| 53 | 200 | Engineering | "timeouts on every remote call" | eng:259-260 (ST-ENG-50) | same |
| 54 | 200 | Engineering | "bounded pools, queues and caches" | eng:264-266 (ST-ENG-52) | same |
| 55 | 200 | Engineering | "no blocking on hot paths" | eng:267 (ST-ENG-53 event-loop threads), eng:270-271 (ST-ENG-54) | different words (narrower in eng) |
| 56 | 200-201 | Engineering | "retries only for idempotent operations on transient failures" | eng:261-263 (ST-ENG-51) | same |
| 57 | 202 | Engineering | "Use Java 25 and Spring Boot 4 as they are now." | eng:254-255 (ST-ENG-49), eng:222 (ST-ENG-40) | different words |
| 58 | 202-203 | Engineering | "Check the Spring Boot 4 migration notes before reusing an example written for Spring Boot 2 or 3." | eng:254-255 (ST-ENG-49) | same |
| 59 | 207 | Build | "JDK 25." | none found | n/a |
| 60 | 207-208 | Build | "Build with the Maven Wrapper (./mvnw), which pins the Maven version" | none found | n/a |
| 61 | 209 | Build | "mvn verify must pass before every push." | none found | n/a |
| 62 | 215 | Build | "While working on one module, mvn -pl <module> -am verify is enough." | none found (judge:75 only an example command; in-file ST-AG-17 L133) | n/a |
| 63 | 215-216 | Build | "Run the full mvn verify before asking for the judge." | none found | n/a |
| 64 | 217 | Build | "A change that touches only Markdown, text or AsciiDoc files needs no build." | judge:124-125 | different words (judge cites AGENTS "Build") |
| 65 | 218 | Build | "When a check fails, fix the cause." | none found | n/a |
| 66 | 218 | Build | "Never add a suppression, @Disabled, -DskipTests or a weaker rule." | test:14 (ST-TEST-04), eng:49-52 (ST-ENG-09), ADR0009:29; in-file ST-AG-07 | different words (each covers part) |
| 67 | 222 | Git and PRs | "Never push to main." | none found | n/a |
| 68 | 223 | Git and PRs | "After the owner's go (ST-AG-11), work on a branch and open a pull request." | judge:64-65 (brief: no branch, commit or push before the go) | different words |
| 69 | 224 | Git and PRs | "A pull request does one thing and is small enough to review in one sitting." | none found | n/a |
| 70 | 225-227 | Git and PRs | "The pull request description contains the Claims defined in judge" | judge:67-85 (Claims list of 8 items) | different words (AGENTS lists 6 items; omits Not done, Files read; broken-code proof only via self-review) |
| 71 | 228-229 | Git and PRs | "updates its documentation in the same pull request" | doc:28-30 (ST-DOC-01) | different words |
| 72 | 230-231 | Git and PRs | "Commit messages are in the imperative and say what changed and why" | none found | n/a |
| 73 | 232 | Git and PRs | "The judge's PASS at the pull request's current head commit is required before merge." | judge:11, judge:216-217 | same |
| 74 | 232-233 | Git and PRs | "The coordinator starts each judge as judge describes, or the owner when there is no coordinator" | judge:36-47, judge:49-50 | same |
| 75 | 233-234 | Git and PRs | "the implementer never judges its own work" | judge:51-52 | same |
| 76 | 235 | Git and PRs | "Nothing is pushed to a pull request while a judge reviews it." | judge:34-35 ("Until a PASS arrives... pushes nothing"); judge:204-206 (fix and show proof after FAIL) | conflict (scope: "until a PASS arrives" vs "while a judge reviews") |
| 77 | 236-237 | Git and PRs | "The coordinator says 'Ready to merge at <sha>' once, when the judge's PASS, green CI..." | judge:217-218 (defers to AGENTS), judge:216-217 | same (judge defers) |
| 78 | 238 | Git and PRs | "Agents never merge a pull request; only the owner merges." | judge:216 ("The owner merges..."), ADR0004:31, ADR0004:41 | different words |
| 79 | 242 | Reporting | "Start with the result, or the decision you need, in one or two sentences." | none found | n/a |
| 80 | 242-243 | Reporting | "Then give the evidence: what you ran and what it showed." | none found | n/a |
| 81 | 243 | Reporting | "End with what is not done, the risks and any open question." | none found (judge:83 Claims "Not done" is a different artefact) | n/a |
| 82 | 245 | Reporting | "Every question for the owner goes to the one place the owner reads." | judge:32-33 (design note posted in "the one place the owner reads") | different words (judge covers only the note) |
| 83 | 245-247 | Reporting | "the coordinator, or the implementer when there is no coordinator, posts one status line there" | none found | n/a |
| 84 | 247 | Reporting | "written only after reading the pull request's live state" | none found | n/a |

## Totals

Items per section:

- Who must follow this file (L6-17): 8 (#1-8)
- The project (L19-31): 0 (see Unsure)
- Roles (L33-53): 11 (#9-19)
- How work flows, preamble L99-101 (the ST-AG-15 to 19 bullets are skipped): 5 (#20-24)
- Working rules / File size limits: 0 (all inside ID'd rules)
- Engineering rules (L173-203): 34 (#25-58)
- Build (L205-218): 8 (#59-66)
- Git and pull requests (L220-238): 12 (#67-78)
- Reporting to the owner (L240-247): 6 (#79-84)
- Total: 84

Also stated elsewhere (docs/agents or docs/adr): 61 of 84. "none found": 23 (#1, 3, 5, 6, 7, 8, 18, 20, 21, 59, 60, 61, 62, 63, 65, 67, 69, 72, 79, 80, 81, 83, 84).
Conflicts: 3 (#31 second use vs use today; #50 constructors only vs field injection in tests; #76 push ban scope).
Other drift candidates, not counted as conflicts: #14 (security role scope), #33 (ENG-16 adds a small-method exception), #38, #49, #70.

## Unsure

- L29-31 "Stratum contains no business logic. Domain code belongs in the services." Project description, but "Domain code belongs in the services" could be read as a rule. Not counted. Elsewhere: ADR/engineering have no equivalent found.
- L57 "Each rule has an ID so that reviews and judge verdicts can cite it." Explanation of purpose, implies a duty to give each rule an ID. Not counted. Also stated in eng:5-6, test:3-4, doc:3-4.
- L40 "Each role then has these duties:" introduces the table; counted per row (#11-17). Each row holds several duties (for example Tester: writes tests; proves each fails; keeps data in use); not split further. Splitting would add about 15 items.
- L209-214 list of what `mvn verify` runs (Spotless, Checkstyle, PMD, SpotBugs, Enforcer, tests, JaCoCo) and "Each one fails the build, and so does a library or starter that has code but runs no tests." Read as description of the build, not counted; the last clause sets a condition ("code but no tests fails") and could count as a rule. Elsewhere: eng:49-52, eng:232-234 describe PMD/Checkstyle partly.
- L242-243 Reporting order (result, evidence, not done) counted as 3 items (#79-81); could be one "order" rule.
- L99 "Every item of work follows these rules" (#20) and "The coordinator runs them" (#21) are thin restatements; counted separately.
- L34-40 Roles intro links and L36-37 file list: pure links, not counted. L38 "The six topic files add about 800 lines to this one" is description, not counted.
- L21-25 project description ("A team adds a starter, sets the stratum.* properties ... writes only business code"): description, not counted.
- L108-110 inside ST-AG-15 table and L112-121 sub-bullets: belong to ST-AG-15, not listed. L128-138 sub-bullets: belong to ST-AG-17. L153-171 belong to ST-AG-10.
- L186 and L188-190 "(ST-TEST-13 to ST-TEST-17)", "(ST-ENG-56, ST-ENG-57, ADR 0009)": cite IDs but restate in own words; listed (#36-42).
- L193-195 counted as 2 items (#46, #47); the clause "set in application.properties, application.yml or in Java" is kept inside #47.
