# Judge: B2b design note, a 404 read as an empty Optional

Note `design/http-client-libraries/b2b-optional-404-design-note.md`, on top of PR #258 (branch
`claude/project-thread-ex55r3`, head 1f81239). Track Feature, review step: design note (steps 1, 2, 3 and 5).
Spring 7.0.10 (`spring-web-7.0.10-sources.jar` from Maven Central), JDK 25.

**FAIL** (1 blocking, 1 minor)

## Gap list

1. Gate, note complete with all four questions: MET.
2. Feature 8.4, an `Optional<T>` method returns empty on a 404: MET for `Optional<T>` bodies. Probe (below):
   `Optional<String>` on a 404 returned `Optional.empty`. PARTLY overall, see F1.
3. ADR 0011 line 116, a 404 read as empty never counts for the breaker: MET by design. `RetryRules.countsAsBreakerFailure`
   (`RetryRules.java:150-158`) counts only 5xx and 429; `ErrorTranslatingExchangeAdapter` is outermost, so the breaker
   sees Spring's 404 exception. Test `notFoundNeverOpensBreaker` planned.
4. Owner 2026-11-09, an uncaught downstream error stays 500: MET. Probe: a plain `String` method on a 404 and an
   `Optional<String>` method on a 500 both still threw `DownstreamException`.
5. `mapError` not asked for an `Optional` 404: MET, and I agree with the choice. Probe: four `Optional` 404 calls made
   0 `mapError` calls; the 404 and 500 on the other two methods made 2. Asking the mapper first would make the
   `Optional` useless for any team whose mapper maps every 404 of a client, as `ErrorMapperIntegrationTest.Mappers`
   does. The note names it as its own choice that the owner can reverse.
6. Spring facts: MET, read in the sources.
   - `HttpServiceProxyFactory.Builder.httpRequestValuesProcessor` adds to a list (`:211-212`), combined by
     `CompositeHttpRequestValuesProcessor` (`:322-331`). So a team's own processor does not replace Stratum's.
   - `HttpServiceMethod.invoke` runs the processor with the `Method` before each call (`:135`).
   - `HttpRequestValues.Builder.addAttribute` (`:466`) and `getAttributes` (`:175`) exist.
   - `ExchangeResponseFunction` applies `nestedIfOptional` (`:416`) and `asOptionalIfNecessary` = `Optional.ofNullable`
     (`:454-456`).
   - `RestClientAdapter` copies the attributes to the request (`:105`).
7. Simplest way, no new class, no cycle (ST-ENG-05, ST-ENG-57): MET. Writer and reader are in `com.exepex.stratum.restclient`.
   `HttpClientConstants` is `@StratumInternal` (`HttpClientConstants.java:10`), so the constant adds no API (ST-ENG-56).
8. Fit with C2b's `RestClient` and `RestTemplate` beans: MET in code, PARTLY in docs, see F2. C2b keeps the interfaces
   on `configure(..)`, without `ResilientRequestFactory` (c2b note, "One set-up for both"), and the beans never pass
   through `ErrorTranslatingExchangeAdapter`. So the two items do not touch each other's code, and B2b changes nothing
   for the beans.
9. Documents planned (errors page, module page Planned :467, migration guide): MET.

## Findings

**F1 (blocking): Spring's other `Optional` return types get a `NullPointerException` instead of empty.**
Spring wraps three more answer types in `Optional`:
- `Optional<ResponseEntity<T>>`, through `exchangeForEntity` (`HttpServiceMethod.java:440`);
- `Optional<ResponseEntity<Void>>`, through `exchangeForBodilessEntity` (`:433`);
- `Optional<HttpHeaders>`, through `exchangeForHeaders` (`:427`).

The note's processor marks all of them, since `getReturnType() == Optional.class`, and `around` returns `null`. But
`AroundCallExchangeAdapter` wraps those three calls in `Objects.requireNonNull` (`AroundCallExchangeAdapter.java:60,84,99`).

I built the note's design on the PR #258 head: a processor in `decorateCalls` and the 404 check in `around`. Then I ran
a probe IT against `ErrorDownstream.PROBLEM` (404):
- `Optional<String>` returned `Optional.empty`.
- `Optional<ResponseEntity<String>>`, `Optional<HttpHeaders>` and `Optional<ResponseEntity<Void>>` each threw
  `java.lang.NullPointerException: null`.

Today (B2) these methods throw `DownstreamException`. With the note, they would throw an NPE with no code, which is
worse. `Optional<ResponseEntity<T>>` is a realistic signature for a team that needs a found answer's headers, such as
`ETag`.

Fix expected: return empty for all four shapes. One way is to let the `null` through for these three calls in
`AroundCallExchangeAdapter`, only when the call carries the mark. The other way is to state in the note and the docs
that only `Optional<T>` bodies are read as empty, and mark only those. In either case, add a test row for
`Optional<ResponseEntity<T>>`, and name the planted fault for it.

**F2 (minor): the docs plan does not say which calls read a 404 as empty.**
C2b's beans keep Spring's exceptions (ADR 0012, c2b note "Errors"). So these calls get Spring's
`HttpClientErrorException.NotFound` on a 404, not an empty `Optional`:
- `@Qualifier("inventory") RestClient` with `.retrieve().body(..)`;
- the `RestTemplate` bean;
- a team's own `HttpServiceProxyFactory` built on the bean.

The note's question 1 talks only about `@StratumClient` interfaces. The planned section "A 404 read as an empty
Optional" should say, in one line, that this applies to a client's interfaces only. The C2b own-clients page should say
the same, whichever item lands second.

## Planted faults

None. This is a design-note review with no code. I ran one probe, which F1 cites. I restored the working tree
afterwards (`git status` is clean).

## Notes

- Section 3's "not verified" item is settled. Spring 7.0.10's client observation package
  (`org/springframework/http/client/observation`) never reads request attributes (`grep`: 0 hits), so the mark does
  not become a tag. Stratum's own main code reads no request attributes in an observation convention.
- The attribute is written and read only in `com.exepex.stratum.restclient`. A package-private constant there would
  be simpler than one in the shared `HttpClientConstants`. Either is acceptable.
- `Optional<Void>` on a 404 returns `null`, not `Optional.empty()`. That is Spring's own rule for void types
  (`HttpServiceMethod.java:420-424`), and Spring does the same on a 2xx. It is theoretical, so it is not a finding.
- A wrong base path behind a gateway that answers 404 now reads as "not found" on `Optional` methods. Feature 8.4
  accepts this. The docs could say it in one sentence.
- Model: this judge ran on Opus 5.5.
