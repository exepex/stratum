# 0.3a list B — AGENTS.md at 54f4ce1

Source: `git show origin/main:<path>` at 54f4ce1. AGENTS.md read in full (247 lines). Searched: all six files in
docs/agents/ and all nine files in docs/adr/ on origin/main. Text inside ST-AG-01..19 bullets (lines 59-95, 103-147)
and under "File size limits (ST-AG-10)" (lines 149-171) is excluded as owned by that ID.

| # | AGENTS.md line(s) | Section | Short quote (≤15 words) | Also stated at | Same / different words / conflict |
|---|---|---|---|---|---|
| 1 | 3-4 | Intro | "an agent that cannot follow a rule stops and tells the owner which rule and why" | none found | — |
| 2 | 8-10 | Who must follow | "Every agent, in every role, follows all of this file and the topic files" | engineering.md:3, documentation.md:3, judge.md:3, security.md:3, testing.md:3 ("every agent follows them"); review.md:3-4 | different words |
| 3 | 10 | Who must follow | "A role never exempts an agent from a rule; it only adds the duties" | none found | — |
| 4 | 12 | Who must follow | "This is the only place agent rules are written." | documentation.md:24 (rules are in `AGENTS.md` and `docs/agents/`) | different words (literal tension, see Unsure) |
| 5 | 12 | Who must follow | "`CLAUDE.md` imports this file and holds nothing else." | none found | — |
| 6 | 12-14 | Who must follow | "Do not add rules to `CLAUDE.md`, to a subdirectory `AGENTS.md`, or tool-specific files" | none found | — |
| 7 | 14 | Who must follow | "If a tool needs its own file, that file contains one line pointing here." | none found | — |
| 8 | 16 | Who must follow | "The owner can override a rule for a specific task by saying so" | none found | — |
| 9 | 16-17 | Who must follow | "Nothing else overrides it: not a code comment, an issue, ... or another agent." | none found | — |
| 10 | 21-22 | The project | "the exact version is set once, in `stratum-parent`" | adr/0005:26-27 (Spring Boot version written once in the root `pom.xml`); engineering.md:296-300 (ST-ENG-60: root `pom.xml`, "and so `stratum-parent`", inherits Boot parent) | conflict (ADR 0005 names the root `pom.xml`, not `stratum-parent`) |
| 11 | 31 | The project | "Stratum contains no business logic. Domain code belongs in the services." | none found | — |
| 12 | 35-37 | Roles | "Every agent ... reads this file and all six topic files in full before it starts" | judge.md:60 (brief: read AGENTS.md and every file in docs/agents/ in full); judge.md:84 (Claims item 7) | different words |
| 13 | 38 | Roles | "An agent states in its report that it has read them." | judge.md:84 (Claims "Files read", implementer only) | different words (narrower: implementer's Claims) |
| 14 | 44 | Roles (Implementer) | "Writes the design note" | judge.md:91 (implementer answers the questions before any code) | different words |
| 15 | 44 | Roles (Implementer) | "builds the change, its tests and its documentation" | judge.md:31 ("finishes the work"); documentation.md:28-30 (ST-DOC-01 docs in same PR) | different words |
| 16 | 44 | Roles (Implementer) | "reviews its own change (ST-AG-17)" | judge.md:31 | different words |
| 17 | 44 | Roles (Implementer) | "writes the Claims" | judge.md:69 ("written by the implementer and updated each round") | different words |
| 18 | 44 | Roles (Implementer) | "asks for the judge" | judge.md:34 (asks the coordinator), judge.md:49 (asks the owner) | different words |
| 19 | 45 | Roles (Tester) | "Writes or reviews tests" | none found | — |
| 20 | 45 | Roles (Tester) | "proves each test fails when the code it covers is broken" | testing.md:8-10 (ST-TEST-01) | different words |
| 21 | 45 | Roles (Tester) | "keeps test data, fixtures, assertion helpers and `@Nested` groups in use" | testing.md:24-26, 33-37, 38-40 (ST-TEST-13, 16, 17) | different words |
| 22 | 46 | Roles (Documentation) | "Keeps the documents true to the code at every commit" | documentation.md:61-62 (read against the code at the head commit) | different words |
| 23 | 47 | Roles (Security) | "Reviews the change against every security rule" | security.md:59, 66-68 (ST-SEC-01..06 always; others only when touched) | different words (security.md narrows "every") |
| 24 | 48 | Roles (Reviewer) | "Reviews a change and reports findings" | review.md:6 ("A reviewer reads and reports.") | different words |
| 25 | 48 | Roles (Reviewer) | "does not edit it" | review.md:6 ("It does not edit the change.") | same |
| 26 | 49 | Roles (Judge) | "Independently verifies one review step of a change" | judge.md:7, 16, 26 | different words |
| 27 | 49 | Roles (Judge) | "writes only its verdict" | judge.md:41-42, 188-189 ("change nothing else") | different words |
| 28 | 50 | Roles (Coordinator) | "Puts each item on a track and runs the vote" | none found (only ST-AG-15 in AGENTS.md) | — |
| 29 | 50 | Roles (Coordinator) | "writes each implementer's brief" | judge.md:56 | different words |
| 30 | 50 | Roles (Coordinator) | "starts a fresh judge from the pool for each review step" | judge.md:16, 36-37 | different words |
| 31 | 50 | Roles (Coordinator) | "says when a pull request is ready to merge" | judge.md:217-218 | different words |
| 32 | 50 | Roles (Coordinator) | "proposes a stronger check for each miss" | none found (judge.md:46 only cites ST-AG-19) | — |
| 33 | 52 | Roles | "One agent can hold several roles in one task ... has the duties of each" | none found | — |
| 34 | 52-53 | Roles | "The judge role can never be combined with any other role on the same change." | judge.md:27 (never reviews work it helped build); judge.md:51-52 | different words |
| 35 | 99 | How work flows | "Every item of work follows these rules." | none found | — |
| 36 | 99 | How work flows | "The coordinator runs them." | none found | — |
| 37 | 99-100 | How work flows | "When there is no coordinator, the implementer does the coordinator's duties except one" | judge.md:49-51 | different words |
| 38 | 100-101 | How work flows | "The implementer never starts or chooses its own judge." | judge.md:51 | same |
| 39 | 175 | Engineering rules | "These points summarise the topic files and apply to every change." | none found | — |
| 40 | 175-176 | Engineering rules | "Where this summary and a topic file differ, the topic file wins." | engineering.md:3-4 | same |
| 41 | 178-179 | Engineering rules | "Prefer the simplest code that works, with nothing built for a use that doesn't exist" | engineering.md:19-25 (ST-ENG-05) | different words |
| 42 | 179 | Engineering rules | "Keep Javadoc brief, functional and logical (ST-ENG-15)." | engineering.md:88-90 (ST-ENG-15) | different words |
| 43 | 180 | Engineering rules | "SOLID, KISS, DRY and clean code" | none found (SOLID/KISS/DRY not named elsewhere; ST-ENG-08 at engineering.md:46 is "Clean code") | — |
| 44 | 180 | Engineering rules | "clear names" | engineering.md:75-80 (ST-ENG-13) | different words |
| 45 | 180 | Engineering rules | "small classes and methods with one purpose" | engineering.md:10-11 (ST-ENG-01), 46 (ST-ENG-08) | different words |
| 46 | 180 | Engineering rules | "no duplication" | engineering.md:26-29 (ST-ENG-06) | different words |
| 47 | 180-181 | Engineering rules | "no dead code" | engineering.md:47 (ST-ENG-08) | different words |
| 48 | 181 | Engineering rules | "no abstraction without a second use" | engineering.md:19-21 (ST-ENG-05: no type/helper "without a use that exists today"); engineering.md:94-97 (ST-ENG-16 moves a second job out with one use) | conflict (one use today vs a second use) |
| 49 | 181 | Engineering rules | "Comments explain why, not what." | engineering.md:47-48 (ST-ENG-08), 90 (ST-ENG-15) | different words |
| 50 | 182-183 | Engineering rules | "When a class holds a second job, move that job into a helper, utility or mapper" | engineering.md:94-106 (ST-ENG-16) | different words (AGENTS omits ST-ENG-16's "too small to split" limit) |
| 51 | 183 | Engineering rules | "Logic written in two places moves into one." | engineering.md:28-29 (ST-ENG-06) | different words |
| 52 | 183-184 | Engineering rules | "Use a design pattern where it removes branching or copying" | engineering.md:34-45 (ST-ENG-07, also "coupling"); review.md:36 | different words |
| 53 | 185 | Engineering rules | "Tests get the same care" | testing.md:86-89 (ST-TEST-41) | different words |
| 54 | 185 | Engineering rules | "shared data and setup in `testsupport`" | testing.md:24-26 (ST-TEST-13), 33-34 (ST-TEST-16) | different words |
| 55 | 185-186 | Engineering rules | "repeated checks in assertion helpers" | testing.md:34-36 (ST-TEST-16) | different words |
| 56 | 186 | Engineering rules | "related tests grouped with `@Nested`" | testing.md:38-40 (ST-TEST-17) | different words |
| 57 | 187 | Engineering rules | "Every public type a service must not use carries `@StratumInternal`" | engineering.md:279-281 (ST-ENG-56); adr/0009:33-35 | different words |
| 58 | 187-188 | Engineering rules | "a type without it is API and exposes only API types" | engineering.md:282-284 (only API, JDK and library types); adr/0009:36, 39-40 | conflict (AGENTS omits JDK and library types) |
| 59 | 188-189 | Engineering rules | "Two packages never depend on each other, directly or through others" | engineering.md:287 (ST-ENG-57); adr/0009:45-46 | same |
| 60 | 189 | Engineering rules | "before adding an import, check the other package does not already depend on yours" | engineering.md:288-289 (ST-ENG-57) | different words |
| 61 | 189-190 | Engineering rules | "never loosen the cycle check" | engineering.md:291 (ST-ENG-57); adr/0009:47 | same |
| 62 | 191 | Engineering rules | "Each starter owns one cross-cutting concern" | engineering.md:10 (ST-ENG-01), 56-57 (ST-ENG-10) | different words |
| 63 | 191 | Engineering rules | "and is optional" | engineering.md:57 (ST-ENG-10 "A service only gets what it adds"); adr/0003:22-25, 31 | different words |
| 64 | 191 | Engineering rules | "Code depends on interfaces" | engineering.md:17 (ST-ENG-04 title) | different words |
| 65 | 191-192 | Engineering rules | "every external system sits behind one" | engineering.md:17-18 (ST-ENG-04) | same |
| 66 | 192 | Engineering rules | "Before writing something new, use the Stratum module that already does it." | engineering.md:26-28 (ST-ENG-06), 110-111 (ST-ENG-18) | different words |
| 67 | 193 | Engineering rules | "A service can replace every bean Stratum defines (`@ConditionalOnMissingBean`)" | engineering.md:125-126 (ST-ENG-21); adr/0001:35-36 | different words |
| 68 | 193-194 | Engineering rules | "change every default through a typed `stratum.<module>.*` property, or Spring Boot's own key" | engineering.md:172 (ST-ENG-30), 195 (ST-ENG-33), 208-212 (ST-ENG-34) | different words |
| 69 | 194-195 | Engineering rules | "set in `application.properties`, in `application.yml` or in Java" | engineering.md:193 (ST-ENG-32), 196-198 (ST-ENG-34); documentation.md:44-46 (ST-DOC-05); adr/0003:42-43 | different words |
| 70 | 196 | Engineering rules | "Names and Javadoc describe what things do for the service, in plain words." | engineering.md:72-73, 75-76 (ST-ENG-13), 88 (ST-ENG-15) | different words |
| 71 | 196-197 | Engineering rules | "Every class, method and configuration property has Javadoc." | engineering.md:81-87 (ST-ENG-14: tests use `@DisplayName` instead); testing.md:86-87 (ST-TEST-41) | conflict (topic files exempt test code) |
| 72 | 197 | Engineering rules | "Dependencies are injected through constructors only." | engineering.md:156-161 (ST-ENG-26: field injection allowed in test classes); testing.md:87-88 (ST-TEST-41) | conflict ("only" vs test exception) |
| 73 | 198 | Engineering rules | "Generate boilerplate with Lombok and MapStruct." | engineering.md:227-234 (ST-ENG-42), 105-106 (ST-ENG-16) | different words |
| 74 | 198-199 | Engineering rules | "Use the JDK, Spring or an established library from Maven Central before writing your own" | engineering.md:307-309 (ST-ENG-63), 216 (ST-ENG-39), 310 (ST-ENG-64) | different words |
| 75 | 200 | Engineering rules | "timeouts on every remote call" | engineering.md:259 (ST-ENG-50) | same |
| 76 | 200 | Engineering rules | "bounded pools, queues and caches" | engineering.md:264-266 (ST-ENG-52); security.md:40-41 (ST-SEC-12) | different words |
| 77 | 200 | Engineering rules | "no blocking on hot paths" | engineering.md:267 (ST-ENG-53 event-loop threads), 270-271 (ST-ENG-54) | different words |
| 78 | 201 | Engineering rules | "retries only for idempotent operations on transient failures" | engineering.md:261-263 (ST-ENG-51) | different words |
| 79 | 202 | Engineering rules | "Use Java 25 and Spring Boot 4 as they are now." | engineering.md:222-224 (ST-ENG-40), 254-255 (ST-ENG-49); adr/0007:24 | different words |
| 80 | 202-203 | Engineering rules | "Check the Spring Boot 4 migration notes before reusing an example ... Boot 2 or 3" | engineering.md:255 (ST-ENG-49) | same |
| 81 | 207 | Build | "JDK 25." | adr/0007:24 (release supports Java 25) | different words |
| 82 | 207 | Build | "Build with the Maven Wrapper (`./mvnw`), which pins the Maven version" | adr/0005:40-41 (Maven version in `maven-wrapper.properties`) | different words |
| 83 | 209 | Build | "`mvn verify` must pass before every push." | none found | — |
| 84 | 214 | Build | "Each one fails the build, and so does a library or starter ... runs no tests." | none found for "no tests fails" (checks described at engineering.md:49-52, 232-234; testing.md:65-66, 83-84) | — |
| 85 | 215 | Build | "While working on one module, `mvn -pl <module> -am verify` is enough." | none found | — |
| 86 | 215-216 | Build | "Run the full `mvn verify` before asking for the judge." | none found | — |
| 87 | 217 | Build | "A change that touches only Markdown, text or AsciiDoc files needs no build." | judge.md:124-125 | different words |
| 88 | 218 | Build | "When a check fails, fix the cause." | none found | — |
| 89 | 218 | Build | "Never add a suppression, `@Disabled`, `-DskipTests` or a weaker rule." | engineering.md:51-52 (ST-ENG-09); testing.md:14 (ST-TEST-04) | different words |
| 90 | 222 | Git and pull requests | "Never push to `main`." | none found | — |
| 91 | 223 | Git and pull requests | "After the owner's go (ST-AG-11), work on a branch and open a pull request." | judge.md:64-65 (brief) | different words |
| 92 | 224 | Git and pull requests | "A pull request does one thing and is small enough to review in one sitting." | none found | — |
| 93 | 225-227 | Git and pull requests | "The pull request description contains the Claims defined in judge" | judge.md:69-85 | different words (AGENTS lists a subset of the 8 items) |
| 94 | 228-229 | Git and pull requests | "A change to behaviour, a default, a property or a public API updates its documentation" | documentation.md:28-30 (ST-DOC-01, also bean and dependency version) | different words |
| 95 | 230-231 | Git and pull requests | "Commit messages are in the imperative and say what changed and why" | none found | — |
| 96 | 232 | Git and pull requests | "The judge's PASS at the pull request's current head commit is required before merge." | judge.md:11, 216-217 | different words |
| 97 | 232-233 | Git and pull requests | "The coordinator starts each judge ... or the owner when there is no coordinator" | judge.md:36-37, 49-50 | different words |
| 98 | 233-234 | Git and pull requests | "the implementer never judges its own work" | judge.md:51-52 | same |
| 99 | 235 | Git and pull requests | "Nothing is pushed to a pull request while a judge reviews it." | judge.md:34-35 (implementer "pushes nothing" until a PASS arrives) | different words |
| 100 | 236-237 | Git and pull requests | "The coordinator says \"Ready to merge at <sha>\" once, when ... all hold" | judge.md:217-218; judge.md:128-129 (gate: CI, Codex, Sonar) | different words |
| 101 | 238 | Git and pull requests | "Agents never merge a pull request; only the owner merges." | judge.md:216; adr/0004:31, 41 (Dependabot PRs) | different words |
| 102 | 242 | Reporting to the owner | "Start with the result, or the decision you need, in one or two sentences." | none found | — |
| 103 | 242-243 | Reporting to the owner | "Then give the evidence: what you ran and what it showed." | none found | — |
| 104 | 243 | Reporting to the owner | "End with what is not done, the risks and any open question." | judge.md:83 (Claims item 6 "Not done or not verified") | different words |
| 105 | 245 | Reporting to the owner | "Every question for the owner goes to the one place the owner reads." | none found (judge.md:33 uses that place for the design note only) | — |
| 106 | 245-247 | Reporting to the owner | "On each pull request state change ... posts one status line there" | none found | — |
| 107 | 247 | Reporting to the owner | "written only after reading the pull request's live state" | none found | — |

## Totals

| Section | Items | Also stated elsewhere | Conflicts |
|---|---|---|---|
| Intro (lines 1-4) | 1 | 0 | 0 |
| Who must follow this file | 8 | 2 | 0 |
| The project | 2 | 1 | 1 |
| Roles | 23 | 19 | 0 |
| Working rules (outside ID'd bullets) | 0 | 0 | 0 |
| How work flows (outside ID'd bullets) | 4 | 2 | 0 |
| File size limits (ST-AG-10) | 0 (owned by ST-AG-10) | — | — |
| Engineering rules | 42 | 40 | 4 |
| Build | 9 | 4 | 0 |
| Git and pull requests | 12 | 9 | 0 |
| Reporting to the owner | 6 | 1 | 0 |
| **Total** | **107** | **78** | **5** |

Conflicts: #10, #48, #58, #71, #72.

"Same" words elsewhere: #25, #38, #40, #59, #61, #65, #75, #80, #98 = 9. "Different words": 64. Conflicts: 5.
None found: 29.

## Unsure

- Line 57, "Each rule has an ID so that reviews and judge verdicts can cite it." Read as a statement of purpose, not
  counted. If read as "every rule must have an ID", it is stated in the same words at engineering.md:5-6,
  documentation.md:3-4 and testing.md:3-4, and AGENTS.md itself breaks it (this list).
- Lines 21-22, "built on the latest GA release of Spring Boot ... and Java 25". Counted only the "set once, in
  `stratum-parent`" part (#10). "Latest GA" could be a rule (it constrains version choice); compare adr/0007:24.
- Lines 24-25, "A team adds a starter, sets the `stratum.*` properties ... writes only business code." Read as product
  description, not counted.
- Lines 29-30, "Many services will run on Stratum at high volume ..." A fact that motivates rules, not counted.
- Line 38, "The six topic files add about 800 lines to this one." Fact, not counted.
- Lines 207-208, "the commands below work the same with `./mvnw` in place of `mvn`." Description, not counted.
- Lines 209-213, the list of checks `mvn verify` runs. Read as a description of the build; only line 214 (each check
  fails the build; a module with code but no tests fails) counted as #84. Another lister may count each check.
- #4 (line 12) "This is the only place agent rules are written": literally contradicts documentation.md:24 and
  AGENTS.md's own lines 8 and 35 (rules also in docs/agents/). Marked "different words"; could be judged a conflict.
- #23 (line 47) security role "every security rule" vs security.md:59 (ST-SEC-10..18 only when touched). Marked
  different words; could be judged a conflict.
- #50 (lines 182-183) omits ST-ENG-16's limit that a one- or two-method job is not split into its own class.
  Marked different words; could be judged a conflict.
- #69 (lines 194-195): the module switch `stratum.<module>.enabled` cannot be set by a customizer (adr/0003:42-43),
  but can be set in Java through `SpringApplication` default properties (engineering.md:206-207). Not a conflict.
- Roles table (lines 44-50): split per duty (19 items). Counting one item per row would give 7 instead.
- Lines 99 (#35, #36) and 175 (#39): framing sentences that set scope; counted because they state a requirement.
