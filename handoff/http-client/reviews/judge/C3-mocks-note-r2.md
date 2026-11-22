# Judge verdict: C3 mocks design note, round 2

**Design note:** `design/http-client-libraries/c3-mocks/c3-design-note.md` (round 2), base `main` at `aabdae8`.
Track: Feature. Review step: design note (judge steps 1, 2, 3 and 5). Judge model: Fable 5.1 (configured
`claude-fable-5-1`; round 1 ran on Opus 5.5). Sources read: AGENTS.md and all six files in `docs/agents/`; ADR 0011
(sections 2.4, 5.5, 9, 10); `feature-list.md` section 10; the round-1 verdict; the B1, A1 and C2a notes; the
starter's code on `main` (`StratumClientGroups`, `StratumClientChecks`, `ClientConnectionPools`,
`HttpClientErrorCode`, `META-INF/spring.factories`); spring-web and spring-test 7.0.10 sources (Maven Central
sources jars, unpacked and read). Not run: no build, no probe (design-note review).

**FAIL** (no blocking finding; two should-fix, three minor)

## Gate
The note is complete: questions 1 to 4 answered, each in three lines or fewer, with the details, the decision and
test table and the round-1 answers. Gate passed.

## Gap list
- 10.1 Any client mocked by config, same interface: MET (`mock` under `defaults` and `clients.<name>`; fake
  `ClientHttpRequestFactory` set by `StratumClientBuilders.configure`); see F2 for `restClient(name)`.
- 10.2 Answers from files per method: MET (`classpath:stratum-mocks/<client>/<method>.json`, bytes cached).
- 10.3 Works in tests and running apps: MET (WARN at startup; records only after `of(type)`); see F1 for tests.
- 10.4 WARN at every startup per mocked client: MET (`LogLineCapture` row).
- 10.5 Assert calls, slow and failing answers: MET for calls and `fail(status)`; PARTLY for slow answers (M1).
- Owner 13:39Z, `MockClient` in the starter, only for `mock: true`: MET (`of(type)` on a non-mocked client stops).
- Owner: no request interceptors: MET (`HttpRequestValues.Processor` and a request factory, no interceptor).
  No WireMock: MET (`SilentPort`, scripted answers). Code 1009 reserved for C3: MET.
- ADR 0011 section 10 route: with the owner (ADR 0012, 13:45Z), not judged here. The note is consistent with it.
- ADR 5.5, no network for a mocked client: MET (no connection; no auth initializer, test row).
- Cross-lane: B1's `AttemptTimeLeft.timeLeft()` is in the B1 note (lines 67-70, agreed 13:45Z): MET (see M2).

## Round-1 findings, each checked
- F1 accepted. Facts reproduced in spring-web 7.0.10: `RestClientAdapter.java:105` copies the `HttpRequestValues`
  attributes to the request; `DefaultRestClient.java:601-602` copies them onto the `ClientHttpRequest` before
  `execute()` (line 614); `HttpRequest.getAttributes()` is `@since 6.2`. `MockRestServiceServer` mocks a
  `RestClient` the same way (spring-test `MockRestServiceServer.java:295-299`, `restClientBuilder.requestFactory`).
  The status-handler test row is there. The fix is the design, not a patch.
- F2 accepted. `SocketTimeoutException` from `execute()` becomes `ResourceAccessException`
  (`DefaultRestClient.java:623-624`), the same as a real read timeout; the cap uses B1's value. Wording: M1.
- F3 accepted (records only after `of(type)`; test row). F4: owner decided, not failed. F5 accepted: spring-test
  7.0.10 loads default listeners with `TestContextSpringFactoriesUtils.loadFactoryImplementations`
  (`AbstractTestContextBootstrapper.java:214-216`) from `META-INF/spring.factories`, where spring-test lists its
  own `MockitoResetTestExecutionListener`; the starter already has that file. F6, F7 (`HttpServiceMethod.java:426`
  answers `HttpHeaders` with `exchangeForHeaders`), F8 and F9 accepted as written.

## Findings

1. **Should fix (ST-DOC-06, ST-AG-01; design-note question 1).** The startup check and the test example contradict
   each other. "A method whose return type needs a body and has no file stops startup with `SX-HTTPC-1009`", and
   `CheckoutTest` sets `clients.payments.mock=true` and scripts `createPayment` with no file named. As written,
   that test stops at startup unless `stratum-mocks/payments/createPayment.json` exists for every body-returning
   method of `PaymentClient`, which every team that mocks only in tests (10.5) hits on its first run. **Fix:**
   decide and say it: either a file per body-returning method is required even when tests script every answer
   (then the example names its file, and question 1's "lines a service writes" says so for tests), or a missing
   answer fails at the call with a clear message and there is no startup check (fewer lines, as
   `MockRestServiceServer` does; the code would then be a runtime `0xxx` code, not 1009). Add the test row.
2. **Should fix (design-note question 2; ADR 9.2).** `StratumClientBuilders.configure` is shared with C2a's
   `stratumHttpClients.restClient("<name>")`, and the note says `configure` "chooses `MockRequestFactory` ... when
   `mock: true`". A `restClient("payments")` call on a mocked client therefore reaches the fake factory with no
   `stratum.mock.method` attribute (the Processor runs only in the proxy), and the note does not say what it
   answers. **Fix:** one line and one test row: either `restClient(name)` is never mocked (`configure` swaps the
   factory for proxy groups only) or it fails at the call with a message naming the client and `mock`; and the
   module page says which.
3. **Minor (10.5 slow answers).** "The fake request waits the smaller of the delay and the attempt's limit ...
   then throws `SocketTimeoutException`" reads as always throwing, so a `delay(200ms)` under `timeout: 5s` would
   never answer. **Fix:** "waits the delay and answers; when the delay is longer than min(`timeout`, time left),
   waits that limit and throws ...", plus a test row for a delay within the limit answering after it.
4. **Minor (ST-AG-01).** "Not verified: that B1 exposes its time-left value" is stale: the B1 note (lines 67-70)
   names `AttemptTimeLeft.timeLeft()` returning `Optional<Duration>`, empty outside a Stratum call. **Fix:** cite
   it, and say the mock falls back to `timeout` when the value is empty (finding 2's path).
5. **Minor (judge question 3, ST-ENG-53; ST-TEST-20).** Question 3 does not state the thread hop answer: none,
   the delay sleeps on the caller's thread. And `MockClientsResetListener` runs for every Spring test of a service
   that has the starter, mocked client or not; the note should say it does nothing when the `MockClients` bean
   has no mocked client (or is absent), with the test.

## Planted faults
None: a design-note review runs no build.

## Notes
- The eight ST-AG-14 parameters, the module and package (`restclient`, no new package, no cycle), the model
  (`MockRestServiceServer`), new/existing/hybrid services and the API/internal split are all present now.
- `MockClient.respond(value)` written with the application's JSON mapper then read by Spring's converters is sound;
  the code step should show the mapper comes from the context, not a new one, so a team's Jackson settings apply.
- Thread.sleep for the delay is production code simulating latency, not a test wait; ST-TEST-14 does not apply.
- Streamed uploads: `DefaultRestClient.java:486-492` writes a `StreamingHttpOutputMessage.Body` to `getBody()`
  when the request is not streaming, so a fake request with a discarding `OutputStream` gives "read and dropped".
