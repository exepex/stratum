# Judge verdict: B2 errors design note, round 1

**Design note:** `design/http-client-libraries/b2-errors-design-note.md` (round 1), lane B, Feature track,
design-note review (judge steps 1, 2, 3 and 5). Checked against `main` at `da23adc` (B1, PR #256, merged).
Judge model: Opus 5.5, 2026-11-09.

**FAIL**: one blocking and two should-fix findings, plus four minor ones.

I read AGENTS.md and all six topic files in `docs/agents/`.

## What I ran
- Read the note; ADR 0008, 0011 (decisions 1 to 10), 0012; the B1 code on main (`ClientResilience`,
  `ResilientExchangeAdapter`, `StratumClientGroups`, `RetryRules`, `AttemptTimeLeft`,
  `RestClientEnvironmentPostProcessor`); `StratumDefaults`; `ErrorReport`, `StratumException`;
  `ErrorResponseFactory`; the A1 and C2b notes where they meet B2.
- Read sources of httpclient5 5.6.5 and spring-web 7.0.10 (Maven Central sources jars).
- **Probe** `CapProbe.java` (HC5 5.6.5, JDK HTTP server, 500 answer, `Content-Encoding: gzip`, 1,051 bytes on the
  wire, 1 MB decompressed; an `ExecChainHandler` that caps the entity at 65,536 bytes on status >= 400):
  `addExecInterceptorLast` -> caller read **1,048,576** bytes; `addExecInterceptorFirst` -> **65,536** bytes.

## Gap list
| Requirement (ADR 0011, owner) | Result | Evidence |
|---|---|---|
| SX-HTTPC-0001 504, 0002 503, 0003 503 | MET | table in "What each final failure becomes"; HC5 `ConnectTimeoutException extends SocketTimeoutException` (so the "not a ConnectTimeoutException" rule is needed and right) |
| Status only from `stratum.web.error.statuses`, no core change | MET | `ErrorResponseFactory.java:221` reads only that map; `StratumDefaults.addBelowServiceValues` used by `RestClientEnvironmentPostProcessor.java:48` |
| `DownstreamException` with status, client, endpoint, code, trace ID, body (ADR 6.2) | MET | `RestClientResponseException` public ctors, `getResponseBodyAs*` public, convert function private transient (spring-web 7.0.10, lines 59, 70-203) |
| Error body at most 64 KB (ADR 6.2, A10) | NOT MET | F1: chain position not set; wrong position does not cap gzip bodies (probe) |
| Error mapper on `StratumClientCustomizer` (ADR 1.4) | MET | default method; translation outermost, so outside retry, breaker and resend |
| Retry, breaker, lane A resend still see Spring's exceptions | MET | `StratumClientGroups.decorate` composes lowest order outermost; order `RESILIENCE_ORDER - 1` (0 - 1) is outermost |
| Owner: uncaught downstream error stays 500 | MET | table row 1 + `uncaughtDownstreamIs500WithoutItsBody` |
| Owner: token fetch fail = SX-HTTPC-0002 503 | MET | "Anything else ... unchanged": lane A's `StratumException` passes through |
| Owner: lane A adds SX-HTTPC-0004=500 | MET | "Later items"; see Note 3 |
| Breaker open gives SX-HTTPC-0003 for every client (ADR 4.5) | PARTLY | F2: `resilience(name)` has no owner |
| Caller never sees internal detail (note answer 3, ADR 6.2) | NOT MET | F3 |
| Scope (1): 404 as empty `Optional` split to B2b | Accepted | `HttpServiceMethod.java:416` `nestedIfOptional()`, `:454-455` `asOptionalIfNecessary`: the adapter gets the nested type only |
| Scope (2): `restClient(name)` keeps Spring's exceptions | Accepted | ADR 0011 9 ("Stratum never wraps ... a foreign client"); translating inside a `RestClient` needs a request interceptor, which buffers bodies (ADR 0012 context) |

## Findings
1. **Blocking (ST-SEC-12, ADR 6.2, note answer 3).** The note says the cap is "an Apache exec interceptor" but not
   where in the chain. HC5 turns gzip on by default (`HttpClientBuilder.java:989-997` adds `ContentCompressionExec`
   as `COMPRESS`); `addExecInterceptorLast` sits inside it (`:1065`, before `MAIN_TRANSPORT`) and caps the
   compressed bytes. Probe: a 1 KB gzip error body is read as 1 MB. The planned test (1 MB plain body) passes in
   both positions. **Fix:** state that `ErrorBodyLimit` sits outside `COMPRESS` (`addExecInterceptorFirst`, or
   `addExecInterceptorBefore(COMPRESS)`), and add a gzip error body that inflates past 64 KB to
   `ErrorBodyLimitIntegrationTest`.
2. **Should fix (ADR 4.5, 6.1).** "Lane C's C2b *may* call the same translation" leaves breaker-open on
   `resilience(name)` with no owner. B1's public `ClientResilience.call` throws `CallNotPermittedException`
   (its `@throws`), and the C2b note (line 72) keeps it, so during an outage every refused call answers our caller
   500 and the web starter logs it at ERROR with a stack trace, where ADR 4.5 says "the call stops with
   `SX-HTTPC-0003` (503)". **Fix:** say who turns it into `SX-HTTPC-0003` (B2's `ClientErrors`, or C2b by name,
   with the C2b note changed to match), and add the test to that item.
3. **Should fix (ErrorReport contract, note answer 3).** "The detail of each `StratumException` is the client and
   endpoint ... such as `pricing GET /prices/{id}`". `ErrorReport.of` puts the detail into the external message
   (`ErrorReport.java:87-92`, "never include ... an internal detail"), and `ErrorResponseFactory.bodyOf` sends that
   message as the body's `detail` to our caller. Every 503/504 would show our downstream's name and path. That
   contradicts "our caller sees only our own code". **Fix:** no detail (the code's description only); the client and
   endpoint reach the log through B3's call log line (ADR 8.1), or say how else they are logged.
4. **Minor (citation).** "ADR 0008 decision 3 (status only from `stratum.web.error.statuses`)": decision 3 is
   standard mode; the rule is decision 5 (lines 49-52) and `docs/modules/web.md` "Your own error handling". Fix the
   citation in answer 4 and "Rejected".
5. **Minor (design-note question 1).** Answer 1 covers new services only. Say what an existing or hybrid service
   sees when it moves a client to `@StratumClient`: `catch (HttpClientErrorException.NotFound e)` and other Spring
   subtypes stop matching (catch `DownstreamException`, check `getStatusCode()`), and 503/504 now carry Stratum's
   body even in compatible mode (ADR 0008 decision 2). Put the catch change on the module page.
6. **Minor (ST-ENG-33).** The 64 KB cap is a fixed limit; the note cites ST-ENG-33 without saying why it has no
   key. Add one line: ADR 0011 2.4 fixes the 19 keys and its Consequences name the 64 KB cap, so it stays a constant.
7. **Minor (Javadoc).** `StratumClientCustomizer`'s Javadoc says "It runs once per client at startup, never per
   call"; `mapError` runs per failed call. Name the change in the note.

## Planted faults
None: a design-note review has no code (judge.md step 4 not in scope). The probe above replaces it for F1.

## Notes (not findings)
1. Boot merges `Map` entries from several property sources, the higher source winning per key (Boot reference,
   "Merging Complex Types"); not probed here. The planned `teamStatusWins` test covers it.
2. On close, Spring's `EntityUtils.consume` and HC5's `ResponseEntityProxy.streamClosed` (`:133-147`) drain the rest
   of a capped body; an endless error body is bounded only by `timeout` between bytes. The note names it (#236/#237).
3. The A1 note line 150 says "lane B2 registers [SX-HTTPC-0004's] status"; the B2 note says A1b does, "agreed with
   lane A". The coordinator should make the A1 note match so the default is not added twice or missed.
4. The cause kept on 0001/0002 carries Spring's `ResourceAccessException` message with the real URL; that is
   logged today for any uncaught `ResourceAccessException` too, so nothing new.
5. `ClientResilience.getOrder` Javadoc says "the outermost of Stratum's decorators"; it changes with B2.

Not merged; nothing in the repository changed.
