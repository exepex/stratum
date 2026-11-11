# Judge: B2b design note, a 404 read as an empty Optional, round 2

Note `design/http-client-libraries/b2b-optional-404-design-note.md` (round 2, changes marked [r2]), on top of PR #258
(branch `claude/project-thread-ex55r3`, head 1f81239, the same head round 1 used). Track Feature, review step: design
note (steps 1, 2, 3 and 5). Round 1 verdict: `reviews/judge/B2b-optional-404-note.md` (FAIL, F1 and F2).
Spring Framework 7.0.10 through Spring Boot 4.1.2, JDK 25 (`openjdk-25-jdk-headless`), Maven 3.10.0 as the wrapper pins.

**PASS**

## Gap list

1. Gate, note complete with all four questions and both round 1 findings addressed with [r2] marks: MET.
2. F1 fixed, all four `Optional` shapes return empty on a 404: MET. I built the r2 design on the PR #258 head
   (constant `OPTIONAL_ANSWER` in `HttpClientConstants`; `factory.httpRequestValuesProcessor` in
   `StratumClientGroups.decorateCalls` marking `method.getReturnType() == Optional.class`; `around` returning `null`
   on a marked 404; `answerOf(values, result)` in place of the three `requireNonNull`), then ran a probe IT against
   `ErrorDownstream.PROBLEM` (404 problem detail) with `Optional<String>`, `Optional<ResponseEntity<String>>`,
   `Optional<ResponseEntity<Void>>` and `Optional<HttpHeaders>`: all four returned `Optional.empty`. A 404 whose body is
   not JSON (`BROKEN_PROBLEM`) also returned `Optional.empty`.
3. Any other `null` still fails fast: MET. A unit probe with an `AroundCallExchangeAdapter` whose `around` returns `null`:
   on an unmarked call, `exchangeForHeaders`, `exchangeForBodilessEntity` and `exchangeForEntity` each threw
   `NullPointerException`, and `exchangeForBody` returned `null` as before; on a marked call all three let `null` through.
   The mark is set only by the processor on `Optional` methods, so no non-`Optional` method can be marked.
4. Everything else behaves as after B2: MET. Probe: `Optional<String>` and `Optional<ResponseEntity<String>>` on a 500,
   and `String`, `ResponseEntity<String>` and `HttpHeaders` on the 404, each threw `DownstreamException` with the right
   status (owner 2026-11-09: an uncaught downstream error stays 500). `Optional` methods on a 200 returned the body
   (1 MB), the entity (`200 OK`) and the headers (`text/plain`).
5. `mapError` not asked for an `Optional` 404: MET. A counting `StratumClientCustomizer` saw 0 calls after the five
   `Optional` 404s and 5 after the five failures above.
6. ADR 0011 line 116, a 404 read as empty never counts for the breaker, and is not retried: MET. 40 more `Optional`
   404s in a row, with the default retries, then `body()` still returned `Optional.empty` with no breaker exception; the
   whole probe of about 54 calls took 2.5s, so no retry back-off ran. `RetryRules.java:43-48,96-98` (404 not in
   `RETRIED_STATUSES`) and `:150-156` (only 5xx and 429 count).
7. Connection reuse after a 404 with a body: MET. `ErrorDownstream.connections()` = 1 after all calls.
8. F2 fixed, the docs say only a client's interfaces read a 404 as empty: MET in the note ("What it does" [r2, F2],
   question 4 "Documents"). The C2b beans never pass through `ErrorTranslatingExchangeAdapter`
   (`StratumClientGroups.decorateCalls` is its only registration, `StratumClientGroups.java:67-69`).
9. Spring facts: MET. `HttpRequestValues.Processor.process(Method, MethodParameter[], @Nullable Object[], Builder)`,
   `Builder.addAttribute` and `getAttributes()` compile and work against spring-web 7.0.10 as the probe shows;
   round 1 read `asOptionalIfNecessary` (`HttpServiceMethod.java:454-456`) and the composite processor in the sources.
10. Simplest way, no new class, no cycle, no API (ST-ENG-05, ST-ENG-56, ST-ENG-57): MET. The probe diff is 36 added
    lines in four existing files of `com.exepex.stratum.restclient` and `com.exepex.stratum.httpclient`; the starter
    already imports the library.
11. The note's open item, how the build's null checks treat `answerOf`: SETTLED. `mvn -pl starters/stratum-starter-http-client
    verify -DskipTests` on the probe: Checkstyle 0 violations (after `HttpStatus.NOT_FOUND.value()` in place of the
    literal 404), SpotBugs `BugInstance size is 0` with no suppression on `answerOf` returning a `@Nullable` value as
    non-null. PMD: see Notes.
12. Documents planned (errors page section and table row, `http-client.md` Planned :467 and Errors section, migration
    guide :89, the C2b page): MET. `http-client.md` is 484 lines; removing one Planned line and adding one keeps it
    under 500 (ST-DOC-09).
13. Security, ST-SEC-01 to ST-SEC-06: MET. No new input is trusted; the empty result carries no data; the 404 body
    stays under B2's 64 KB cap; no logging is added.

## Findings

None.

## Planted faults

None: a design-note review with no code in the pull request. I built the design and ran two probes (one unit, one
integration), then restored the working tree (`git status` clean). I changed nothing in the repository.

## Notes

- PMD: with the processor written as a method reference to a four-parameter private method, PMD fails the build with
  `UnusedFormalParameter` on the two parameters the method ignores (`parameters`, `arguments`). A lambda with unnamed
  parameters, `(method, _, _, builder) -> markOptionalAnswer(method, builder)`, is one expression (ST-ENG-46) and avoids
  it. A build detail for the code PR, not a design matter.
- `ErrorTranslatingExchangeAdapter.around` must compare the status with `HttpStatus.NOT_FOUND.value()`, not the literal
  `404` (Checkstyle `MagicNumber`).
- `docs/modules/http-client-errors.md:55-67` today shows `try { return inventory.stock(sku); } catch (DownstreamException)
  { if (status == 404) return Stock.none(sku); ... }` as the way to handle a not-found. Once the new section exists, that
  example should point to `Optional<Stock>` as the first choice, or use a status other than 404, so the page does not
  teach two ways for the same case (ST-DOC-02). The code PR's judge will read the page against the code.
- A 404 whose body is not valid JSON on an `Optional` method also returns empty (probe), which is the right result: the
  status, not the body, decides.
- Round 1's notes still hold: `Optional<Void>` returns `null` by Spring's own rule; a gateway's 404 for a wrong base path
  reads as "not found" on `Optional` methods, which feature 8.4 accepts and the docs could say in one sentence.
- Model: this judge ran on Claude Fable 5.1 (round 1 ran on Opus 5.5).
