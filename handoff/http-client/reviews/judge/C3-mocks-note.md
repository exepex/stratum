# Judge verdict: C3 mocks design note, round 1

**Design note:** `design/http-client-libraries/c3-mocks/c3-design-note.md` (round 1), base `main` at `aabdae8`.
Track: Feature. Review step: design note (judge steps 1, 2, 3 and 5). Judge model: Opus 5.5 (configured
`claude-opus-5-5`). Sources read: AGENTS.md and all six files in `docs/agents/`; ADR 0011; `gap-review-merged.md`;
`feature-list.md` section 10; the B1, A1 and C2 notes; spring-web 7.0.10 sources (Maven Central sources jar).

**FAIL**

## Gap list
- 10.1 Any client mocked by config, same interface: MET (`mock` key, decorator only when true).
- 10.2 Answers from files per method: MET (`stratum-mocks/<client>/<method>.json`).
- 10.3 Works in tests and in running apps: MET in the note; see F3 for the running-app memory bound.
- 10.4 WARN at every startup per mocked client: MET (test row with `LogLineCapture`).
- 10.5 Assert calls, simulate slow and failing answers: PARTLY (F1 failing answers, F2 slow answers).
- Owner 13:39Z: `MockClient` in the starter, only for `mock: true`: MET (`of(type)` on a non-mocked client stops).
- Owner: no request interceptors: MET. No WireMock, `SilentPort` helper: MET.
- Lane B contract, mock innermost in the `ClientExchangeDecorator` chain: MET as written, but see F1.
- ADR 0011 section 10 says `stratum-test`: PARTLY (F4: route of the correction).
- ADR 5.5, no network for a mocked client: MET under the chosen design (initializers never run).

## Findings

1. **Blocking (ST-AG-01, ST-ENG-28 for tests' trust).** The reason for rejecting a fake request factory is wrong,
   and the chosen decorator skips the client's own answer handling. Note: "a fake request factory, which sees only the
   URL, not the method". Spring 7.0.10: `HttpRequest.getAttributes()` exists "@since 6.2", and
   `DefaultRestClient` copies the request attributes onto the `ClientHttpRequest` before `execute()`
   (`DefaultRestClient.java:601-602`, then `clientRequest.execute()`), and `RestClientAdapter.java:105` copies
   `HttpRequestValues` attributes into the request. So a fake request can read the method name the same Processor sets.
   Consequence of the decorator: a mocked answer never passes through the client's status handlers or message
   converters. A team that sets `defaultStatusHandler` in a `StratumClientCustomizer` (A1 names this case) gets its own
   exception for a real 404 but Spring's `HttpClientErrorException` from `fail(404)`; B2's mapping to
   `DownstreamException` has the same risk if it sits in a status handler. The decorator also re-implements return-type
   handling (void, `ResponseEntity`, `Optional`, `InputStream`) that Spring's converters already do.
   **Fix:** re-weigh with the correct fact. Either a fake `ClientHttpRequestFactory` per mocked client (Spring then
   converts bodies and runs status handlers; the auth initializer is not added for a mocked client, so still no
   network), or keep the decorator with a true reason and say how `fail(status)` and file answers match a real answer
   of the same status, with a test: a team status handler mapping 404, and `fail(404)` giving the team's exception.
2. **Should fix (ADR 4.4, feature 10.5).** "A delay longer than the client's `timeout` fails like a real read
   timeout, after `timeout`." B1 enforces each attempt's limit as min(`timeout`, time left under `total-timeout`)
   through the Apache request config (B1 note, "Time" and "Facts"), which a mock never reaches in either design. With
   `timeout` 5s, `total-timeout` 8s, `retries` 3 and `delay(10s)`, the note's rule gives a second attempt of 5s and a
   call of 10s or more; a real downstream ends the call by 8s. **Fix:** the mock waits min(delay, `timeout`, time
   left from B1's scoped value) and then throws what Spring throws for a real read timeout
   (`ResourceAccessException` caused by `SocketTimeoutException`), so B1 classifies it the same; test row: the call
   ends by `total-timeout`.
3. **Should fix (ST-ENG-52, ST-AG-14 memory).** "calls recorded only in `MockClient` (tests)". `MockClient` now lives
   in the starter's main code, and 10.3 runs `mock: true` in demo apps for days. If every call's arguments are
   recorded whenever the client is mocked, the list grows without bound. **Fix:** record calls only after a test
   asked for the client with `of(type)` (or bound the list), say which in the note, and add the test row.
4. **Blocking (documentation.md, "Where things are documented", `docs/adr/` row: "An accepted record is never
   edited... a new record replaces it").** Note: "The C3 pull request corrects ADR 0011 section 10". ADR 0011 is
   Accepted 2026-11-09 and merged (PR #239); its two commits (`43e2c6d`, `2a50969`) were before merge, so there is no
   precedent of editing it after. **Fix:** a short new ADR that replaces ADR 0011 section 10 (and is linked from
   `docs/README.md`), or the owner's explicit override of that rule for this item, recorded in the note.
5. **Should fix (ST-TEST-14, realistic test use).** Scripts and recorded calls live in a bean of the application
   context, which Spring's test framework caches and shares across test classes. A class that scripts `fail(503)`
   and does not call `reset()` changes the answers of a later class with the same context (the reason
   `@MockitoBean` resets after each test). The note leaves `reset()` manual and does not say this. **Fix:** state the
   reset rule and show it on the module page (for example `@AfterEach void reset() { mockClients.reset(); }`), or
   reset automatically; one test row either way.
6. **Minor (judge design-note questions).** Q2 does not name the module and package of `MockClients`,
   `MockClient`, `MockExchange` and `MockAnswers`, why no package cycle results (ST-ENG-57), or the class it is
   modelled on. Q3 covers six of the eight ST-AG-14 parameters ("Safe and fast" has no throughput or scalability
   line). Q1 does not say new, existing and hybrid service. **Fix:** add these facts.
7. **Minor.** Decision table row "Missing file stops startup | `StartupChecksTest` 1007"; the note's code is
   `SX-HTTPC-1009`, and 1007 is C2a's. The no-file list ("`void` and `ResponseEntity<Void>` need no file") misses
   `HttpHeaders`, which Spring answers with `exchangeForHeaders` (`HttpServiceMethod.java:426`), so the startup
   check would wrongly stop on it. **Fix:** 1009, and add `HttpHeaders` to the list and the test.
8. **Minor.** "Files are read once at first use and cached per method" with "a cached parse per call": if the
   parsed object is cached, every call gets the same instance, and a caller that changes a returned DTO changes the
   next answer. **Fix:** cache the bytes, parse per call (cost is negligible at mock volumes); say so.
9. **Minor.** The `HttpRequestValues.Processor` that adds the method name (and, for `calls`, the arguments) should
   be added only to mocked clients' proxy factories; on other clients the attributes are copied into every real
   request and its observation context (`DefaultRestClient.java:601-604`) for nothing. **Fix:** say it is added only
   when `mock: true`.

## Planted faults
None: a design-note review runs no build (judge step 4 is not in this review).

## Notes
- The `SX-HTTPC-1009` startup check reads the methods of a `@StratumClient` interface. That is inside the 11-06
  boundary: `StratumClientChecks` already does the same for `SX-HTTPC-1002`, and the check is driven by Stratum's
  own `mock: true`.
- With the decorator, a mocked call produces no `http.client.requests` observation and no call log; with a fake
  factory it would. Either is fine if the module page says which (the metric item is already planned).
- `HttpRequestValues.Processor` and `HttpServiceProxyFactory.Builder.httpRequestValuesProcessor` exist in
  spring-web 7.0.10 (`HttpRequestValues.java:238-249`, `HttpServiceProxyFactory.java:211`), confirming that part
  of the note.
- `Optional<T>` needs no special read: Spring unwraps and wraps it (`HttpServiceMethod.java:416, 454-455`), so the
  mock returns the inner value or null.
