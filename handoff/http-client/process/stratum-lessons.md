# Stratum: repeated mistakes and the rules to stop them

Written 2026-11-02 for a new session that has none of this project's history. The owner, Aashish, will go
through it with you and turn each point into a permanent rule. Do not write any rule into the repo until he agrees
to that exact rule.

## 1. Context in five lines

- **Stratum** (`exepex/stratum`): an SDK on Spring Boot 4.1 and Java 25. Starters give services ready-made logging,
  observability, web, security and more. Agent rules live in `AGENTS.md` and six files in `docs/agents/`.
- **Who works on it:** Aashish (owner; he alone merges), a coordinator agent, implementer agents, one independent
  judge agent per PR, the Codex review bot and SonarCloud. Aashish also reviews on GitHub as `aashish-kumar-intelera`.
- **State:** PRs #1 to #106 are merged; main is at `7b65c52`. Issue #107 is deferred. The full project handoff is
  `handoff/stratum-handoff.md` in the same shared folder.
- **The root problem:** decisions were made in chat and never became written rules (point 0).

## 2. How to read this file

- **Sources.** Three independent reviews of the whole project history: Sonnet (S), Opus (O) and Fable (F). Full
  files: `reviews/lessons/sonnet.md`, `opus.md`, `fable.md`. They read 104 judge reports, 456 PR review comments,
  the project chat (10-27 to 11-02) and the threads.
- **Quotes** are copied from those files. I did not check them again against the chat or GitHub.
  **†** marks a quote from Sonnet's thread notes: helpers wrote them and nobody spot-checked them.
- **"PR #n"** means a comment or judge verdict on that PR. **"chat 10-31 11:31"** means the project chat at that
  UTC time.
- **Rule status** was checked against main at `7b65c52` on 2026-11-02:
  - **In repo:** the rule is already written; the problem is that agents did not follow it.
  - **Partly:** part of the rule is written; the named part is missing.
  - **Chat only:** said in chat or kept in agent memory, not written in the repo.
- **Found by** gives how many of the three reviewers listed it, and which.

---

## 0. Root cause: decisions stayed in chat

- **What kept happening:** Aashish stated a rule in chat; the next agent never saw it and broke it again.
- **Examples:**
  - Chat 11-02 10:04: "we discussed a lot during the chats and they stayed inside the chats. They never became rule."
  - Structure review 10-30: "'Builder for 3+ fields' and 'merge small related classes' are owner rules not written
    in the repo."
  - The "ask in project chat" instruction was given about nine times; "too verbose" about nine times (F).
- **Fixed at the time:** some rules were later added to the repo (ST-ENG-05 simplicity in PR #25, versions in one
  place in PR #24, structure rules ST-ENG-16 to 18 in PR #55). Most stayed in agent memory only.
- **Proposed rule:** when Aashish states a rule, or repeats an instruction a second time, the agent proposes the
  one-line rule and the file it goes in, in that same reply. After his go it is added the same day, with a matching
  line in the judge checklist.
- **Status:** chat only.
- **Found by:** 3 (S 24, O 19, F 25).

---

## Planning

### 1. Work started before "go", or extras added

- **What kept happening:** a question or discussion was read as an order to build, and helpful extras were added.
- **Examples:**
  - Chat 10-28 11:56: "every discussion, when I ask for suggestion, feedback, queries and questions, that means it
    is for planning and decision making rather than implementation."
  - Chat 11-01 01:18: "I never discussed or talked about starting HTTP client starter." (PR #79 is still open.)
  - PR #2: a line-count script added unasked: "I did not like this script." Two judges per PR became the default
    without a request (chat 10-30 18:37: "that was my mistake").
- **Fixed at the time:** not fixed, only corrected each time. The script was removed; the HTTP client work was put
  on hold.
- **Proposed rule:** no branch, commit or PR until Aashish says "go" on that exact item. A message that asks,
  discusses, suggests or reviews is planning only. Extras are proposed in one line, never built.
- **Status:** partly. ST-AG-06 (`AGENTS.md`) says "propose it to the owner instead of building it". The "go on the
  exact item" rule is chat only.
- **Found by:** 3 (S 9, 17; O 14; F 10, 19).

### 2. Firm rules and decisions got lost

- **What kept happening:** a rule Aashish called firm was missed in the next PR, sometimes the same day.
- **Examples:**
  - Existing-contract rule set chat 11-01 about 15:14. PR #95 judge: "that exact break already happened twice in
    this PR". PR #96: WebFlux multipart limits still applied in compatible mode.
  - Chat 10-28 07:57: "Lombok ... was explicitly mentioned"; the judge passed PR #11 twice without it.
  - "all dependency versions in one single place" said 10-27 22:46 and again 10-28 18:46.
- **Fixed at the time:** fixed one by one in later PRs (#24 versions, #35 package name, #48 and #49 Java config and
  IDE metadata, #96 compatible-mode limits).
- **Proposed rule:** same as point 0. In addition, each PR's Claims list the owner decisions it covers, each with
  its test.
- **Status:** chat only. `judge.md` asks for "every requirement in the request", not for standing decisions.
- **Found by:** 3 (S 24; O 19; F 14, 21).

### 3. Gaps kept appearing after a module was "done"

- **What kept happening:** a module was called done, then more gaps were found round after round.
- **Examples:**
  - Chat 10-31 14:09: "we have been fixing it for a very, very long time".
  - Chat 11-02 02:09: "almost last 24 hours, and still more and more gaps".
  - Late gaps in core: protected nested types (#106), the forgotten `@StratumInternal` label (issue #107).
- **Fixed at the time:** a full module review pass started on 11-01 (core first). Late non-blockers began to go to
  GitHub issues (#107).
- **Proposed rule:** a module is done only against a written checklist (switch off, back-off, both stacks,
  security, docs, owner decisions). Gaps found later that do not block go to a GitHub issue, not a new PR round.
- **Status:** chat only (agent memory says "prefer GitHub issues for non-blockers").
- **Found by:** 2 (S 11, O 17).

---

## Design

### 4. MVC and WebFlux behaved differently

- **What kept happening:** each stack was built and tested on its own; the judge found the gaps.
- **Examples:**
  - PR #72: "WebFlux wrote no line when the caller disconnected; MVC wrote one."
  - PR #67: a WebFlux client could forge `clientIp`. PR #86: `@ResponseStatus` "splits the stacks".
  - PR #98: the 499 status; "32 cases the judge tested".
- **Fixed at the time:** each gap fixed in its own PR (#86, #89, #98, #101, #102). Aashish made it firm on 11-01:
  MVC and WebFlux always behave the same, every status and error, in every mode.
- **Proposed rule:** before any web code, write one table of cases (input, status, body, log line, headers). One
  shared parameterised test runs that table on both stacks.
- **Status:** partly. ADR 0006 line 43: "The two starters must behave the same: every feature is tested on both
  stacks." ADR 0008 decision 7 and `docs/modules/web.md` say the same. The case table and the shared test are not
  in the agent rules.
- **Found by:** 3 (S 1, O 8, F 8).

### 5. Log fields and context lost or leaked across async hops

- **What kept happening:** each PR fixed one thread hop and tested only that hop.
- **Examples:**
  - PR #54: "Restore failed dispatch MDC before its thread is released."
  - PR #67: "Restore request fields during MVC asynchronous redispatch."
  - PR #92 merged, then Codex found a repeating task left a log field behind, so PR #94 followed. PR #50 had 4
    judge rounds, #51 had 4.
- **Fixed at the time:** fixed hop by hop in PRs #44 to #54, #92, #94 and #101.
- **Proposed rule:** one fixed list of contexts in `stratum-test` (MVC, WebFlux, `@Async`, task executor,
  `@Scheduled`, Reactor operators, Kafka and JMS listeners, virtual threads, own executors), plus the exits (normal,
  exception, async redispatch, error dispatch, cancel, a second Spring context). Any change to log fields, tracing
  or correlation IDs runs the whole list.
- **Status:** partly. ST-ENG-53 says "When work moves to another thread, pass the logging and tracing context
  explicitly." There is no fixed list.
- **Found by:** 2 (S 2, F 6). The merged list said 3; Opus has no separate point for it.

### 6. Secrets leaked through odd inputs

- **What kept happening:** each leak was patched alone; nobody listed hostile inputs first or searched other
  modules for the same bug.
- **Examples:**
  - PR #26: 21 redaction findings over about 6 rounds: cookie, `redis://:pw@host`, a second IBAN,
    `{"token":["x"]}`; 9 judge rounds.
  - PR #11 and PR #60: `${validatedValue}` and map keys leaked rejected values into errors; the same bug returned
    in #60 three times.
  - PR #72: the PR said it "already masks every log field"; names and phone numbers from the JWT were logged.
- **Fixed at the time:** redaction rewrite (#31) and masking-gap PRs (#33, #36).
- **Proposed rule:** before coding anything that writes untrusted text to a log, response or error, list the
  hostile inputs (encodings, separators, nesting, empty parts, size, forged headers) as tests. When one leak is
  fixed, search every module for the same pattern.
- **Status:** partly. `security.md` ("How to check a change") and `review.md` step 3 ask reviewers to follow
  untrusted values. A hostile-input list before coding and a cross-module search are not written.
- **Found by:** 3 (S 3, O 3, F 13).

### 7. Over-engineering

- **What kept happening:** too many small types for imagined cases.
- **Examples:**
  - PR #16 chat 10-28: "What the hell is this? You create overcomplicated in the design ... too many fragmented
    error code objects." 13 types cut to 7; the judge had passed it.
  - PR #19 `stratum-test`: "over-engineered", reverted in PR #22.
  - Chat 10-30 18:14: "no point of having a single class with only one method".
- **Fixed at the time:** PR #23 (13 simplifications, about 225 lines removed), ST-ENG-05 added in PR #25.
- **Proposed rule:** every new type names its use today in the Claims. A tiny type with one caller is merged into
  that caller.
- **Status:** partly. ST-ENG-05 and `judge.md` step 7 ("name its use today") are in the repo. "Merge tiny
  single-use types" is chat only, and it pulls against ST-ENG-16 and ST-ENG-17 (see open questions).
- **Found by:** 3 (S 6, O 12, F 12).

### 8. Code copied between MVC and WebFlux

- **What kept happening:** the second stack was built by copying the first, in code and in tests.
- **Examples:**
  - Codex PR #64: "Share the order error-code fixture between starters".
  - Codex PR #95: "identical files apart from...". Codex PR #97: "Share the counter lookup between both web-stack
    tests".
  - Judge PR #61: "PR #60 still has its own copies of these checks".
- **Fixed at the time:** shared code moved into `libs/stratum-web` and `StarterTestSupport` PR by PR.
- **Proposed rule:** build each web feature once in the shared library; each stack only adapts to it. Move a test
  helper to `testsupport` before the second stack uses it.
- **Status:** in repo. ST-ENG-06 and ADR 0006 line 37. Not followed.
- **Found by:** 2 (S 4, O 6). Fable lists the copied test helpers under its point 3.

### 9. A chain of follow-up PRs on one feature

- **What kept happening:** the first design shipped, then each review patched it instead of redesigning.
- **Examples:**
  - Correlation ID #67, #69, #70, #71: "I hope this is not a patched PR"; judge: "reads patched".
  - Version check #81, #83, #84, #85. Compatible mode #95, #96, #97, #98, #102.
  - Masking #26, #29, #31, #33, #36.
- **Fixed at the time:** not fixed, only corrected each time.
- **Proposed rule:** when a feature needs a second follow-up PR, stop. Write a one-page design (both stacks, all
  contexts) and get Aashish's go before the third PR. The judge checks the third PR against that page.
- **Status:** chat only.
- **Found by:** 2 (F 17, O 17).

### 10. Slow code on the request path

- **What kept happening:** per-request cost was never checked.
- **Examples:**
  - Codex PR #26: quadratic regex, "~1.7 s on 16KB".
  - Codex PR #38: "compiling a regex for every observed HTTP request".
  - Judge PR #31: card digits "2x slower"; PR #36 a `HashSet` built per call.
- **Fixed at the time:** fixed in PR #31 and the masking PRs.
- **Proposed rule:** compile once, keep the work linear, and add one large-input test (for example 1 MB of a
  repeated character) for any code on a request or log path.
- **Status:** partly. ST-ENG-54 ("No reflection, regex compilation, large allocations or quadratic loops per
  request") is in the repo. The large-input test is not.
- **Found by:** 2 (S 15, O 11).

---

## Implementing

### 11. Tests stayed green with the code broken (biggest cause of extra judge rounds)

- **What kept happening:** tests were written to pass; nobody broke the code to see a test fail.
- **Examples:**
  - Judge planted-fault survivals: #26 "four planted faults survived", #53 "the tests miss 6 of 9", #31 5 of 22.
  - PR #23: the U+2028/2029 test cases "didn't test anything". PR #47: "Renaming the span logger left all 92 tests
    passing."
  - Codex PR #43: the test reads the key from the same constant as production, "so a misspelt key passes".
- **Fixed at the time:** not fixed, only caught by the judge each round. 54 of 104 judge reports needed 2 or more
  rounds.
- **Proposed rule:** before asking for the judge, plant a fault in each new branch, guard and bean registration,
  and list "fault -> failing test" in the Claims. A fault no test catches blocks the PR.
- **Status:** in repo. ST-TEST-01 ("break the code on purpose ... confirm the test fails") and `judge.md` Claims
  item 3 ("Broken-code proof"). Not followed.
- **Found by:** 3 (S 20, 8; O 1; F 2).

### 12. Docs and PR descriptions left out of date

- **What kept happening:** docs were updated where the author remembered; Claims were not refreshed after later
  pushes.
- **Examples:**
  - Judge PR #59: "Two READMEs still list the bundles from before this PR". PR #101: "two doc lines still say only
    logging and observability turn on this setting."
  - PR #78 "aligned the web docs only" after ST-ENG-34 changed, so PR #88 was needed.
  - PR #63: "the PR description still describes 2100bb7"; PR #45: "line counts are those of 9698558".
- **Fixed at the time:** fixed per judge finding.
- **Proposed rule:** for every changed key, class, default or rule, grep the repo for its name and old wording and
  update every hit; paste the grep in the Claims. Regenerate the Claims on every push.
- **Status:** partly. ST-DOC-01 (same PR) and `judge.md` ("updated each round") are in the repo. The grep step is
  not.
- **Found by:** 3 (S 5, 18; O 2; F 5).

### 13. Existing code rules broken again

- **What kept happening:** the same written rule was flagged by Codex PR after PR.
- **Examples:**
  - ST-ENG-47 statics in non-utility classes: Codex on #51, #65, #70, #75, #81, #86, #98.
  - ST-TEST-15 and ST-TEST-17 (test data constants, `@Nested`): #64, #75, #81, #89, #92, #101; post-merge fixes #85
    and #93.
  - PR #28 and PR #35: the judge found the implementer "skipped 5 of 6 rule files". †
- **Fixed at the time:** not fixed, only corrected each time.
- **Proposed rule:** before each push, check the diff against a short checklist of the most-broken rules (statics,
  test data, `@Nested`, shared helpers, unit vs integration placement, lambdas, docs, line counts, Javadoc, key
  names). Paste the result in the Claims; the judge fails a PR without it.
- **Status:** partly. Each rule is in the repo; the checklist is not.
- **Found by:** 3 (S 7, 28; O 4; F 3).

### 14. Code that looked AI-generated or used tricks

- **What kept happening:** rules were met with tricks instead of plain code.
- **Examples:**
  - PR #18, `@ParameterizedTest(name = "\"{0}\"")`: "looks like the code is generated by AI ... Don't use such
    tricks."
  - PR #33 and #38: "feels like a junior developer wrote the code". PR #42: "what USABLE_ID means? ... nobody will
    be able to understand".
  - Chat 10-29 00:16: "code feels like AI generated ... different design patterns in different places".
- **Fixed at the time:** a 26-finding consistency review and PRs #29 to #37; style retrofits in #23, #27, #30.
- **Proposed rule:** write plain code that matches the nearest existing class. Never satisfy a rule with a trick;
  if a rule and plain code collide, write the plain code and ask about the rule.
- **Status:** partly. ST-ENG-05 says "No clever tricks". "Match the nearest class" is not written.
- **Found by:** 3 (S 6, O 10, F 18).

### 15. A fix added a new bug or missed similar cases

- **What kept happening:** quick patches without rerunning the module or testing sibling cases.
- **Examples:**
  - Codex PR #13: "My previous commit caused this regression".
  - Codex PR #86: the cause-chain fix created a cycle hang; PR #86 then had 6 judge rounds.
  - PR #105: Codex found generics bounds, `throws`, inherited members and protected nested types one after another.
- **Fixed at the time:** not fixed, only corrected each time.
- **Proposed rule:** after each review fix, rerun the whole module verify, and list and test the sibling cases of
  the finding before asking again.
- **Status:** chat only. ST-AG-03 (find the root cause) is related but does not say this.
- **Found by:** 2 (O 5, S 13).

### 16. Spring Boot behaviour assumed from memory

- **What kept happening:** Boot was described from memory or from Boot 2 or 3 examples.
- **Examples:**
  - Codex PR #38: validation ran before Boot maps the OTEL env vars. Codex PR #75: Boot 4 renamed the codec key.
  - Observability plan v1 failed with 30 findings (Boot already observes `@Scheduled`, Batch, JMS); web plan v1
    failed with 31.
  - The API-split plan, agreed by three reviewers, hit the no-cycle build rule at build time (11-02 00:31).
- **Fixed at the time:** fixed per finding; plans were rewritten.
- **Proposed rule:** check each claim about Boot in the Boot 4.1 source or docs and cite it. Compile any package
  move in a throwaway branch before the plan goes to review.
- **Status:** partly. ST-ENG-49 and `AGENTS.md` say to check the Boot 4 migration notes. "Cite the source" is not
  written.
- **Found by:** 2 (O 13, F 24).

### 17. Owner had to report merge conflicts, CI failures and Sonar issues

- **What kept happening:** agents asked for review before rebasing or reading CI and Sonar.
- **Examples:**
  - Owner reported conflicts on #9, #11, #27, #63, #82, #88.
  - Chat 10-28 22:12: "I don't know why you're not picking up Sonar Cube issues". CI was red for about 14 hours on
    10-29 before anyone noticed.
  - Sessions cannot reach sonarcloud.io; the owner pasted issues for #26, #36, #38, #43, #50, #53, #58, #61. †
- **Fixed at the time:** agent memory now says "ask for Sonar screenshots".
- **Proposed rule:** before asking for review, rebase on main and read CI and Sonar results (through GitHub check
  runs first, the owner's screenshot only if that fails). Say so in the PR.
- **Status:** chat only. `AGENTS.md` only requires `mvn verify` before each push.
- **Found by:** 2 (O 16, 18; S 19, 27).

---

## Review

### 18. "Ready to merge", then "hold"; head moved while the judge ran

- **What kept happening:** "ready" was said on the judge's PASS while Codex, CI or Sonar were still running.
- **Examples:**
  - PR #38: five holds in one PR. PR #105: ready 01:50, hold 01:53, ready 01:57.
  - PR #45 merged at 18:01 during judge round 2; round 2 found a real bug, so PR #46.
  - Head moved during the judge: PR #72 four times, PR #75 five times. †
- **Fixed at the time:** not fixed, only corrected each time.
- **Proposed rule:** say "ready to merge at <commit>" once, only when the judge, CI, Codex and Sonar have all
  finished on that same commit and every review thread is answered. Push nothing while the judge runs. Otherwise
  say "waiting on X".
- **Status:** partly. `judge.md` says a PR merges only with a PASS for its current head. The rest is chat only.
- **Found by:** 3 (S 21, 22; O 16; F 4, 11).

### 19. The judge passed things the owner later rejected

- **What kept happening:** the judge checked the author's Claims, not the owner's standing rules.
- **Examples:**
  - PR #16: the judge agreed with all four decisions; the owner called it overcomplicated.
  - PR #11: Lombok missing, PASS twice. PR #19: PASS "nothing extra", then rejected as over-engineered.
  - PR #32: the judge wrote "my earlier PASS was wrong".
- **Fixed at the time:** not fixed.
- **Proposed rule:** the judge starts from the owner's standing-rule list, then the request, then the Claims.
- **Status:** partly. `judge.md` step 1 builds the gap list from the request, and step 6 checks the repo rules. It
  cannot check rules that are not written (point 0).
- **Found by:** 1 (S 23).

### 20. Findings accepted or rejected without proof

- **What kept happening:** findings were judged by reading, not by running; both blind yes and blind no happened.
- **Examples:**
  - Chat 11-01 23:39, 23:55, 23:56 (three times in 20 minutes): "don't accept anything blindly".
  - PR #67: a percent-encoded secret marked "no change", overruled by the owner: "Option B. I want to get it fixed."
  - PR #54: a same-thread guard was built for a case never seen on a real server. Owner, 10-30: "If judge is giving
    a feedback which is unrealistic, then let's not implement it."
- **Fixed at the time:** core round 2 was settled with "three throwaway probe tests", which worked well.
- **Proposed rule:** every accept or reject comes with a test or command output. Only findings that can happen on
  a real setup count.
- **Status:** partly. ST-AG-04, `review.md` step 6 ("Drop anything you cannot show") and `judge.md` rounds ("not an
  answer") are in the repo. "Realistic only" is chat only.
- **Found by:** 2 (F 16, S 13).

### 21. Rule files contradicted each other

- **What kept happening:** a rule was changed in one place and not in the others.
- **Examples:**
  - PR #2 failed rounds 1 to 6 on contradictions such as ST-ENG-05 against other rules.
  - PR #103: "the new ST-DOC-04 contradicts the PR's own tables".
  - PR #25: `AGENTS.md` left out of step with the new ST-ENG-05; ST-SEC-03 against IP logging.
- **Fixed at the time:** fixed per judge finding.
- **Proposed rule:** a rule change lists every file that states or depends on that rule; the judge checks each.
- **Status:** chat only.
- **Found by:** 2 (O 20, S 26). The merged list said 3; Fable has no separate point for it.

---

## Discussion

### 22. Owner kept asking "what's happening?"

- **What kept happening:** status lived in threads and long replies; status lines were sometimes stale.
- **Examples:**
  - Chat 10-31 11:31 and 11:49: "What is happening? Why is it taking so much time? I am unable to understand."
  - Chat 11-01 18:25: "Which PRs are ready... what is still left"; repeated 20:18, 20:49, 11-02 01:32, 01:57,
    02:14, 02:29.
  - Stale status edited later: PR #66 "already merged"; PR #104 "hold" edited to "was already merged".
- **Fixed at the time:** not fixed, only answered each time.
- **Proposed rule:** on every PR state change (opened, judge PASS or FAIL, ready, merged, blocked), post one line
  in the project chat: PR number, link, state, and what is waiting on the owner. Write it only after a live read of
  the PR.
- **Status:** chat only.
- **Found by:** 3 (S 16, 18; O 7; F 1, 15).

### 23. Questions left inside threads

- **What kept happening:** questions and decision cards sat in the thread where the work was; the owner only reads
  the project chat.
- **Examples:**
  - Chat 11-01 23:59: "I see there is a question in the agent screen... ask the agent to send it here... Otherwise,
    I will not know."
  - Chat 11-01 11:30: "send here… in one single place." Repeated 10-29, 10-30, 10-31, 11-01.
  - PR #95: "waiting for your answer in Judge thread" after six such instructions.
- **Fixed at the time:** agent memory says "ALL questions go to project chat". Still missed.
- **Proposed rule:** every question for the owner goes to the project chat the moment it comes up. A card in a
  thread is only a copy.
- **Status:** chat only.
- **Found by:** 3 (S 16, O 7, F 9).

### 24. Answers too long

- **What kept happening:** decision questions were written as full analyses.
- **Examples:**
  - Chat 11-02 00:13: "Keep it simple, be precise, small, not too verbose. You always tend to make it too
    verbose." The 3-line rewrite got "definitely option A" in 30 seconds.
  - Chat 11-01 15:36: "Can we discuss one at a time, because you brought up too many open questions."
  - PR #38 (twice): "be precise & concise and use simple words for explanation".
- **Fixed at the time:** not fixed, only rewritten each time.
- **Proposed rule:** one question per message: the question in one line, one concrete example, one line per option
  with its effect, one recommendation with one reason. Under 10 lines.
- **Status:** partly. `AGENTS.md` "Reporting to the owner" says to start with the result. The question shape is
  chat only.
- **Found by:** 3 (S 10, O 9, F 7).

### 25. Short instructions misread

- **What kept happening:** a one-word reply was matched to whatever the agent was working on.
- **Examples:**
  - Chat 11-02 02:11: the agent fixed #107; owner: "No, I'm not talking about 107. I was talking about 106."
  - Chat 10-31 11:02: "Option A" fit two open cards.
  - PR #36: "drop it" misread.
- **Fixed at the time:** not fixed.
- **Proposed rule:** restate a short instruction in one line ("I read this as: fix #106") before acting.
- **Status:** chat only.
- **Found by:** 1 (F 22). Opus quotes the same #106 case.

### 26. Permission asked for trivial fixes

- **What kept happening:** "no build before go" was applied even to fixes inside an approved PR.
- **Examples:**
  - PR #60 and #62: "Shall I go ahead?" three times. Chat 10-30 22:41: "If everything is already clear... continue."
  - PR #102: an A/B card for one test (11-01 22:56). PR #100: a flaky-test fix.
- **Fixed at the time:** not fixed.
- **Proposed rule:** inside an approved PR, fix reviewer and judge findings without asking. Ask only for a change
  to a default, a key, a public API or a contract.
- **Status:** chat only. ST-ENG-05 allows one case (moving a second job out) without asking.
- **Found by:** 1 (F 20).

---

## Extra points not in the 26-point list

Each was found by one reviewer only. Decide with Aashish whether each needs a rule.

- **27. Defaults chosen without a real service use case** (O 15). PR #38 reviewer: "what if there are query
  parameters which are not sensitive?" PR #42: "what if a service wants to monitor ipaddress, sessionid, orderid?"
  Both designs were reversed. Proposed rule: for each default, write one real service case that needs to change it,
  and show how. Status: chat only.
- **28. A one-off instruction became a standing default** (F 23). Two judges per PR (#47 to #57); "no loops"
  became stream tricks; "no unnecessary tests" left a known gap. Proposed rule: an instruction applies to its task
  unless the owner says it is a rule. Status: chat only.
- **29. Cards on questions already decided, or building while a card is open** (S 25). PR #51: "building both as
  recommended unless you say otherwise". † Proposed rule: one card per open question; nothing built on its options
  before the answer. Status: chat only.
- **30. Property keys drift between code, docs and metadata** (S 12). PR #49: "Align the runtime propagation key
  with the published property"; PR #74 `@Name("default")`. Proposed rule: one constant per key, one test that docs,
  metadata and code agree. Status: partly (ST-ENG-45 constants; ST-DOC-04 tables).
- **31. Build gates bypassed** (S 14). PR #41: "Obtain a rule exception before bypassing the vulnerability gate."
  Proposed rule: no gate bypass without the owner's written go. Status: in repo (ST-AG-07).

---

## Standing instructions Aashish repeated most

His words, from the reviews. Counts are the reviewers' tallies.

1. "Keep it simple, be precise, small, not too verbose. You always tend to make it too verbose." (11-02 00:13;
   about 9 times)
2. "I see there is a question in the agent screen... ask the agent to send it here... Otherwise, I will not know."
   (11-01 23:59; about 9 times)
3. "What is happening? Which PRs are ready... what is still left" (11-01 18:25; about 26 times in some form)
4. "every discussion, when I ask for suggestion, feedback, queries and questions, that means it is for planning and
   decision making rather than implementation." (10-28 11:56)
5. "don't accept anything blindly" (11-01 23:39, 23:55, 23:56; 5 times)
6. "I'm really against unwanted over complexity" (10-28 17:45); "no point of having a single class with only one
   method" (10-30 18:14)
7. "Can we discuss one at a time, because you brought up too many open questions." (11-01 15:36)
8. "Don't use such tricks." (PR #18); "be precise & concise and use simple words" (PR #38, twice)
9. "If judge is giving a feedback which is unrealistic, then let's not implement it." (10-30)
10. "do not write any development plan ... in the repository" (10-28 07:03)
11. "don't merge PR. I want to review that first" (10-28 07:43). Only Aashish merges.
12. "we have to respect the existing contracts of an existing service" (11-01 about 15:14)
13. "all dependency versions in one single place" (10-27 22:46, 10-28 18:46); "Lombok ... was explicitly
    mentioned" (10-28 07:57)
14. "If everything is already clear... continue." (10-30 22:41)

Also firm, from agent memory (not a direct quote): MVC and WebFlux always behave the same, every status and error,
in every mode (11-01); virtual threads never on by default; one judge per PR unless he asks for two.

---

## Summary

| # | Point | Found by | Status |
|---|---|---|---|
| 0 | Decisions stayed in chat | 3 | Chat only |
| 1 | Built before go, extras | 3 | Partly |
| 2 | Firm rules lost | 3 | Chat only |
| 3 | Gaps after "done" | 2 | Chat only |
| 4 | MVC and WebFlux differ | 3 | Partly |
| 5 | Context lost across hops | 2 | Partly |
| 6 | Leaks through odd inputs | 3 | Partly |
| 7 | Over-engineering | 3 | Partly |
| 8 | Copied MVC/WebFlux code | 2 | In repo |
| 9 | Follow-up PR chains | 2 | Chat only |
| 10 | Slow request path | 2 | Partly |
| 11 | Tests green with code broken | 3 | In repo |
| 12 | Stale docs and Claims | 3 | Partly |
| 13 | Code rules broken again | 3 | Partly |
| 14 | AI-looking code, tricks | 3 | Partly |
| 15 | Fix adds a bug | 2 | Chat only |
| 16 | Boot assumed | 2 | Partly |
| 17 | Conflicts, CI, Sonar left to owner | 2 | Chat only |
| 18 | Ready then hold | 3 | Partly |
| 19 | Judge misses owner rules | 1 | Partly |
| 20 | Findings without proof | 2 | Partly |
| 21 | Rule files contradict | 2 | Chat only |
| 22 | "What's happening?" | 3 | Chat only |
| 23 | Questions in threads | 3 | Chat only |
| 24 | Answers too long | 3 | Partly |
| 25 | Short instructions misread | 1 | Chat only |
| 26 | Permission for trivial fixes | 1 | Chat only |

Points 8, 11 and 31 are already written rules that were not followed. A new rule alone will not fix those; they
need a check, such as a Claims line the judge fails without.

---

## Open questions for the new session

One decision per rule. Ask them one at a time, in the shape of point 24.

0. Where do new owner rules go: `AGENTS.md`, a topic file, or a new `docs/agents/owner-rules.md`?
1. What counts as "go": only the word "go", or also picking a card option?
2. Does each PR list the owner decisions it covers in its Claims, and does the judge fail it if one is missing?
3. What is on the module done-checklist, and where does it live?
4. Is the MVC/WebFlux case table a file in the repo or only in the PR description?
5. Which contexts go in the fixed async list, and which module owns the shared test?
6. Is the hostile-input list a fixed list in `security.md`, or written per PR?
7. Merge tiny single-use types: how does this fit with ST-ENG-16 and ST-ENG-17, which say to split a second job
   out even when the file is short?
8. Nothing to decide; the rule exists. Only how to enforce it (see 13).
9. After how many follow-up PRs must the agent stop and write a design: two?
10. Is a large-input test required for every request-path change, or only for regex and parsing code?
11. Should the judge fail a PR whose Claims have no fault -> failing-test list?
12. Must the Claims include the grep output for each changed key or class?
13. Which rules go on the self-check list, and how many lines at most?
14. Is "match the nearest existing class" a rule, and who decides which class is nearest?
15. Must the implementer rerun the whole module after each review fix, or only the touched tests?
16. Must every Boot claim cite a source link or file?
17. Where do agents read Sonar results if `gh api` check runs do not show them?
18. Who says "ready to merge": the implementer or the coordinator? Is Codex's result required?
19. Does the judge get the owner's standing rules as a file or inside its prompt?
20. What makes a finding "realistic": reproduced on a real setup, or a credible path?
21. Who checks a rule change against every rule file: the author, the judge, or both?
22. Which PR states need a chat line, and should it ever be quiet?
23. Can a thread ever ask the owner directly, or must every question go through the coordinator?
24. Is "under 10 lines" a hard limit for questions?
25. Restate every short instruction, or only ones that could mean two things?
26. Which changes still need the owner's go inside an approved PR: defaults, keys, public API, contracts, others?
27. to 31. Does each extra point need a rule, or is it covered by another one?
