## Judge verdict: PR #265, B2b (a 404 read as an empty `Optional`), round 2

Branch `claude/project-thread-ex55r3`, head `ba83919`, track Feature, code review round 2. Judge model: Sonnet 5.5
(round 1 was Opus 5.5; the note's round-2 judge was Fable 5.1).

**PASS.** Both round 1 findings are fixed and I found nothing new.

### Gate

- Mergeable: `origin/main` is `abeaa2a`, which is the merge base of the head, so the branch merges cleanly. GitHub
  reports `mergeable_state: clean`.
- CI green at `ba83919`: Verify and SBOM, CodeQL (actions, java-kotlin), the wiki check, SonarCloud (gate passed). The
  wiki sync job is skipped, as expected on a PR.
- Threads: the two ST-ENG-02 Codex threads are fixed and resolved. One Codex thread (ST-ENG-54, `getReturnType()`,
  `OptionalNotFound.java:44`) is still open. It is answered with evidence, and I accept that answer (see below). It
  must be resolved before "Ready to merge".

### Round 1 findings

| # | Finding | Result | Evidence |
|---|---|---|---|
| 1 | Decorator beans looked up and sorted on every call (ST-ENG-54) | FIXED | `StratumClientGroups.decorateCalls` (`:70-75`) runs `decorators.orderedStream().toList()` once per client group at start-up. Both the adapter decorator and the request processor use that list. `grep -rn orderedStream starters/stratum-starter-http-client/src/main` shows no per-call use in the call path; the other hits are start-up customizer loops. |
| 2 | No test that a non-404 4xx still throws | FIXED | `ErrorDownstream.FORBIDDEN` (a 403, no body), `OrdersClient.findForbidden()` and `OptionalNotFoundIntegrationTest.otherClientErrorStillThrows`, which asserts `DownstreamException` with status 403. I planted round 1's P5 (`== NOT_FOUND` changed to `is4xxClientError()` in `OptionalNotFound`): the test **fails** (8 run, 1 failure). Code restored; `git status` clean. |

### Full diff against the note, ADRs and rules

| Requirement | Result | Evidence |
|---|---|---|
| 404 is empty for the four shapes Spring wraps | MET | `notFoundIsEmpty` (4 rows) passes on JDK 25 |
| A success is present; a 404 on a non-`Optional` method throws | MET | `foundIsPresent`, `plainMethodStillThrows` |
| Another status throws, 4xx included | MET | `otherClientErrorStillThrows` (403), `unmappedAnswerKept` (500 on `findOrder`) |
| `mapError` not asked about that 404 | MET | `OPTIONAL_NOT_FOUND_ORDER` (-1) is inside `ERROR_TRANSLATION_ORDER` (-2); `optionalNotFoundNotMapped` |
| A missing answer on an unmarked call fails fast | MET | `AroundCallExchangeAdapterTest` (2 tests, pass) |
| A 404 never counts against the breaker (ADR 0011:116) | MET | `OptionalNotFound` is outside resilience (-1 before 0), `RetryRules` unchanged, `notFoundNeverOpensBreaker` |
| Only interfaces do this, beans keep `NotFound` | MET | Decorators and `prepare` are applied only in `StratumClientGroups.decorateCalls`; stated in `http-client-errors.md` |
| Docs (errors page, http-client.md, migration guide) | MET | Read against the code; ST-DOC-05: Java example, no key so no YAML |
| No new public type or key (ST-ENG-56/57) | MET | `OptionalNotFound`, `ClientExchangeDecorator` are `@StratumInternal`; new constants sit in `HttpClientConstants` |
| Bean replaceable (ST-ENG rule on `@ConditionalOnMissingBean`) | MET | `stratumOptionalNotFound` is `@Bean @Fallback @ConditionalOnMissingBean` |
| File lengths (ST-AG-10) | MET | `wc -l` equals the Claims for all 14 files; the largest are `http-client.md` 484 (limit 500) and `ErrorDownstream.java` 209 (limit 400) |

Tests I ran at `ba83919` on JDK 25: `AroundCallExchangeAdapterTest` 2, `ErrorMapperIntegrationTest` 3 and
`OptionalNotFoundIntegrationTest` 8, all with 0 failures. A restricted run fails only the JaCoCo gate, because
`-Dtest` and `-Dit.test` skip most tests; CI's full Verify, which includes the gate, is green.

### ST-ENG code rules

| Rule | Result |
|---|---|
| ST-ENG-02 new behaviour behind an existing interface | OK: `OptionalNotFound` is a `ClientExchangeDecorator`; `ErrorTranslatingExchangeAdapter` is untouched |
| ST-ENG-42/43 Lombok and builders | OK: `StratumClientGroups` keeps `@RequiredArgsConstructor`; the new class has no fields and needs none |
| ST-ENG-39/40 JDK functional interfaces, `var` | OK: lambdas with unnamed parameters, `var ordered` |
| ST-ENG-41 `@NullMarked` | OK: `@Nullable` on `around` and `answerOf`, as in the existing class |
| ST-ENG-14/15 Javadoc | OK: every new type, method and constant has brief Javadoc (private helpers too) |
| ST-ENG-54 cheap request path | OK: ordered list once at start-up; `getReturnType()` is one field read per call |

### The open Codex thread

I accept the author's rejection, as round 1 did. `getReturnType()` on the `Method` Spring already passes in is one
field read, not the lookup, scan or reflective call that ST-ENG-54 forbids. A cache would add a map read per call
and state to keep. Nothing in the change reflects per request.

### Findings

None.

### Notes (not findings)

- The `RestClient` and `RestTemplate` beans of C2b are not on main yet, so "beans keep `NotFound`" has no test. The
  PR says so. The C2b PR should add that test when the beans land.
