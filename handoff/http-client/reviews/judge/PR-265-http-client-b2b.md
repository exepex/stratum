## Judge verdict: PR #265, B2b (a 404 read as an empty `Optional`)

Branch `claude/project-thread-ex55r3`, head `e0447c0`, track Feature, code review round 1. Judge model: Opus 5.5
(the note's round-2 judge was Fable 5.1).

**FAIL**: two should-fix findings. The behaviour, design and docs are right; both fixes are small.

### Gate

- Mergeable: `main` (`abeaa2a`) is an ancestor of the head, and `git merge-tree` shows no conflicts.
- CI green at `e0447c0`: Verify and SBOM, CodeQL (actions, java-kotlin), the wiki check, SonarCloud (gate passed).
- Bot threads: two Codex ST-ENG-02 threads are fixed and resolved. The open ST-ENG-54 thread is decided below.

### Gap list

| Requirement | Result | Evidence |
|---|---|---|
| 404 gives `Optional.empty()` for all four shapes | MET | `notFoundIsEmpty` (four rows) passed on JDK 25; plants P3, P4, P6 caught |
| A successful answer is present | MET | `foundIsPresent` |
| 404 on a non-`Optional` method still throws `DownstreamException` | MET | `plainMethodStillThrows`; plant P1 caught |
| Any other status on an `Optional` method still throws | PARTLY | Tested for 500 only. A plant that empties every 4xx survives (F2) |
| `mapError` is not asked about an `Optional` 404 | MET | `OptionalNotFound` order -1 is inside `ClientErrors` (-2); `optionalNotFoundNotMapped`; P3 caught |
| Unmarked missing answer still fails fast | MET | `AroundCallExchangeAdapterTest`; plants P4, P6 caught |
| A 404 read as empty never counts against the breaker (ADR 0011 line 116) | MET | Plant P2 (breaker counts every 4xx) caught by `notFoundNeverOpensBreaker` |
| Only the interfaces do this; the beans keep `NotFound` | MET | Only `StratumClientGroups.decorateCalls` applies decorators and `prepare`; stated in `http-client-errors.md` |
| Docs: errors page, http-client.md Planned and Errors, migration guide | MET | Read against the code at `e0447c0` |
| No new public type or key; ST-ENG-56/57 | MET | All new types and constants are `@StratumInternal` or in `HttpClientConstants` |
| Line counts | MET | `wc -l` matches the Claims; `http-client.md` 484 is under 500 |

### Findings

1. **Should fix, ST-ENG-54.** The processor in `StratumClientGroups.decorateCalls` (`StratumClientGroups.java:72-74`)
   calls `decorators.orderedStream()` on every call of every client. That resolves the beans from the bean factory and
   sorts them for each request. Measured with Spring 7.0.10, 400 beans in the context and three decorators: about
   240-490 ns and **888 bytes of garbage per call**, against about 14 ns and 0 bytes when the list is built once.
   `decorate` in the same class already builds its list once, at start-up. **Fix:** resolve the ordered list once
   (for example `var ordered = decorators.orderedStream().toList();` in `decorateCalls`) and use it in the processor.
2. **Should fix, ST-TEST-01 and ST-AG-17 (sibling cases).** No test sends a 4xx other than 404 to an `Optional`
   method. Plant P5 (`OptionalNotFound`: `== NOT_FOUND` changed to `is4xxClientError()`) passes every test. It would
   turn a 401, 403 or 409 into "not found" with no error. **Fix:** add one 4xx endpoint (for example 403) to
   `ErrorDownstream` and assert that an `Optional` method gets `DownstreamException` with that status.

### The open Codex thread (ST-ENG-54, `Method.getReturnType()`)

I **accept the author's rejection**. `javap -c -p java.lang.reflect.Method` on JDK 25 shows
`aload_0; getfield returnType; areturn`: one field read on the `Method` that Spring already passes in. That is not the
costly reflection that ST-ENG-54 forbids. The real per-request cost on that path is the bean lookup in F1. Once F1 is
fixed, answer the thread and resolve it.

### The two gaps named in the Claims

- **Connection reuse after a large 404 body: no finding.** `ErrorBodyLimit.execute` treats every status `>= 400` the
  same way. Spring reads and closes the capped body before `OptionalNotFound` sees the exception, and the catch only
  returns `null`. `ClientErrorsIntegrationTest.BodyLimit.largeErrorBodyCapped` (1 MB, connection reused) covers that
  path and passed. The note's `largeNotFoundBodyEmptyAndConnectionReused` would add nothing.
- **Observation tag from the attribute: verified, no finding.** `javap -c -p` of Spring 7.0.10's
  `DefaultClientRequestObservationConvention` has no reference to request attributes. The HTTP client starter defines
  no observation convention of its own (`grep -rn ObservationConvention` finds none there).

### Planted faults

The implementer planted 9 and the tests caught all 9. I planted 6 more on JDK 25, one at a time, then restored the
code. The baseline was green. The tests caught **5 of 6**:

| # | Fault | Result |
|---|---|---|
| P1 | Mark every method, not only `Optional` ones | Caught: `plainMethodStillThrows`, `firstMappingWins` |
| P2 | Breaker counts every 4xx | Caught: `notFoundNeverOpensBreaker` (and others) |
| P3 | `OptionalNotFound` bean never marks | Caught: `notFoundIsEmpty` x4, `optionalNotFoundNotMapped` |
| P4 | `exchangeForBodilessEntity` keeps `requireNonNull` | Caught: `optionalCallGetsNothing` |
| P5 | Every 4xx on an `Optional` method is empty | **Survived** (F2) |
| P6 | `exchangeForHeaders` keeps `requireNonNull` | Caught: `optionalCallGetsNothing` |

### Build

`./mvnw verify -pl libs/stratum-http-client,starters/stratum-starter-http-client -am` on JDK 25 exits 0 at `e0447c0`.
That covers Spotless, Checkstyle, PMD, SpotBugs, the tests and the coverage gate. `OptionalNotFoundIntegrationTest`
ran 7 tests, `ErrorMapperIntegrationTest` 3 and `AroundCallExchangeAdapterTest` 2, with 0 failures.

### Code rules

| Rule | Result |
|---|---|
| ST-ENG-02 | The 404 rule is behind `ClientExchangeDecorator` |
| ST-ENG-05 | No unused type or option. `prepare` has one user today, and it is internal |
| ST-AG-12 | One new class, `OptionalNotFound`, as the Codex ST-ENG-02 fix. It is the simplest fix |
| ST-ENG-16 | Marking and the 404 rule are one feature in one class |
| ST-ENG-42 | No hand-written boilerplate. Constructor injection is unchanged (`@RequiredArgsConstructor`) |
| ST-ENG-14/15 | Every new member has short Javadoc |
| ST-ENG-54 | See F1 |

### Notes

- The code moved away from the note in one place: the note said "no new class", and the code adds `OptionalNotFound`
  plus the `prepare` hook. Both follow from the accepted Codex ST-ENG-02 findings, and the Claims say so.
