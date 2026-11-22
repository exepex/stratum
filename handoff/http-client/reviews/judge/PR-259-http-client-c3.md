# Judge verdict: PR #259, C3 mocked clients and `MockClient`

**PR #259** `claude/project-thread-79g1e3`, head `97db87b`, base `main` at `ba0e42b`. Track: Feature. Review step: code
(judge steps 1 to 5), round 1. Judge model: Fable 5.1 (`claude-fable-5-1`). Read: AGENTS.md and all six topic files;
the C3 note round 3 and both note verdicts; the whole diff; the PR threads, CI, Codex and Sonar at `97db87b`.
Run on JDK 25: `./mvnw -pl starters/stratum-starter-http-client -am verify` → exit 0 (22 mock tests: 10 unit, 12 IT,
0 failures); seven planted faults, each with a `-o -pl` verify run of the catching tests.

**FAIL** (no blocking, no should-fix; two minor findings)

## Gate
Claims complete (request, requirements, note round 3, broken-code proof, self-review, not done, files read, line
counts). PR open, not draft. CI green at `97db87b` (Verify and SBOM, CodeQL, Analyze ×2, wiki check, SonarCloud).
Codex: code review at `97db87b` completed with no findings; its seven threads (two ST-SEC-03 `toString` leaks, reset
recording, streamed delay, `MockAnswer` builder, two ST-TEST-17 groupings) all answered with a commit and resolved;
I reproduced each fix in the diff. Sonar: quality gate passed, 0 new issues. Gate passed.

## Gap list (owner's request: ADR 0011 section 10, feature list 10.1 to 10.5, owner decisions 13:39Z and 13:45Z)
- 10.1 `mock` key under `defaults` and `clients`, properties, YAML, Java, IDE metadata: MET (`ClientSettings.java:70,92,117`; `HttpClientPropertiesTest.javaSetsMock`, `readFromYaml`; `ConfigurationMetadataTest`).
- 10.1 Any client mocked, same interface, no connection, no credential: MET (`StratumClientBuilders.java:50-57`; `fromFile` on a `SilentPort`, fault P1 caught by 11 ITs).
- 10.2 Answers from `stratum-mocks/<client>/<method>.json`, read once, parsed per call: MET (`MockAnswers.java:160-163, 211-227`; `readAnewEachCall`).
- 10.3 Works in a running app: WARN per mocked client at every startup (ADR 10): MET (`DefaultMockClients.java:46-53`, `SmartInitializingSingleton`; fault P4 caught).
- 10.3 Nothing kept in memory without `of(type)`: MET (`MockAnswers.java:98-101`; fault P6 caught).
- 10.5 Script answers, `fail(status)` through the team's status handlers, delays, `calls`: MET (`teamStatusHandlerSeesIt`, `scriptWins`, `withinLimitAnswers`; faults P2, P3 caught).
- Note round 2 F1: no startup check, 501 with the file to add; `void`/`HttpHeaders`/`ResponseEntity<Void>` need no file: MET (`missingAnswerIs501`, `noBodyNeedsNoFile`; fault P5 caught).
- Note round 2 F2: `restClient(name)` on a mocked client answers 501 naming the client; module page says so: MET (`MockAnswers.java:151-158`; `restClientOfClientIs501`; `http-client-mocks.md:53-55`).
- Note round 2 F3/F4: delay within the limit answers; beyond it fails as a read timeout within `total-timeout`, streamed download keeps `timeout`: MET (`MockRequestFactory.java:61-75`; `endsByTotalTimeout`, `MockRequestFactoryTest`).
- Note round 2 F5: reset after each test by a `TestExecutionListener` in `spring.factories`, idle without the bean: MET (fault P7 caught by `fileInSecondTest` and four more; `MockClientsResetListenerTest`).
- Owner 13:39Z `MockClient` in the starter, only with `mock: true`: MET (`refusesClientNotMocked`). Owner 13:45Z ADR 0012, ADR 0011 unedited: MET (ADR 0012 decision 2 on `main`; no ADR change in the diff).
- Retries and breaker unchanged around a mocked call, recorded once: MET (`retriedAsReal`).
- Docs: mocks page with both formats and the Java pointer, property row, Planned list, `docs/README.md`: MET; every link target exists.
- Out of scope, declared: metric or actuator info for a mocked client; reactive mocks. Nothing built that was not asked for.

## The five deviations from the note
1. Latch instead of `Thread.sleep` for the delay: accepted. Checkstyle `noThreadSleep` covers main code; `reset()` ending a finished test's delayed calls is a real improvement (`MockAnswers.java:172-187`).
2. No-credential test with `auth.bearer` instead of `client-credentials`: accepted; that kind is not on `main` (lane A). Claims 6 says so.
3. `respond(CharSequence)` as `text/plain`: accepted and documented (`http-client-mocks.md:84`). See Note 1.
4. WARN proved by a unit test of `afterSingletonsInstantiated` instead of a `LogLineCapture` IT: accepted; Spring calls it once per startup, and the test checks the exact line.
5. "`mock: false` keeps the pool" proved by the existing ITs: accepted; fault P1 shows every non-mocked IT depends on that branch.

## Findings
1. **Minor (ST-ENG-13).** `MockAnswers.java:177` `catch (InterruptedException e)` and `:224` `catch (IOException e)`;
   `MockRequestFactoryTest.java:76` `catch (IOException e)`. The rule names caught exceptions (the module uses
   `failure`, `missing`). **Fix:** rename the three variables, for example `interrupted`, `unreadable`, `failure`.
2. **Minor (ST-TEST-16, ST-TEST-13).** `new MockAnswers(new StaticListableBeanFactory().getBeanProvider(JsonMapper.class))`
   is repeated in `DefaultMockClientsTest.java:34`, `MockAnswersTest.java:23` and `MockRequestFactoryTest.java:31`.
   **Fix:** one method in `testsupport`, such as `RestClientTestData.answersWithoutMapper()`, used by the three.

## ST-ENG code rules checked
ST-ENG-42 Lombok: `@RequiredArgsConstructor` on every class, `@Getter` on `MockRequest.method`, `@Builder(toBuilder)`
on `MockAnswer` (four fields), `@Slf4j`; `toString` overrides carry logic (ST-SEC-03). ST-ENG-43 builders used in the
factories and `withDelay`. ST-ENG-39/40 streams, `switch` pattern on `value`, `var`, `Optional` returns only. ST-ENG-41
`@NullMarked` package-info on the new test package; `@Nullable` where null is possible. ST-ENG-45 `METHOD_ATTRIBUTE`
and `ANSWER_FILE` constants. ST-ENG-46 one-expression lambdas. ST-ENG-47 statics only in `MockAnswer` factories.
ST-ENG-56 API: `MockClients`, `MockClient`, `MockScript`, `RecordedCall` (on the arch list, members expose JDK and
Spring types only); internal: `MockAnswers`, `DefaultMockClients`, `MockClientsResetListener`; rest package-private.
ST-ENG-57 all in `restclient`, no new package. ST-ENG-62 `spring-test` optional, reason in the POM. ST-ENG-66 specific
catches only. ST-SEC-01 to 06: no secret, no body or argument logged or printed, CRLF stripped from the client name in
the WARN (`DefaultMockClients.java:57-61`). Line counts: every changed file under its limit (checked each).

## Planted faults
7 planted, 7 caught, 0 survived: P1 mock condition inverted (11 ITs); P2 script ignored (6 ITs); P3 delay never
capped (`otherCallEndsByTotalTimeout`); P4 WARN logged at DEBUG (`warnsPerMockedClient`); P5 `HttpHeaders` asks for
a file (`noBodyNeedsNoFile`); P6 always records (`nothingRecordedBeforeAsked`); P7 listener removed from
`spring.factories` (5 ITs). Tree restored after each; `git status` clean.

## Notes
1. `respond("{...}")` for a method returning an object sends `text/plain`, which Jackson's converter does not read;
   a team gets `UnknownContentTypeException`. Documented behaviour, not a defect; a line on the page saying "pass the
   object, not its JSON text" would save a question.
2. `MockAnswers` also owns the answer-file cache (`file`, `read`): two small methods, which ST-ENG-16 says not to
   split. Theoretical only.
3. The branch merged `main` instead of rebasing (Claims 5); the head contains `ba0e42b`, which is what ST-AG-17's
   rebase is for. Not a finding.
4. Not verified by me: Docker-bound modules (data JPA, cache) locally; CI's Verify job covers them and is green.
