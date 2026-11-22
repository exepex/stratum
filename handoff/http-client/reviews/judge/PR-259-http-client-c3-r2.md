# Judge verdict: PR #259, C3 mocked clients and `MockClient`, round 2

**PR #259** `claude/project-thread-79g1e3`, head `c16c71c`, base `main` at `abeaa2a`. Track: Feature. Review step: code
(judge steps 1 to 5), round 2, after round 1 FAIL (Fable, `reviews/judge/PR-259-http-client-c3.md`). Judge model:
Opus 5.5. I read AGENTS.md and all six topic files, the round-1 verdict, the diff `97db87b..c16c71c` and the whole PR
diff against `main`, the PR threads, CI, Codex and Sonar at `c16c71c`.
Run on JDK 25: `./mvnw -pl starters/stratum-starter-http-client -am verify` exits 0. The starter's 34 suites report 0
failures and 0 errors; the mock suites hold 24 test cases. I planted five faults, each checked with a `-o -pl` verify.

**FAIL** (no blocking, no should-fix; one minor finding)

## Gate
- Merge with `main`: `git merge-tree origin/main c16c71c` is clean, `main` (`abeaa2a`) is an ancestor of the head,
  and GitHub reports `mergeable_state: clean`.
- CI at `c16c71c`: Verify and SBOM, CodeQL, Analyze (java-kotlin, actions), the wiki check and SonarCloud all succeed.
  The wiki sync is skipped, as it is on every PR.
- Codex: the code review at `c16c71c` completed with no findings. All 11 threads are answered and resolved, and I
  found each fix in the diff.
- Sonar: the quality gate passes with 2 new issues, S9398 on `ClientTransport.java:36` and `:52`, answered in Claims 5c.
  I accept the answer: see "Sonar S9398" below.
- Gate passed.

## Round-1 findings
1. ST-ENG-13 catch names: **fixed**. `MockAnswers.java:177` `interruption`, `:224` `unreadable`,
   `MockRequestFactoryTest.java:110` `failure`. `grep "catch ("` finds no single-letter name in the `Mock*` files.
2. Repeated `MockAnswers` test setup: **fixed**. `RestClientTestData.answersWithoutMapper()` (`:130`) is used by
   `MockAnswersTest:20`, `DefaultMockClientsTest:31` and `MockRequestFactoryTest:29`, and nothing else repeats the setup.
- Note 1, the `respond()` line in the docs: **done**. `http-client-mocks.md:84` says "Pass the object, such as
  `new Payment(...)`, not its JSON text: a text is sent as `text/plain`...".

## Changes since round 1
- **Merge of `main` after B2 (6f330f3).** The tests now expect B2's results: `missingAnswerIs501` expects a
  `DownstreamException` and `endsByTotalTimeout` expects `SX-HTTPC-0001` through `CallChecks.assertEndsWith`. The
  `SX-HTTPC-0001` delay row on `http-client-mocks.md:86` matches `docs/guides/error-codes.md`. MET.
- **`ClientTransport` strategy (7599c8b).** This fixes Codex's ST-ENG-02 finding. `StratumClientBuilders.configure`
  (`:111`) no longer branches; `ClientTransport.of` (`:29-32`) picks `Mocked` or `Pooled` once per build. That is a
  static factory allowed by ST-ENG-47. The customizers still run after the transport (`:112`). `restClient(name)`
  builds once per client (`DefaultStratumHttpClients:42`, `computeIfAbsent`), so nothing is allocated per call. It is
  one new file for one finding (ST-AG-12), and a sealed interface with two implementations is the smallest form
  ST-ENG-02 allows. MET.
- **64 KB cap on a mocked error body (7599c8b).** `MockRequestFactory.java:128-135` cuts the body at
  `HttpClientConstants.ERROR_BODY_LIMIT` when `status().isError()` is true. That is the same set as `ErrorBodyLimit`'s
  `>= 400` (`ErrorBodyLimit.java:44`) and the same constant. Successful bodies stay whole. Tests: `ErrorBody.errorBodyCut`
  and `successfulBodyWhole`. MET.
- **Call mark worked out once (33f3e79).** `MockCallProcessor.java:30,38` caches each method's `MockCall` with
  `computeIfAbsent`. The cache is bounded by the interface's method count, and a call does no reflection (ST-ENG-54). MET.
- **Instance helper (c16c71c).** `readsNoBody` is a private instance method (ST-ENG-47). The `Mock*` classes and
  `ClientTransport` have no private static method left. MET.

## Sonar S9398 (Claims 5c)
I accept "not accepted". ST-ENG-40 asks for sealed types for closed hierarchies, and permitted subtypes nested in their
sealed interface keep the whole strategy in one 63-line file. The module already nests its helper types the same way
(`ErrorBodyLimit.LimitedEntity`, `LimitedStream`). Moving them out would add two files with no gain, against ST-AG-12's
one-file-per-fix limit. Sonar's gate passes with both issues open.

## Findings
1. **Minor (ST-ENG-06; judge step 3, "repeated test setup").** `MockRequestFactoryTest` repeats the same three
   request lines in `call()` (`:105-107`) and in `bodyLength()` (`:117-119`), which 7599c8b added:
   `var request = factory.createRequest(URI.create("http://payments/files"), HttpMethod.GET);`
   `request.getAttributes().put(MockAnswers.METHOD_ATTRIBUTE, new MockCall(METHOD, false));`
   `try (var response = request.execute()) {`
   **Fix:** add one private method, such as `ClientHttpResponse execute() throws IOException`, that creates the
   request, marks it and executes it. `call()` and `bodyLength()` then both use it inside their own `try` blocks.

## ST-ENG code rules checked (new code since round 1)
ST-ENG-02: a strategy, not an `if`. ST-ENG-05: each new type has a use today (`Pooled`, `Mocked`, the `calls` map).
ST-ENG-13: names are functional. ST-ENG-15: every new type and method has short Javadoc, and the comment at
`MockRequestFactory:130` says why. ST-ENG-40: sealed type and `var`. ST-ENG-42: `@RequiredArgsConstructor` on both
transports. ST-ENG-46: one-expression lambdas and method references. ST-ENG-47: only the `of` factory is static.
ST-ENG-54: no per-call reflection. ST-ENG-56: `ClientTransport` and its subtypes are package-private, so they are not
API. ST-ENG-57: no new package. ST-SEC-01 to 06: no body, argument or secret is logged, and the 64 KB cap matches
production. Line counts: each changed file is within its limit; the largest are `http-client.md` (485 lines, text)
and `HttpClientPropertiesTest.java` (388).

## Planted faults
5 planted, 5 caught, 0 survived:
- P1, the error body is not cut: caught by `errorBodyCut`.
- P2, the cut applies to every status: caught by `successfulBodyWhole`.
- P3, `ClientTransport.of` inverted: 26 tests fail across `MockClientIntegrationTest` and `RetryIntegrationTest`.
- P4, `Mocked` adds the auth initializer: caught by `fromFile`.
- P5, the cached `bodyless` mark inverted: caught by `noBodyNeedsNoFile` and `missingAnswerIs501`.

I restored the tree after each fault; `git status` is clean.

## Notes
1. `Boolean.TRUE.equals(settings.mock())` appears in `ClientTransport:31`, `StratumClientGroups:88` and
   `DefaultMockClients:48`. These are one-expression reads of a nullable `Boolean`, the same idiom as
   `ClientResilience:236` and `ClientConnectionPools:151`. This is not copied logic.
2. Not run by me: the Docker-bound modules (data JPA, cache). CI's Verify job runs them and is green at `c16c71c`.
