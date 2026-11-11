# Judge verdict: PR #256 (B1, retries, total-timeout and circuit breaker of the HTTP client starter), branch `claude/project-thread-ex55r3`, head `fe6a360`, Feature track, code review, round 1

**PASS.** No finding of any severity. Every Claim (R1 to R9, F1 to F13) checked against the code and the tests at `fe6a360`; every finding of the two note verdicts is fixed in the code and the docs.

Inputs: the design note (`design/http-client-libraries/b1-resilience-design-note.md`, round-2 revision), verdicts `reviews/judge/B1-resilience-note.md` and `-r2.md`, ADR 0011 section 4, the full diff `e410a17..fe6a360` (44 files), the PR Claims, the 8 review threads, CI checks, and the Spring Web 7.0.10 and Resilience4j 2.4.0 jars (`javap`). Fresh judge, no part in building the change, no access to the implementer's conversation. I read AGENTS.md and all six topic files. Model: Claude Fable 5.1 (the note judges were Fable then Opus; the code judge is the next in rotation after Opus).

Build at `fe6a360`, JDK 25: `./mvnw -B -pl starters/stratum-starter-http-client -am verify` → `BUILD SUCCESS`; starter `TEST-*.xml`: 77 unit and 71 integration test cases, 0 failures (the `.txt` reports under-count `@Nested` classes).

## Gate

Open, not draft; `Verify and SBOM`, CodeQL and the Sonar quality gate green at `fe6a360`; the code-review bot's review of `fe6a360` has no findings; all 8 review threads answered with a fix and a test and resolved; Claims complete with the note and both verdicts posted on the PR. MET.

## Gap list

| Requirement | Result | Evidence |
|---|---|---|
| Four keys, defaults 3 / 200ms / 8s / true, `retries` 0 to 9, defaults + per client + Java, both formats, IDE metadata (ADR 2.4) | MET | `ClientSettings.java:62-65, 82-85`, `HttpClientProperties.isRetriesInRange` :119-125, `HttpClientConstants.java:79-91`; `HttpClientPropertiesTest` (defaults, YAML, Java wins, 10 and -1 rejected, 0s rejected), `ConfigurationMetadataTest`; faults J6, J12 |
| Retried: GET/HEAD/OPTIONS/PUT/DELETE on 502/503/504/429, drop, read timeout, cut-off body; any method after a connect failure; never other status, full pool, streamed body, open breaker (ADR 4.2, 4.3, owner "only if nothing sent") | MET | `RetryRules.java:38-48, 91-105, 205-230`; `RetryRulesTest` 20 cases, `RetryIntegrationTest` (`methods`, `droppedConnection`, `refusedConnectionRetriedForPost`, `streamedUploadSentOnce`), `TotalTimeoutIntegrationTest.postNotResentAfterTimeout`; faults J1, J9 |
| Back-off doubles from `retry-delay`, ±50%, at most 2s | MET | `RetryRules.backOff` :133-140; `RetryRulesTest.Waits` |
| `Retry-After` seconds or date on 503/429 replaces the wait; hostile values ignored; ≤32 chars, ≤9 digits | MET | `RetryRules.java:170-198`; `RetryRulesTest.ParseRetryAfter` (7 ignored values), `RetryIntegrationTest.retryAfterHonoured` (≥2s for two waits of 1s); fault J7 |
| No attempt when the wait leaves <100ms; back-off and `Retry-After` and a team's wait all checked the same way (note r1 F1, r2 F1) | MET | `RetryRules.shouldRetry` :73-80; the wait is computed once per attempt in `ClientResilience.attempt` :166-174 through `waits` and slept from the attempt (`carriedWaitMillis` :262-269), so the interval is never negative; `retryAfterTooLongReturnsTheAnswer` (sent once, 503 returned, <1.5s), `customWaitCheckedAgainstTotalTimeout`; fault J2 |
| Each attempt's pool wait, connect timeout and `timeout` cut to the time left; the call ends by `total-timeout` (ADR 4.4) | MET | `ClientConnectionPools.contextWithin` :148-161, `connectionConfig` :190-199 via `setConnectionConfigResolver` (no deprecated API, no `@SuppressWarnings`); `retryEndsByTotalTimeout` (two 1s attempts end within 1.5s ±0.4s), `answerAfterTotalTimeoutFails`, `ConnectTimeLeftIntegrationTest`; fault J13 |
| A streamed download (`InputStream`) keeps its full `timeout`; only new attempts stop at the deadline | MET | `ResilientExchangeAdapter.isStream` :122-124, `AttemptTimeLeft.keepsAnswerTimeout`, `contextWithin` :154; `streamedDownloadKeepsItsTimeout`; faults J4, J8 |
| One breaker per client; counts no answer (full pool included), cut-off body, 5xx, 429; never other 4xx; open = refused without sending and no retry; state change logged once (ADR 4.5) | MET | `RetryRules.countsAsBreakerFailure` :150-159, `ClientResilience.newBreaker` :230-242, `logStateChange` :272-285; `CircuitBreakerIntegrationTest` 7 tests; faults J5, J14 |
| `retries + 1` attempts; `retries: 0` sends once | MET | `ClientResilience.java:207`; `retriedStatuses` (3 requests), `ApacheRetryIntegrationTest` with `NO_RETRIES`; fault J11 |
| Decorators: `ClientExchangeDecorator` beans composed by order, resilience outermost (order 0), inner ones run on every attempt; no request interceptor (no buffering) | MET | `StratumClientGroups.decorate` :96-102 (reversed order fold), `HttpClientConstants.RESILIENCE_ORDER`; `innerDecoratorRunsOnEveryAttempt`, `refusedConnectionRetriedForPost` (team interceptor counted 3), `RestClientAutoConfigurationTest.noRequestInterceptor`; fault J3 |
| Only Stratum groups (`isStratumClient`) get resilience; `Groups.filter` resets after each `forEach` | MET | `StratumClientGroups.configureGroups` :48-54, two filters; Spring 7.0.10 `DefaultGroups.filterAndReset` resets the filter on every `forEach*` (`javap`); `ForeignClientIntegrationTest.serviceGroupKeepsBootSettings`; fault J10 |
| `restClient(name)` sends once; `resilience(name).call` in C2 | MET, out of scope as agreed | `ClientResilience.call` :115-146 is public and used by `ResilientExchangeAdapter` only; no retry on `StratumHttpClients.restClient` |
| SX-HTTPC-0002 (a `StratumException`, not a `RestClientException`) not retried and not a breaker failure | MET by construction, probe | `attempt` :163 catches only `RestClientException | CallNotPermittedException`; `retryOnException(failure -> false)` :209; `recordException` :235. Probe: a supplier throwing `IllegalStateException` → 1 call, rethrown, breaker 0 failed / 1 success. Lane A's client-credentials code is not on `main` yet, so no test in this PR |
| Java: `customizeRetry`, `customizeCircuitBreaker` default no-ops after Stratum's rules (ADR 1.4) | MET | `StratumClientCustomizer.java:59-73`; `ClientCustomizerIntegrationTest.RetryChanges`, breaker tests' window customizer. Probe: a team's `intervalFunction(5ms)` builds (Resilience4j setters null the other interval field, `javap`) and gives 4 attempts in <500ms |
| Docs: module page, reference, installation, quick-start, README; no "planned retries" left (note r1 F2, r2 F2, M1) | MET | `docs/modules/http-client.md:224-285, 435-456`; `reference.md:175, 200`; `installation.md:100`; `quick-start.md:318`; `README.md:269`; `git grep -n "retries.*planned\|planned.*retries" docs README.md` → 0 |
| Allow list of methods, HEAD and OPTIONS on purpose (note r1 M2, r2 M1); multipart part streamed (note r2 Note) | MET | `RetryRules.IDEMPOTENT` :38-40; `StreamedBody.java:28-33`, `StreamedBodyTest.multipartWithStreamedPart` |
| Note Notes carried: result on `ClientAttempt`; page names full pool, the 503 of B2, the 100-call minimum with the Java way; `ClientResilience.call` used in B1 | MET | `ClientAttempt.java:25`; page :248-252, 254-279; `ResilientExchangeAdapter.run` :107-113 |
| ST-AG-10 line counts | MET | All 44 files recounted with `wc -l`; every count matches the Claims; `ScriptedDownstream` 400 (at the limit), all others under |
| ST-SEC-01 to -06 | MET | No secrets; `ClientAttempt` excludes result and failure from `toString` (`ClientAttemptTest`); the WARN logs only the client name and states, with line breaks removed; `Retry-After` bounded by length, digits and the time left |

ST-ENG code rules: Lombok `@RequiredArgsConstructor`, `@Builder`, `@Value`, `@UtilityClass`, `@Slf4j` (ST-ENG-42); `var`, records, `Optional` only as a return (ST-ENG-39, Codex thread fixed); `@StratumInternal` on every new type but `ClientAttempt`, its builder and the customizer (ST-ENG-56, `RestClientArchTest`); constructor injection; no block lambdas; Javadoc on every type, method and key (ST-ENG-14); no new package, no cycle. Tests: `@Nested` groups, `@FieldSource` constants documented (ST-TEST-15, -17), shared `ScriptedDownstream`, `SilentPort`, `CallChecks`, `RestClientTestData.NO_RETRIES`.

## Planted faults

14 planted, 14 caught, 0 survived. Each planted alone, run offline with `-Dtest`/`-Dit.test`, restored with `git checkout`.

| Fault | Caught by |
|---|---|
| J1 POST retried on 503 (method check dropped) | `RetryRulesTest.writesNotRetried`, `RetryIntegrationTest.methods` |
| J2 time-left check ignored in `shouldRetry` | `RetryRulesTest.tooLittleTimeLeft` |
| J3 decorator order not reversed | `RetryIntegrationTest.innerDecoratorRunsOnEveryAttempt` |
| J4 streamed download's answer timeout cut | `TotalTimeoutIntegrationTest.streamedDownloadKeepsItsTimeout` |
| J5 breaker counts every exception | `CircuitBreakerIntegrationTest.clientErrorsNotCounted` |
| J6 `total-timeout` left out of the positive-duration check | `HttpClientPropertiesTest.zeroTotalTimeout` |
| J7 carried wait never slept | `RetryIntegrationTest.retryAfterHonoured` |
| J8 `InputStream` not seen as a streamed download | `TotalTimeoutIntegrationTest.streamedDownloadKeepsItsTimeout` |
| J9 full pool retried | `RetryRulesTest.fullPoolNotRetried` |
| J10 one `Groups.filter` for both callbacks | `ForeignClientIntegrationTest.serviceGroupKeepsBootSettings` |
| J11 `retries + 2` attempts | 6 `RetryIntegrationTest` tests |
| J12 `retries: 10` accepted | `HttpClientPropertiesTest.tooManyRetries` |
| J13 connect timeout not cut to the time left | `ConnectTimeLeftIntegrationTest.connectEndsByTotalTimeout` |
| J14 breaker refusal rethrown as `IllegalStateException` | 3 `CircuitBreakerIntegrationTest` tests |

## Notes (not findings)

- `ClientAttempt.getFailure` Javadoc names the two Spring exceptions as examples; a failure can also be `CallNotPermittedException` or a plain `RestClientException` (cut-off body), as `ClientResilience.call`'s Javadoc says. A team's `retryOnResult` rule should match on type, not cast.
- A non-Spring exception from inside an attempt (lane A's future SX-HTTPC-0002) is recorded by the breaker as a success call, Resilience4j's semantics for an exception the predicate does not record. It cannot open the breaker; it does count in the 100-call window. Lane A may want one test for it when the code lands.
- The note listed "the `HttpClientPropertiesCustomizer` example" among B1's documents; the example on the page still shows `timeout` and `maxConnections` only. The sentence above it says every key works there, and `HttpClientPropertiesTest` proves `totalTimeout` does. No change asked.
- Full `./mvnw verify` not run here (no Docker for the data-jpa and cache Testcontainers tests); CI ran it green at `fe6a360`.
