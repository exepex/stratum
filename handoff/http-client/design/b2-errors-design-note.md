# Design note: B2, errors of the blocking HTTP client (Feature track), round 2, revised after the round-1 verdict

**Item:** "errors" of ADR 0011 (decision 6, and the error mapper of 1.4 and 2.4); feature list 8.1, 8.2, 8.3 and
8.5. Lane B, step 2 of 3 (owner, 2026-11-09 13:26Z). Built on a fresh branch from `main` at `da23adc` (B1, PR #256,
merged).

**In B2:**
- the codes `SX-HTTPC-0001` (timeout, 504), `SX-HTTPC-0002` (unavailable, 503) and `SX-HTTPC-0003` (breaker open,
  503), with their statuses given as Stratum defaults of the web starter's `stratum.web.error.statuses`;
- `DownstreamException` for every error answer (4xx, 5xx) of a `@StratumClient` interface;
- an error body read at most 64 KB, for every Stratum client (ADR 6.2, gap review A10);
- Java: one default method on `StratumClientCustomizer` that maps one client's `DownstreamException` to the team's
  own exception (the "error mapper" of ADR 1.4 and 2.4);
- the module page, the error codes page and the planned list.

**Later items, not B2:** `SX-HTTPC-0004` (lane A's A1b adds it, with its status default beside these three; agreed
with lane A); a 404 read as an empty `Optional` (feature 8.4: Spring 7.0.10 gives the exchange adapter no return
type, so it needs its own small change; proposed as B2b, after B2); the call log line and meters (B3);
`stratumHttpClients.restClient("<name>")` and `resilience("<name>")` keep Spring's exceptions (ADR 0012: the team's
own `RestClient` behaviour), and lane C's C2b may call the same translation; the reactive starter.

## 1. Who uses it, and does anything change for them?
Teams with a `@StratumClient` interface: a timeout answers 504, a dead downstream or full pool 503, an open breaker
503 (also via `resilience(name)`), an error answer a `DownstreamException` (500 unless caught). Moving a client in:
`catch (HttpClientErrorException.NotFound e)` becomes `catch (DownstreamException e)` plus `getStatusCode()`.

## 2. How is it built, and why this way?
One new outermost `ClientExchangeDecorator` (order before retry) turns the final failure into the code or a
`DownstreamException`, so retry and breaker rules still see Spring's own exceptions. The 64 KB cap is the first
Apache exec interceptor, outside gzip, on error answers, so Spring's error handler and exception types stay.

## 3. Is it safe and fast?
ST-AG-14 below. Hostile input: a huge or endless error body (memory is capped at 64 KB per call), and an error body
with personal data (never in a message, `toString` or log line). Nothing is added on a successful call but one
`try`; the translation runs once per failed call, after the retries.

## 4. Which rules and owner decisions does it follow?
ADR 0011 1.4, 2.4, 4.5, 6.1, 6.2; ADR 0008 decision 5 (status only from `stratum.web.error.statuses`), ADR 0012;
ST-ENG-05, -07, -24, -33, -34, -39, -46, -47, -56; ST-SEC-03, -04; ST-DOC-01, -04, -05; ST-TEST-01, -14, -15, -17.
Owner: "uncaught downstream error stays 500" (2026-11-09). Each decision has a test below; facts under "Facts".

## Details

### Modules and packages
- `libs/stratum-http-client` (`com.exepex.stratum.httpclient`): `HttpClientErrorCode` gets the three runtime codes
  (`DOWNSTREAM_TIMEOUT`, `DOWNSTREAM_UNAVAILABLE`, `BREAKER_OPEN`); its Javadoc stops saying every code stops
  startup. Code numbers: runtime `000x`, startup `10xx` as today.
- `stratum-starter-http-client` (`com.exepex.stratum.restclient`):
  - `DownstreamException` (API, extends Spring's `RestClientResponseException`, so `getStatusCode()`,
    `getResponseHeaders()`, `getResponseBodyAsString()` and `getResponseBodyAs(..)` work as on Spring's): adds
    `getClient()`, `getEndpoint()` (method and URI template, such as `GET /orders/{id}`), `getTraceId()` and
    `getDownstreamCode()` (`Optional<String>`: the `code` member of a `application/problem+json` body, read when
    asked, through the client's own converters; empty for any other body or one that cannot be read);
  - `ClientErrors` (`@StratumInternal`): one method, "this final failure of client X's call to endpoint Y becomes
    what", the rules in the next section; reusable by lane C's C2b;
  - `ErrorTranslationDecorator` (`@StratumInternal`, a `ClientExchangeDecorator` bean, order
    `RESILIENCE_ORDER - 1`, so outermost): wraps each `exchange*` method of the adapter with `ClientErrors`;
  - `ErrorBodyLimit` (`@StratumInternal`): the Apache `ExecChainHandler` that, on an answer of 400 or more, wraps
    its entity so that at most 64 KB is read; added in `ClientConnectionPools` to every Stratum client with
    `addExecInterceptorFirst`, so it sits outside Apache's `COMPRESS` step and caps the bytes after gzip is undone
    (round 1, F1);
  - `ClientResilience.rethrow` (B1): a refused call of an open breaker is thrown as `SX-HTTPC-0003` instead of
    Resilience4j's `CallNotPermittedException`. `ClientResilience.call` is the one path of the interfaces and of
    lane C's `resilience(name)`, so both get it (round 1, F2; the C2b note already gives this to B2, line 53);
    its Javadoc `@throws` and `getOrder` ("the outermost of Stratum's decorators") change with it;
  - `RestClientEnvironmentPostProcessor`: three more defaults below the service's values,
    `stratum.web.error.statuses[SX-HTTPC-0001]=504`, `[SX-HTTPC-0002]=503`, `[SX-HTTPC-0003]=503`.
- No new dependency, no new package, no change to `stratum-core` or the web starter.
- Rejected: a `getHttpStatus()` on core `ErrorCode`, read by the web starter (agreed earlier with lane A as a plan).
  ADR 0008 decision 5 says a status comes only from `stratum.web.error.statuses`; Stratum's defaults in that map keep
  the ADR, need no core change, and a team can change one status the way it maps its own codes. Lane A is told.
- Rejected: Stratum's own `defaultStatusHandler` on the builder. It would rebuild Spring's exception subtypes and
  their body conversion; the exec interceptor caps the body and leaves both to Spring.

### What each final failure becomes (`ClientErrors`)
| Failure after retries | Becomes | Caller of our service gets |
|---|---|---|
| An error answer (Spring's `RestClientResponseException`) | `DownstreamException`, then the team's mapper if any | 500, unless the team catches or maps it |
| No answer: `ResourceAccessException`, or a body cut off (`RestClientException` with an `IOException` cause, B1), caused by a read timeout (`SocketTimeoutException` that is not a `ConnectTimeoutException`) | `StratumException` `SX-HTTPC-0001`, cause kept | 504 |
| No answer for any other reason: connect failure, connect timeout, unknown host, full pool (`ConnectionRequestTimeoutException`), reset, dropped connection | `StratumException` `SX-HTTPC-0002`, cause kept | 503 |
| Breaker open (`CallNotPermittedException`), in `ClientResilience.rethrow`, so for `resilience(name)` too | `StratumException` `SX-HTTPC-0003`, cause kept | 503 |
| Anything else (a body that is not valid JSON, a team exception, lane A's `StratumException`) | unchanged | as today |

- Each `StratumException` has no detail, only its code's description, because `ErrorReport.of` puts a detail into
  the message our caller gets (`ErrorReport.java:87-92`; round 1, F3). Our own log still names the downstream: the
  kept cause (Spring's `ResourceAccessException`) carries the URL in the logged stack trace, as any uncaught one does
  today, and B3's call log line names client, url and template (ADR 8.1).
- `DownstreamException`'s message is `pricing answered 503 Service Unavailable to GET /prices/{id}`: no body, no
  query or path values. It has no cause, because Spring's exception message carries up to 200 characters of the
  body and a logged stack trace prints the cause; it keeps Spring's exception in a transient field only to convert
  the body. `toString` is the message (ST-SEC-03).
- The trace ID is read from the MDC (`LoggingContextConstants.TRACE_ID`) when the exception is made; empty without
  one.
- **Error mapper (ADR 1.4):** `StratumClientCustomizer.mapError(String client, DownstreamException failure)`,
  default returns `failure`. Customizers are asked in their order; the first that returns another exception wins.
  It is only for error answers: the three codes are Stratum's and stay as they are. The functional method
  `customize` stays the only abstract one. It runs per failed call, so the interface Javadoc's "runs once per client
  at startup, never per call" becomes true of the other methods only, and says so (round 1, F7).
- **Moving a client in (round 1, F5):** Spring's subtypes (`HttpClientErrorException.NotFound` and the rest) are no
  longer thrown by a `@StratumClient` interface: catch `DownstreamException` and check `getStatusCode()`; the
  module page says so with an example. A 503 or 504 with a code gets Stratum's error body even in compatible mode,
  as every `StratumException` does (ADR 0008 decision 2); an uncaught error answer stays 500 as today.

### The 64 KB cap
- On an answer with status 400 or more, `ErrorBodyLimit` replaces the entity with one whose stream ends after
  65,536 bytes; Spring's error handler reads it (`FileCopyUtils`), so at most 64 KB is held. Success answers and
  streamed downloads are untouched (ADR 6.2, A10).
- **Its place in Apache's chain:** first (`addExecInterceptorFirst`), outside `COMPRESS`, which Apache 5.6.5 adds by
  default for gzip (`HttpClientBuilder.java:989-997`). Placed last, it sits inside `COMPRESS` and caps the compressed
  bytes: the judge's probe read a 1 KB gzip error body as 1 MB that way, and 64 KB when placed first.
- **No key (ST-ENG-33, round 1, F6):** ADR 0011 2.4 fixes the client's 19 keys, and its gap review (A10) set 64 KB as
  a fixed cap; it is a constant in `HttpClientConstants`.
- On close, Spring drains the rest (`HttpComponentsClientHttpResponse.close` calls `EntityUtils.consume`) so the
  connection can be reused: the rest is read and dropped, never held. A body that never ends is bounded only by
  `timeout` between bytes; that is the open #236 and #237 area, named on the page, not fixed here.

### Safe-and-fast, by ST-AG-14
- Speed: one `try` per call; translation only on the failure path, once per call (not per attempt).
- Throughput: no change; the cap shortens the work on a huge error body.
- Log volume: nothing new is logged; the web starter logs a 5xx answer once, as today.
- Memory and leaks: error body at most 64 KB; Spring's exception held only by the thrown `DownstreamException`.
- Data leaks: body, path values and query values never in a message, `toString` or log; the body never reaches our
  caller (ADR 6.2).
- Security: our caller sees only our own code; the downstream's status and body stay inside the service.
- Maintainability: Spring's handler and exception types are kept; one table of rules in one class.
- Scalability: no shared state; the status defaults are read once at startup.

### Decisions and their tests
Integration tests use `ScriptedDownstream` (error answers, a large body, a slow answer) and `SilentPort`.

| Decision | Source | Test |
|---|---|---|
| A read timeout answers 504 `SX-HTTPC-0001`, after the retries | ADR 6.1 | `ClientErrorsIntegrationTest.readTimeoutIs504` |
| Connect failure, full pool, reset answer 503 `SX-HTTPC-0002` | ADR 6.1 | `ClientErrorsTest.unavailableCauses` (`@FieldSource`), `ClientErrorsIntegrationTest.connectFailureIs503` |
| Open breaker answers 503 `SX-HTTPC-0003` and sends nothing | ADR 4.5, 6.1 | `CircuitBreakerIntegrationTest.openBreakerIs503` |
| The same through `resilience(name)` | round 1, F2 | if C2b is on `main` first: `OwnClientsIntegrationTest.Resilience.openBreakerIs503`; else a `ClientResilience.call` case in `CircuitBreakerIntegrationTest`, and C2b's row asserts what `main` throws |
| A 503 or 504 with a code shows no downstream name or path to our caller | round 1, F3 | `ClientErrorsWebIntegrationTest.codeBodyHasNoDownstreamDetail` |
| An error answer is a `DownstreamException` with status, client, endpoint, trace ID, headers and body | ADR 6.2 | `ClientErrorsIntegrationTest.errorAnswerIsDownstreamException` |
| Uncaught, it answers our caller 500 and never shows the vendor's body | owner 11-09, ADR 6.2 | `ClientErrorsWebIntegrationTest.uncaughtDownstreamIs500WithoutItsBody` |
| A team maps a status to another code; Stratum's default yields | ADR 0008, ST-ENG-34 | `ClientErrorsWebIntegrationTest.teamStatusWins` |
| Its message and `toString` carry no body and no path or query values | ST-SEC-03 | `DownstreamExceptionTest.textHidesBodyAndValues` |
| `getDownstreamCode()` reads a problem+json `code`; empty for other bodies or bad JSON | feature 8.1 | `DownstreamExceptionTest.downstreamCode*` |
| `getResponseBodyAs` converts through the client's converters | ADR 6.2 | `ClientErrorsIntegrationTest.bodyConvertedAsTeamType` |
| A 1 MB error body: 64 KB kept, the connection reused | A10, question 3 | `ErrorBodyLimitIntegrationTest.largeErrorBodyCapped` |
| A gzip error body that inflates to 1 MB: 64 KB kept | round 1, F1 | `ErrorBodyLimitIntegrationTest.gzipErrorBodyCappedAfterInflate` |
| A large success body is not cut | A10 | `ErrorBodyLimitIntegrationTest.successBodyWhole` |
| Retry and breaker rules unchanged (still see Spring's exceptions) | B1 | existing `RetryIntegrationTest`, `CircuitBreakerIntegrationTest` pass unchanged |
| The error mapper changes one client's exception; others keep `DownstreamException`; first in order wins | ADR 1.4 | `ClientCustomizerIntegrationTest.ErrorMapper` (`@Nested`) |
| Codes are never mapped | this note | `ClientCustomizerIntegrationTest.ErrorMapper.codesNotMapped` |
| Three status defaults present, below the service's values, absent with the module off | ADR 0008 | `RestClientEnvironmentPostProcessorTest` (new cases) |

### Facts
- Spring 7.0.10: `RestClientResponseException` has public constructors taking status, headers, body and charset,
  and public, overridable `getResponseBodyAs(Class)` and `getResponseBodyAs(ParameterizedTypeReference)`; its body
  converter is a private transient field with a setter only (`javap`, 2026-11-09).
- Spring 7.0.10 `HttpServiceMethod` unwraps an `Optional` return before the adapter is called (`nestedIfOptional`,
  `asOptionalIfNecessary`), so the adapter cannot tell `Optional<Order>` from `Order` (`javap`, 2026-11-09). Reason
  8.4 is its own item.
- Spring 7.0.10 `HttpServiceProxyFactory.getProxy` builds a plain `ProxyFactory` with its interceptor (`javap`).
- `stratum.web.error.statuses` is the only source of a code's status (`ErrorResponseFactory.answerAndLog`, lines
  214 to 231; ADR 0008 lines 50 to 52). `StratumDefaults.addBelowServiceValues` is how the starter already sets a
  default for another module's key (`RestClientEnvironmentPostProcessor`).
- Not verified: that Boot merges a map key from Stratum's defaults with the service's own map entries under the same
  key (`RestClientEnvironmentPostProcessorTest` and `ClientErrorsWebIntegrationTest.teamStatusWins` will show it);
  that Apache 5.6.5's `ExecChainHandler` can replace the entity before Spring sees it (the cap test will show it).

### Documents
- `docs/modules/http-client.md`: an "Errors" section (the table above, `DownstreamException`, the mapper with an
  example, the catch change for a moved client, the 64 KB cap and its limit), line 251 (the breaker now gives `SX-HTTPC-0003`), the planned list (8.4
  stays, as B2b);
- `docs/guides/error-codes.md`: a runtime part for the starter with the three codes, status and fix;
- `docs/modules/web.md` near line 114: Stratum's starters may give their own codes a default status, which the
  service can change.

## Round 1 findings (Opus, `reviews/judge/B2-errors-note.md`)
- **F1, accepted:** `ErrorBodyLimit` is added first, outside `COMPRESS`; new gzip test row; the probe is cited.
- **F2, accepted:** B2 owns it: `ClientResilience.rethrow` throws `SX-HTTPC-0003`, so the interfaces and
  `resilience(name)` share it; matches the C2b note line 53; lane C is told; new test row.
- **F3, accepted:** no detail on the codes; the downstream is named in our log by the cause and B3's call log line.
- **F4, accepted:** ADR 0008 decision 5 cited.
- **F5, accepted:** answer 1 and "Moving a client in"; the catch change goes on the module page.
- **F6, accepted:** "No key" line under the cap.
- **F7, accepted:** the `StratumClientCustomizer` Javadoc change is named under "Error mapper".
- **Notes:** 3 (A1 note line 150) goes to the coordinator; 5 (`getOrder` Javadoc) is in the `rethrow` bullet.
