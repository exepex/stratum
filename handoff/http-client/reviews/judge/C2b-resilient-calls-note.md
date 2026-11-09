# Judge verdict: C2b design note, `stratumHttpClients.resilience(name)`, round 1

Note: `design/http-client-libraries/c2-own-clients/c2b-design-note.md` (round 1). Base checked: `main` at `da23adc`
(B1, PR #256). Track: Feature, design-note review (steps 1, 2, 3 and 5). Judge model: Opus 5.5.
I read AGENTS.md and all six topic files (engineering, testing, security, documentation, review, judge), and
`handoff/stratum-judge-process.md`.

**FAIL** (1 blocking, 1 minor)

## Gap list

| Requirement | Result | Evidence |
|---|---|---|
| `resilience(name)` on `StratumHttpClients`, public `ResilientCalls` (ADR 0011 9.3, ADR 0012 decision 3) | MET | Note §1; `StratumHttpClients.java` has only `restClient` and `settings` today |
| Retry and breaker for a call through `restClient(name)` | MET | `ClientResilience.call` takes any `Supplier`; guards are made from `properties.clientSettings(client)` only (`ClientResilience.newGuards`), so a client with no interface works too. This answers the note's "Not verified" item from the code; the first test still confirms it |
| Each attempt's pool wait, connect and answer timeouts end by `total-timeout` for `restClient(name)` | MET | `StratumClientBuilders.configure` sets `pools.requestFactory(...)`; `ClientConnectionPools.java:101` sets the context factory that reads `AttemptTimeLeft` |
| One breaker per name, shared with the client's interfaces | MET | `guardsByClient` keyed by client name; `ResilientExchangeAdapter` calls the same `ClientResilience` |
| POST retried only when never sent; streamed body never sent twice (ST-ENG-51) | MET | `RetryRules.isRetryable`: `streamedBody` → false; status retry only for `IDEMPOTENT`; connect failure only for no-answer |
| Uploads keep streaming (ADR 0012 decision 3) | MET | No interceptor; the lambda is re-run per attempt |
| `restClient(name)` still sends once (owner 13:38Z) | MET | Note §1, #252 tests unchanged |
| A client with its own engine uses `.resilience("<name>")` (ADR 0011 9.3; note §1 names "a vendor SDK on its own engine") | NOT MET | F1: probe below |
| No new token after a 401 without the wrapper; with it, the 401 resend (ADR 0012 decision 3) | PARTLY | Deferred to lane A, which matches A1 note F5 (`ServiceTokenResend.call`, test `ResendOn401.plainCallThroughResilience`); ADR 0012 not cited (F2) |
| Docs: "Clients Stratum did not build" and Planned list | MET (plan) | `docs/modules/http-client.md:441` ("planned `resilience`") and `:477` are the lines to change |
| One test and one planted fault for each decision | MET | Note's table, seven rows |
| Note complete: Q2 package and cycle, Q3 the eight ST-AG-14 parameters | PARTLY | F2 |

## Findings

**F1, blocking (ADR 0011 9.3; ST-AG-01).** Note §1 lists "a vendor SDK on its own engine" as a user, and ADR 0011 9.3
says such a client uses `.resilience("<name>")`. The design gives it nothing. `ClientResilience.attempt` catches only
`RestClientException | CallNotPermittedException`, retry is `retryOnException(failure -> false)`, and
`RetryRules.countsAsBreakerFailure` returns false for any other type, so Resilience4j records the failure as a
**success**. `AttemptTimeLeft` is read only by Stratum's Apache pool, so `total-timeout` does not reach a foreign engine
either. Probe (temporary IT on `ClientsApplication`, breaker window 2, deleted after): a lambda that throws
`UncheckedIOException(ConnectException)` three times ran **1 attempt each**, breaker **CLOSED, failed=0, ok=2**; the
same lambda throwing Spring's `ResourceAccessException(ConnectException)` ran 2 attempts and opened the breaker. The
note's 1004 row (`requireUrl`) would also refuse an own-engine client configured without a `url`.
**Fix:** state in the note who `resilience(name)` serves. The simplest fit for the code is: calls that fail with
Spring's `RestClient` exceptions, so `restClient(name)` and generated clients on it. Remove the vendor SDK from §1,
the docs and the tests, and record the own-engine part of ADR 0011 9.3 as Planned in a GitHub issue. That narrows an
accepted ADR, so the coordinator puts it to the owner in one line (or the owner picks another option, such as an
own-engine exception mapper, which I do not recommend now).

**F2, minor (judge.md design note Q2, Q3, Q4).** (a) Q4 cites ADR 0011 9.3 but not ADR 0012 decision 3, which is the
accepted text that says `resilience(name)` gives retry, breaker **and** the new token after a 401. Cite it, and say in
§2 which lands second wires `ServiceTokenResend.call` inside `ClientResilience.call` (within the breaker, per attempt),
as A1 note F5 already commits to. (b) Q2 does not name the package of `ResilientCalls` (presumably
`com.exepex.stratum.restclient`, next to `StratumHttpClients`) or say why no cycle results. (c) Q3 does not cover speed,
throughput, maintainability and scalability of the eight ST-AG-14 parameters; one line each is enough. (d) Q3 says
"a small object per call, or one cached per name (cached, like `restClient`)": pick one.

## Planted faults

None: this is a design-note review. The note's fault table has one fault per decision; the code judge plants its own.

## Notes

- A team lambda that turns error answers into values (a status handler that does not throw, or `exchange` returning a
  `ResponseEntity` for a 503) is not retried, since the rules read only failures. Worth one sentence in the docs, not a
  finding.
- Adding `resilience` to `StratumHttpClients` changes a public interface that a service may replace with its own bean
  (`@ConditionalOnMissingBean`); no service runs on Stratum yet (ST-AG-04), so no finding.
- `resilience(name).call` will throw Resilience4j's `CallNotPermittedException` and raw `RestClientException`s until
  B2's error mapping exists; B2's note should say whether its mapping also wraps this path.
