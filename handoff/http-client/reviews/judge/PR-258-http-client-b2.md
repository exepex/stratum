# Judge verdict: PR #258, B2 errors of the blocking HTTP client, code review round 1

**Pull request:** #258, branch `claude/project-thread-ex55r3`, head `1f81239`, Feature track, code review (judge steps
1 to 5). Design note: `design/http-client-libraries/b2-errors-design-note.md`, passed in round 2
(`reviews/judge/B2-errors-note-r2.md`). Judge model: Fable 5.1, 2026-11-09.

**FAIL.** One should-fix finding and two minor ones. The gate passes and every other requirement is met.

I read AGENTS.md and all six topic files in `docs/agents/`.

## Gate
- Open, not a draft. Claims complete with the design note, broken-code proof, self-review, line counts.
- CI at `1f81239`: Verify and SBOM, CodeQL (actions, java-kotlin), wiki sync, SonarCloud all `success`.
- Codex: one P2 at `b7c82b3` (whole-URI path in the endpoint), fixed in `1f81239`, thread resolved; no findings at
  `1f81239`. I reproduced the fix: `ClientErrors.endpointOf` :104-109 drops the template at `?` and names the method
  alone without a template; plant P4 below. Sonar: quality gate passed, 0 new issues, 0 hotspots.

## What I ran
- `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ./mvnw -B -pl starters/stratum-starter-http-client -am verify` →
  `BUILD SUCCESS`, exit 0; the starter's reports: 92 unit tests (`TEST-*.xml` testcases) and 90 integration tests,
  0 failures. Spotless, Checkstyle, PMD, SpotBugs, JaCoCo passed in that run.
- Read the whole diff (35 files, +1709 −149), `ClientResilience`, `RetryRules`, `StratumClientGroups.decorate`,
  `DefaultStratumHttpClients`, core `ErrorReport` and `StratumException`, web `ErrorResponseFactory`, ADR 0011
  decision 6, feature list 8, the A1b branch's `HttpClientErrorCode` (`claude/project-thread-8ivbzo`).
- Two probes (below) and 18 planted faults, run with a JUnit launcher over the module's test classpath.

## Gap list (built from ADR 0011 decision 6, feature list 8.1 to 8.3 and 8.5, the owner's decisions)
| Requirement | Result | Evidence |
|---|---|---|
| Read timeout → `SX-HTTPC-0001`, 504, cause kept | PARTLY | `ClientErrors.noAnswer` :86-90; `TotalTimeoutIntegrationTest` (3 tests) and my `/text` probe give 0001; a JSON body cut off by the timeout does not (F1) |
| Connect failure, connect timeout, unknown host, full pool, drop, reset → `SX-HTTPC-0002`, 503 | MET | `ClientErrorsTest.NoAnswer` 6 causes; `RetryIntegrationTest.droppedConnection`, `refusedConnectionRetriedForPost`; plant P1 |
| Open breaker → `SX-HTTPC-0003`, 503, also for `ClientResilience.call` (so `resilience(name)`) | MET | `ClientResilience.rethrow` :185-188 is the one exit of `call` :144; `CircuitBreakerIntegrationTest` 3 tests; plant P7 |
| Statuses are web-starter defaults below the team's values; none with the module off | MET | `RestClientEnvironmentPostProcessor` :32-40; `ClientErrorStatusesTest` 3 tests through the real `ErrorResponseFactory`; plant P8 |
| Codes carry no detail: caller sees only the description (note F3) | MET | `new StratumException(code, "", failure)` :88, :187; `ExternalError` has code, severity, message, details, IDs and no fix (`ErrorReport.create`); plant P2 |
| Error answer → `DownstreamException` with status, headers, ≤64 KB body, client, endpoint template, trace ID, downstream code; message without body or values; no cause | MET | `DownstreamException` :54-72; `ClientErrorsIntegrationTest.ErrorAnswers` 6 tests; plants P3, P4, P12, P13, P14, P16 |
| Uncaught → 500 and never the vendor's body (owner 2026-11-09) | MET | `ClientErrorStatusesTest.uncaughtDownstreamIs500WithoutItsBody` |
| At most 64 KB of an error body, after gzip; connection reused; success bodies whole | MET | `ErrorBodyLimit` first in the chain (`ClientConnectionPools` :90); `BodyLimit` 3 tests; plants P9, P10 |
| Error mapper per client, first in order wins | MET | `ClientErrors.downstream` :72-76, `StratumClientCustomizer.mapError`; `ErrorMapperIntegrationTest`; plant P5 |
| Retry and breaker still see Spring's exceptions (translation outermost) | MET | `ERROR_TRANSLATION_ORDER = RESILIENCE_ORDER - 1`; `decorate` puts the lowest order outermost; plant P6 |
| Lane A's `StratumException` and any non-`RestClientException` pass unchanged | MET | `ErrorTranslatingExchangeAdapter.around` catches only `RestClientException` |
| 8.4 `Optional` 404 (B2b) and `SX-HTTPC-0004` (A1b) out of scope | Accepted | as the note and the A1b branch agree |
| Documents: errors page, module page, error codes, web page, migration guide, index | PARTLY | each updated and read against the code; one gap (F2) |
| Line counts ≤ 400 / 500; every file ends with a newline | MET | counted all 35 files; largest `web.md` 496, `http-client.md` 484, `ClientResilience.java` 318 |

## Findings
1. **Should fix** (ST-TEST-01, ST-ENG-06, ST-DOC-01) — `ClientErrors.noAnswer` :86 reads only the direct cause
   (`failure.getCause() instanceof IOException`). A JSON body cut off by the read timeout arrives as
   `RestClientException ← HttpMessageNotReadableException ← JacksonIOException ← SocketTimeoutException`, so the
   call ends with Spring's exception and the caller gets 500, not `SX-HTTPC-0001` 504, against the errors page's
   first row and the note's table. Reproduced through a real `@StratumClient` interface (`timeout=500ms`, a server
   that sends `{"a":1,` and stalls): `Map` return → `RestClientException ... SocketTimeoutException`; `String` return →
   `SX-HTTPC-0001`. `RetryRules.isBodyCutOff` :220-221 has the same one-level check, so the breaker and the retry of
   a `GET` miss the same case (`http-client.md` :234, :249 promise both). **Fix:** one shared predicate that finds an
   `IOException` anywhere in the cause chain (Spring's `NestedExceptionUtils.getMostSpecificCause`, or a walk), used
   by both `ClientErrors` and `RetryRules`; an integration test with a JSON body cut off by the timeout, beside the
   text case; the module's `verify` again.
2. **Minor** (ST-DOC-05, documentation "How documentation is checked") — `docs/modules/http-client-errors.md`
   :21-32 shows `stratum.web.error.statuses` in YAML and properties, and the page has no "Configure in Java" line.
   **Fix:** one sentence saying the same value is set in Java through `WebPropertiesCustomizer`
   (`properties.error().toBuilder().statuses(...)`), linking `web.md#configure-in-java`.
3. **Minor** (ST-ENG-13, ST-TEST-41) — `ErrorDownstream.java` :106 `catch (IOException e)`. **Fix:** name it, such as
   `startFailure`.

## Planted faults: 18 planted, 17 caught
| # | Fault | Caught by |
|---|---|---|
| P1 | `codeFor` swaps timeout and unavailable | `ClientErrorsTest` 9 tests |
| P2 | code detail = failure message | `noDetailForTheCaller` |
| P3 | endpoint keeps the query template | `errorAnswerIsDownstreamException`, `textHidesBodyAndValues` |
| P4 | endpoint falls back to the whole URI's path | `wholeUriNamesMethodOnly` and 7 more |
| P5 | customizers never asked | `firstMappingWins` |
| P6 | translation order inside the retries | `outermost`, `droppedConnection`, `refusedConnectionRetriedForPost` |
| P7 | open breaker rethrows `CallNotPermittedException` | `CircuitBreakerIntegrationTest` 3 tests |
| P8 | no default status for 0003 | `defaultStatuses` |
| P9 | `ErrorBodyLimit` added last (inside gzip) | `gzipErrorBodyCappedAfterInflate` |
| P10 | limit never wraps an error answer | `largeErrorBodyCapped`, `gzipErrorBodyCappedAfterInflate` |
| P11 | `read(byte[])` ignores the bytes left (`Math.min` removed) | **survived**: Spring reads in 16 KB chunks, 4 × 16 KB = 64 KB exactly (Note 2) |
| P12 | Spring's message (with body) kept | `textHidesBodyAndValues` |
| P13 | body converter dropped | `downstreamCodeRead`, `noDownstreamCodeOtherwise` |
| P14 | trace ID not read | `carriesTraceId` |
| P16 | error answer rethrown unchanged | 8 tests of `ClientErrorsIntegrationTest` |
| P17 | `isStream` always false (moved B1 code) | `TotalTimeoutIntegrationTest` streamed download |
| P18 | `@Bean` removed from `stratumClientErrors` | 8 tests of `ClientErrorsIntegrationTest` |
| P19 | non-`IOException` causes also translated | `invalidBodyUnchanged` |

## Rule checks (ST-ENG)
Lombok `@Getter` / `@RequiredArgsConstructor` where fields are only assigned (42); the one new static method is in
`@UtilityClass` `HttpClientConstants` (47); every lambda one expression (46); `var`, streams, `Optional` returns
(39, 40); `@StratumInternal` on every new type but `DownstreamException`, which is on the API list and exposes only
Spring and JDK types (56); no new package, cycle test passes (57); specific catches only (66); `package-info` on the
new test package (41); `ClientErrors` bean has `@Fallback` and `@ConditionalOnMissingBean` (21). Design-note
deviations in the Claims (void `mapError`, `final` exception, two methods, `AroundCallExchangeAdapter`) each remove
code or satisfy SpotBugs; the shared base removes the five copied `exchange*` methods (ST-ENG-06).

## Notes (not findings)
1. **Coordinator:** `DOWNSTREAM_UNAVAILABLE` carries fix text while 0001 and 0003 carry none; ST-ENG-27 says errors
   a caller gets have no fix text. The text is copied word for word from A1b's enum (#257 branch), never reaches the
   caller (`ExternalError` has no fix), and `ErrorCodeChecks` accepts it. One decision for both lanes, not B2's alone.
2. P11 is theoretical: `FileCopyUtils` reads through `InputStream.transferTo` in 16 KB chunks, so the `Math.min` guard
   is never exercised in a real setup; a cheap unit test of the stream would still pin it.
3. `ClientErrors.downstream` calls `customizers.orderedStream()` on each failed call (a cached by-type lookup); the
   failure path only, so not a ST-ENG-54 finding.
4. A team's own `defaultStatusHandler` that throws a plain `RestClientResponseException` is rewrapped as
   `DownstreamException`, whose `getResponseBodyAs` then throws `IllegalStateException("Function to convert body not
   set")` (seen in P13). The page steers teams to `mapError`; theoretical until a service does it.
5. The note's row "codes are never mapped" has no test; the `mapError(String, DownstreamException)` signature makes
   it impossible by type, so none is needed.

Not merged; nothing in the repository changed.
