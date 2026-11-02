# 0.3a decisions — AGENTS.md at origin/main 54f4ce1

Read in full before deciding: `AGENTS.md` (247 lines) and `docs/agents/judge.md` (218 lines), both via
`git show origin/main:<path>`. Every quoted line below was read the same way; POM facts come from
`git show origin/main:pom.xml`, `git show origin/main:platform/stratum-parent/pom.xml` and
`git grep -n '4\.1\.' origin/main -- '*pom.xml'`. Nothing in the repository was changed; HEAD untouched.

Definitions used: a **rule** is a sentence that requires, forbids or permits something, or sets a limit, default,
order or condition agents follow. A **conflict** is another file stating the same requirement so that an agent
following one text would act differently from an agent following the other; a summary that drops a detail but leads
to the same action is **drift**, not a conflict.

## Inclusion decisions

| ID | Decision | Lines quoted | Reason |
|---|---|---|---|
| D1 | **Include** (1 item) | AGENTS.md:21-22 "built on the latest GA release of Spring Boot (4.1.x today; the exact version is set once, in `stratum-parent`)" | "set once, in X" fixes a count and a place that agents follow when they edit POMs; a parenthetical position does not stop it being a rule. (It is also wrong, see C1.) "Latest GA" is a description of the product, not counted. |
| D2 | **Include** (1 item) | AGENTS.md:27 "Two facts shape every rule below:"; AGENTS.md:31 "- Stratum contains no business logic. Domain code belongs in the services." | "belongs in the services" tells an agent where domain code may and may not go, so it forbids something, even though line 27 calls it a fact. The first sentence is the description, the second the rule; counted as one item. |
| D3 | **Exclude** as a separate rule | AGENTS.md:207-208 "Build with the Maven Wrapper (`./mvnw`), which pins the Maven version; the commands below work the same with `./mvnw` in place of `mvn`." | "work the same" states a fact about the wrapper, not a requirement. The rule in that bullet is "Build with the Maven Wrapper" (A#60, B#82) and both lists already have it. |
| D4 | **Include** (1 item) | AGENTS.md:214 "Each one fails the build, and so does a library or starter that has code but runs no tests." | "a library or starter that has code but runs no tests" fails the build is a condition every module must meet; it is not restated under any ID in the six topic files (B searched; I confirmed nothing in testing.md:60-92 or engineering.md states it). The first clause ("Each one fails the build") is a description of the tools listed in 209-213 and adds no item. |
| D5 | **Exclude** | AGENTS.md:57 "Each rule has an ID so that reviews and judge verdicts can cite it."; identical in engineering.md:5-6, testing.md:3-4, documentation.md:3-4 "Each rule has an ID so that reviews and judge verdicts can cite it." | A statement of purpose for the ID scheme, not an instruction to an agent; both lists excluded it and I agree. Note for the cleanup: it is the premise this whole step tests, and AGENTS.md itself does not meet it. |

## Conflict decisions

| ID | Decision | Lines quoted | Reason |
|---|---|---|---|
| C1 | **Conflict** | AGENTS.md:21-22 "the exact version is set once, in `stratum-parent`". ADR 0005:26-27 "The root `pom.xml` inherits `spring-boot-starter-parent`, so the Spring Boot version is written there, once." engineering.md:296-299 (ST-ENG-60) "Every version Stratum chooses is a property in the root `pom.xml`, and no other POM writes a version number (ADR 0005). ... the root `pom.xml`, and so `stratum-parent`, inherits `spring-boot-starter-parent`". POMs: `pom.xml:18-23` `<parent> ... <artifactId>spring-boot-starter-parent</artifactId> <version>4.1.2</version>`; `platform/stratum-parent/pom.xml:15-19` `<parent> <groupId>com.exepex.stratum</groupId> <artifactId>stratum-build-parent</artifactId> <version>${revision}</version>`; `git grep -n '4\.1\.' origin/main -- '*pom.xml'` → 1 hit, `pom.xml:21`. | AGENTS.md names a different file from the ADR, ST-ENG-60 and the actual build. An agent following AGENTS.md would look for or edit the version in `stratum-parent`, which has none. |
| C2 | **Conflict** (scope of the push ban) | AGENTS.md:235 "Nothing is pushed to a pull request while a judge reviews it." judge.md:34-35 "The implementer asks the coordinator to start the judge. Until a PASS arrives, the implementer reports the work as "waiting for judge", never as done, and pushes nothing." judge.md:204 "After a FAIL, the implementer fixes each finding and shows the proof (commit, test, command output)"; judge.md:206 "A fresh judge ... reviews once more at the new head commit". | AGENTS.md closes the window at the verdict (PASS or FAIL); judge.md:34-35 closes it only at a PASS, which, read alone, forbids the pushes that judge.md:204-206 require after a FAIL. Different windows, and judge.md:34-35 also disagrees with its own Rounds section. |
| C3 | **Conflict** | AGENTS.md:181 "no abstraction without a second use". engineering.md:19-20 (ST-ENG-05) "Add no type, interface, helper, option or branch without a use that exists today." engineering.md:24-25 "Moving a second job or copied logic into its own class is not new structure: do it without asking (ST-ENG-16)." engineering.md:94-95 (ST-ENG-16) "When a class also does a second job ... move that job into its own class." judge.md:145-146 "For each new type, method, option and branch, name its use today." | AGENTS.md requires two uses before an abstraction may exist; ST-ENG-05 and the judge checklist require one use today, and ST-ENG-16 orders a class with a single caller to be extracted. An agent following AGENTS.md would refuse an extraction that ST-ENG-16 requires. AGENTS.md:175-176 ("the topic file wins") settles precedence but does not remove the contradiction. |
| C4 | **Not a conflict** (drift) | AGENTS.md:187-188 "a type without it is API and exposes only API types". ADR 0009:39-40 "3. **An API type exposes only API types.** Its public and protected constructors, methods and fields, its superclass and its interfaces use only API types, JDK types and library types." engineering.md:282-283 (ST-ENG-56) "An API type's public and protected members, superclass and interfaces use only API, JDK and library types". | AGENTS.md repeats the ADR's own headline for decision 3 word for word; the ADR's next sentence defines that headline to include JDK and library types. Same requirement under the same label; the summary drops the definition, which is drift to note, not a different meaning. |
| C5 | **Conflict** | AGENTS.md:196-197 "Every class, method and configuration property has Javadoc." engineering.md:84-85 (ST-ENG-14) "In test code, the `@DisplayName` on a test class, `@Nested` class or test method replaces Javadoc (ST-TEST-12)". testing.md:86-87 (ST-TEST-41) "Test code follows the same rules as production code ... with two exceptions: `@DisplayName` replaces Javadoc on tests (ST-ENG-14)". | AGENTS.md's "Every class, method" includes test classes and methods; the topic files exempt them and call this an exception. An agent following AGENTS.md writes Javadoc on tests that ST-ENG-14 says `@DisplayName` replaces. |
| C6 | **Conflict** | AGENTS.md:197 "Dependencies are injected through constructors only." engineering.md:156-160 (ST-ENG-26) "Dependencies are injected through constructors ... Never field injection ... In test classes, field injection and test-method parameters are allowed ... Prefer constructor injection where it is practical". testing.md:87-88 (ST-TEST-41) "field injection is allowed where constructor injection is not practical (ST-ENG-26)". | "only" in AGENTS.md forbids what ST-ENG-26 and ST-TEST-41 permit in test classes. Different action for a test class. |
| C7 | **Conflict** (wording, low harm) | AGENTS.md:12 "This is the only place agent rules are written." AGENTS.md:8 "follows all of this file and the topic files it names". documentation.md:24 "\| `AGENTS.md` and `docs/agents/` \| The rules for agents. `AGENTS.md` links every file in `docs/agents/`. \|" AGENTS.md:90-91 (ST-AG-13) "propose the one-line rule and the file it belongs in". | Read literally, line 12 says rules live in one file; documentation.md:24, AGENTS.md:8 and ST-AG-13 say they live in AGENTS.md plus `docs/agents/`. An agent following line 12 would put a new engineering rule into AGENTS.md instead of engineering.md. The intent of lines 12-14 is clearly "not in CLAUDE.md or tool files", so the fix is one phrase ("This file and `docs/agents/` are the only place"). |
| C8 | **Not a conflict** (drift) | AGENTS.md:47 "\| Security \| Reviews the change against every security rule \|". security.md:59 "Check ST-SEC-01 to ST-SEC-06 on every change, and each other rule above that the change touches." judge.md:163-164 "Check ST-SEC-01 to ST-SEC-06 on every change, and each other rule in [security](security.md) that the change touches." | security.md:59 is the procedure for checking a change against every rule: a rule the change does not touch cannot be broken by it, so "every rule" and "01-06 plus every touched rule" produce the same checks and the same findings. The role line could cite "How to check a change", but no agent acts differently. |
| C9 | **Not a conflict** (drift) | AGENTS.md:182-183 "When a class holds a second job, move that job into a helper, utility or mapper class, even if the file is far below 400 lines." engineering.md:97-99 (ST-ENG-16) "Move it only when it is real work of its own: a new class that would hold just one or two small methods splits the code too finely, so put such methods in an existing class that does the same job, as `MaskedText.keepingLastFourAt` is." | Both texts move the second job out of the class; ST-ENG-16 only decides whether it lands in a new class or an existing helper of the same job, and AGENTS.md's "a helper, utility or mapper class" covers an existing one. Same action (the job leaves the class); the summary omits the new-versus-existing detail. |

### Conflicts that appear in only one list

Scan of both lists' conflict rows: A marks 3 (#31 = C3, #50 = C6, #76 = C2); B marks 5 (#10 = C1, #48 = C3, #58 = C4,
#71 = C5, #72 = C6). Every one of them is decided above; there is no conflict row in either list outside C1-C9.
A's "drift candidates" #38 (ST-TEST-17 adds "one class per behaviour; a one-behaviour class stays flat") and #70
(AGENTS.md:225-227 names six Claims items, judge.md:71-85 lists eight) were checked: both are omissions of detail,
and judge.md is the file AGENTS.md:225 points to ("the Claims defined in judge"), so neither changes an agent's action.
Not conflicts.

## Final 0.3a list

### Count

Counting basis: one item per separate requirement. The Roles table (AGENTS.md:44-50) is counted per duty, as list B
did (19 items for seven rows), because each duty is a separate requirement on that role; counting per row would give
7 and a total of 95. Framing sentences that only introduce a list (AGENTS.md:175 "These points summarise the topic
files and apply to every change", B#39) are not counted; "The coordinator runs them" and "Every item of work follows
these rules" (AGENTS.md:99) are counted, as both lists did, because they assign who runs the flow.

| Section | Items | Basis |
|---|---|---|
| Intro and "Who must follow this file" (lines 3-17) | 9 | B#1-9; A had 8 because it folded "`CLAUDE.md` imports this file and holds nothing else" (line 12) into its neighbours; that clause is a separate requirement on `CLAUDE.md`'s content, so B's split stands |
| The project (lines 19-31) | 2 | D1 and D2 included |
| Roles (lines 35-53) | 23 | B#12-34: read-all-files, state-in-report, 19 duties, several-roles, judge-never-combined |
| How work flows preamble (lines 99-101) | 5 | A#20-24; B merged "the owner starts the judge" into "implementer does the coordinator's duties except one"; the exception names a separate duty of the owner, so A's split stands |
| Working rules, ST-AG-15 to 19 bullets, File size limits (lines 57-171) | 0 | owned by an ID; D5 excluded |
| Engineering rules (lines 175-203) | 41 | B#40-80 (B#39 excluded as framing). B's finer splits over A (names / small classes, "Tests get the same care", `@StratumInternal` / exposes only API types, one concern / optional, depends on interfaces / external system behind one, typed property / where it is set) each separate a distinct requirement, so they stand |
| Build (lines 207-218) | 9 | B#81-89; D4 included, D3 excluded |
| Git and pull requests (lines 222-238) | 12 | A#67-78 = B#90-101 |
| Reporting to the owner (lines 242-247) | 6 | A#79-84 = B#102-107 |
| **Total** | **107** | |

The agreed count of unnumbered rules in AGENTS.md is **107** (per-duty Roles basis; 95 on a per-row basis). It
equals B's total by coincidence: B#39 leaves, "the owner starts the judge" (AGENTS.md:100) enters.

### Final conflicts (5)

1. **C1** AGENTS.md:21-22 "set once, in `stratum-parent`" vs ADR 0005:26-27, ST-ENG-60 (engineering.md:296-299) and
   `pom.xml:18-23`: the Spring Boot version is in the root `pom.xml`, and `platform/stratum-parent/pom.xml` has none.
2. **C2** AGENTS.md:235 "while a judge reviews it" vs judge.md:34-35 "Until a PASS arrives ... pushes nothing"
   (which judge.md:204-206 itself contradicts after a FAIL).
3. **C3** AGENTS.md:181 "no abstraction without a second use" vs ST-ENG-05 (engineering.md:19-20) "a use that exists
   today", ST-ENG-16 (engineering.md:94-95) and judge.md:145-146 "name its use today".
4. **C5** AGENTS.md:196-197 "Every class, method ... has Javadoc" vs ST-ENG-14 (engineering.md:84-85) and ST-TEST-41
   (testing.md:86-87): `@DisplayName` replaces Javadoc in test code.
5. **C6** AGENTS.md:197 "constructors only" vs ST-ENG-26 (engineering.md:158-160) and ST-TEST-41 (testing.md:87-88):
   field injection allowed in test classes.

Plus one wording defect inside the rule book that is a conflict in letter only: **C7** AGENTS.md:12 "This is the
only place agent rules are written" vs AGENTS.md:8, ST-AG-13 and documentation.md:24. Counted separately because the
intent is unambiguous and the fix is a phrase; the owner may fold it into the five or treat it as drift.

Drift, not conflicts, to carry as notes: C4 (JDK and library types omitted; same headline as ADR 0009 decision 3),
C8 (security role line does not cite security.md's "How to check a change"), C9 (ST-ENG-16's new-versus-existing
class detail omitted), A#38 (ST-TEST-17's flat-class detail), A#70 (Claims items listed as six, judge.md has eight).

### Not verified

I did not re-derive the 82 lines on which A and B agree; I took the two lists as given for those and decided only the
disputed points above. Line numbers for engineering.md, testing.md, security.md, documentation.md, review.md and the
ADRs are from `git show origin/main:<path> | cat -n` on the ranges I printed, not a read of every line of those files.
