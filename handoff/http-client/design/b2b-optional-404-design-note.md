# B2b: a 404 read as an empty `Optional`: design note

Round 2. Round 1 failed (`reviews/judge/B2b-optional-404-note.md`): F1 and F2 are fixed below, marked **[r2]**.

Lane B, the item after B2 (#258). Track: **Feature**, because it changes documented behaviour: today a `404` on any
method throws `DownstreamException`. It is feature 8.4 in `design/http-client-libraries/feature-list.md`
("`Optional` return = 404 becomes empty", status Agreed). ADR 0011 already names it at line 116 ("a 404 read as
empty" never counts for the breaker), and `docs/modules/http-client.md` lists it under Planned.

## What it does

A method of a client interface (a `@StratumClient` interface, or one named in a client's `interfaces`) whose return
type is `Optional` returns `Optional.empty()` when the other service answers `404`. **[r2, F1]** This holds for all
four shapes that Spring wraps in `Optional`: `Optional<T>` (a body), `Optional<ResponseEntity<T>>`,
`Optional<ResponseEntity<Void>>` and `Optional<HttpHeaders>`. Every other method, and every other status, behaves as
after B2.

**[r2, F2]** Only a client's interfaces do this. The client's `RestClient` and `RestTemplate` beans (C2b), and a
team's own `HttpServiceProxyFactory` built on the bean, keep Spring's exceptions: a 404 there is
`HttpClientErrorException.NotFound`, as ADR 0012 and the C2b note say.

```java
@StratumClient("inventory")
public interface InventoryClient {

  @GetExchange("/stock/{sku}")
  Optional<Stock> stock(@PathVariable String sku); // 404 -> Optional.empty()

  @GetExchange("/stock/{sku}")
  Optional<ResponseEntity<Stock>> stockWithTag(@PathVariable String sku); // 404 -> Optional.empty(), ETag on a hit

  @GetExchange("/stock/{sku}")
  Stock stockOrFail(@PathVariable String sku); // 404 -> DownstreamException, as today
}
```

```java
// The service's own code: no try/catch for "not found".
return inventory.stock(sku).orElseGet(() -> Stock.none(sku));
```

## 1. Who uses it, and does anything change for them?

- **Consumers:** a service with a client interface method that returns `Optional<T>`. A new service writes only the
  return type. An existing or hybrid service that moves a client to Stratum keeps its `Optional` methods, and the
  `try/catch (HttpClientErrorException.NotFound)` it had becomes dead code. Adoption is the same with Stratum as the
  parent or next to a company framework (ADR 0001): there is no key and no bean to add.
- **Changes a consumer sees:** a `404` on an `Optional` method returns empty instead of throwing `DownstreamException`.
  `mapError` is not asked for it, because it is not an error for that method. A `404` on any other method, and any
  other status on an `Optional` method, are unchanged. Tests: `OptionalNotFoundIntegrationTest.notFoundIsEmpty`, a
  `@FieldSource` row for each of the four shapes (**[r2, F1]**), `.otherStatusStillThrows`, `.plainMethodStillThrows`
  and `.mapErrorNotAsked`.
- **API:** no new public type and no new key. There is one new constant, the attribute's name, in the library's
  `HttpClientConstants`, beside the other internal constants.

## 2. How is it built, and why this way?

- **Reused from Spring 7.0.10** (checked with `javap` on `spring-web-7.0.10.jar`, 2026-11-09):
  - `HttpServiceProxyFactory.Builder.httpRequestValuesProcessor(HttpRequestValues.Processor)`. Its
    `process(Method, MethodParameter[], Object[], HttpRequestValues.Builder)` runs on each call and sees the method.
  - `HttpRequestValues.Builder.addAttribute(String, Object)` and `HttpRequestValues.getAttributes()`. These carry
    the mark from the processor to the exchange adapter.
  - `HttpServiceMethod$ExchangeResponseFunction.asOptionalIfNecessary` wraps the adapter's result with
    `Optional.ofNullable` when the return type is `Optional` (bytecode lines 112-118). So the adapter returning
    `null` gives the caller `Optional.empty()`.
- **The simplest way, chosen:** `StratumClientGroups.decorateCalls` also registers a processor that adds the
  attribute `stratum.httpclient.optional-answer` when `method.getReturnType() == Optional.class`.
  `ErrorTranslatingExchangeAdapter.around` then returns `null` instead of calling `errors.downstream(..)` when the
  answer is a `404` and the call carries the mark. That is one condition in the adapter B2 added, and one line
  where the decorators are registered. There is no new class.
- **[r2, F1] The three non-body shapes.** `AroundCallExchangeAdapter` wraps the result of `exchangeForHeaders`,
  `exchangeForBodilessEntity` and `exchangeForEntity` in `Objects.requireNonNull` (`:62, :87, :102`). Each `null`
  would then become a `NullPointerException`, as the judge's probe showed. Those three lines call one private method
  in the base instead, `answerOf(values, result)`. It returns `null` only when the call carries the mark, and Spring
  then wraps that `null` with `Optional.ofNullable` (`HttpServiceMethod.java:454-456`). Any other `null` still fails
  fast with `requireNonNull`, as today. The mark is set only on `Optional` methods, so a non-`Optional` method can
  never get a `null` through. That is the same one condition, read in one more place, with no new class.
- **Alternative for F1, not chosen:** mark only `Optional<T>` bodies, and document that the other three shapes still
  throw. That would be simpler by one method, but `Optional<ResponseEntity<T>>` is a real signature: a team that needs
  a found answer's `ETag` writes it, and it would get an exception where the same method with a body gets empty.
- **Alternative not chosen:** an AOP advice through `proxyFactoryCustomizer` that catches `DownstreamException` 404
  around the proxy. It adds a second proxy layer, and it runs after `mapError`. A team's mapper would then turn a
  normal "not found" into its own exception before the advice could return empty.
- **Modelled on:** B2's `ErrorTranslatingExchangeAdapter` and `StratumClientGroups.decorateCalls`, in the same
  package `com.exepex.stratum.restclient`. The constant goes in `libs/stratum-http-client`'s `HttpClientConstants`,
  which the starter already imports. There is no new import between packages, so no cycle is possible (ST-ENG-57).
- **Keys:** none. Spring Boot has no key for this, and a team that wants the exception keeps a non-`Optional` return
  type. That is the switch, per method, in Java.

## 3. Is it safe and fast?

| Parameter | Effect |
|---|---|
| Speed | Per call, one `Method.getReturnType()` (a field read) and one map put. Per 404, one map lookup. No reflection scan and no regex (ST-ENG-54). |
| Throughput | Unchanged. The 404 is not retried (`RetryRules.isRetryable`: 404 is not in `RETRIED_STATUSES`) and not counted by the breaker (`RetryRules.countsAsBreakerFailure`: 4xx other than 429 never count), so the result is the same as today's. |
| Log volume | None added. B3's call line will log the 404 with its status like any answer. |
| Memory and leaks | One attribute entry per call, gone with the call. The 404 body is still read through the 64 KB cap of B2 and dropped. |
| Data leaks | Less than today: the empty result carries no body, client or path. The attribute value is `Boolean.TRUE`. |
| Security | No new input is trusted. Only the status, which Spring parsed, decides. A hostile `404` with a huge body is still capped at 64 KB (test `ClientErrorsIntegrationTest.BodyLimit` covers the cap, and the 404 path goes through the same handler). |
| Maintainability | One condition in an existing class, plus one processor line. It rests on two public Spring 7 APIs. |
| Scalability | Per-call constant cost, no shared state. |

- **Thread hops:** none. The processor and the adapter run on the caller's thread, inside the proxy call.
- **Nullness:** after the change, `null` can come out of the three `HttpExchangeAdapter` methods that Spring
  declares non-null. This happens only for a marked call, and only Spring's `ExchangeResponseFunction` reads the
  result, which wraps it with `Optional.ofNullable` because the method returns `Optional`. No other caller of the
  decorated adapter exists (`StratumClientGroups.decorateCalls` is the only place it is built). The code PR records how
  the build's null checks (SpotBugs) treat that return.
- **Hostile inputs:** a 404 with a 1 MB body on an `Optional` method returns empty, with the connection reused
  (`OptionalNotFoundIntegrationTest.largeNotFoundBodyEmptyAndConnectionReused`).
- **Request attributes:** Spring's `RestClientAdapter` copies `HttpRequestValues` attributes to the request's
  attributes. Not verified: whether any Spring observation turns request attributes into tags. The value is a
  constant `Boolean.TRUE`, so even if one did, the tag would be low-cardinality and carry no data. The code PR checks
  it with a test that reads the call's observation, or names it as not verified in the Claims.

## 4. Which rules and owner decisions does it follow?

- **Rules:** ST-ENG-05 (the simplest: no new class; one private method in the base), ST-ENG-18 (it reuses B2's adapter), ST-ENG-54, ST-ENG-56
  (no new API type), ST-ENG-57, ST-DOC-05 (the docs give the Java example; there is no key, so there is no YAML),
  ST-TEST-01. Planted faults:
  - the mark is never set;
  - the status check is dropped;
  - `mapError` is asked first;
  - **[r2, F1]** `answerOf` keeps `requireNonNull` for marked calls, caught by the `Optional<ResponseEntity<T>>` row;
  - **[r2, F1]** `answerOf` lets `null` through for unmarked calls, caught by a unit test of the base with an
    unmarked call whose adapter returns `null`.
- **Owner decisions:**
  - Feature 8.4 "Agreed" (feature list). Protected by `notFoundIsEmpty`.
  - ADR 0011 line 116: a "404 read as empty" never counts for the breaker. Protected by
    `OptionalNotFoundIntegrationTest.notFoundNeverOpensBreaker`, which sends more 404s than the breaker's minimum.
  - Aashish, 2026-11-09: an uncaught downstream error stays 500. Unchanged for non-`Optional` methods
    (`plainMethodStillThrows`).
  - Not running the mapper for an `Optional` 404 is this note's choice, not an owner decision. The judge or the
    owner can reverse it. The alternative is to ask `mapError` first, and to return empty only when it does not
    throw.
- **Spring facts:** the `javap` facts in question 2, and B2's note line 156 (`nestedIfOptional`: the adapter gets
  the unwrapped type, which is why the mark is needed).
- **Documents:**
  - `docs/modules/http-client-errors.md`: a new section, "A 404 read as an empty Optional", with the example above,
    the four shapes and a row in the "What a failed call throws" table. **[r2, F2]** It has one line saying that only a
    client's interfaces read a 404 as empty, while its `RestClient` and `RestTemplate` beans keep Spring's
    `NotFound`.
  - **[r2, F2]** The C2b page for a client's own beans gets the same line, whichever of B2b and C2b lands second. I
    will tell lane C.
  - `docs/modules/http-client.md`: remove the item from Planned, and add one line in the Errors section.
  - `docs/guides/migrating-a-service.md`: a `catch (NotFound)` around an `Optional` method can go.
