# Judge verdict: design note "Retry and circuit breaker settings in files and Java" (round 1)

**Item:** HTTP client starter, retry and circuit-breaker keys (lane B). **Note:**
`design/http-client-libraries/breaker-thresholds-design-note.md` (round 2 text, rewritten to the keys the owner approved
2026-11-09 19:38Z). **Track:** Feature. **Step:** design-note review, round 1. **Base read:** `main` at e97a97c.
**Judge model:** Claude Fable 5.1. I read AGENTS.md and all six topic files in full.

## Verdict: FAIL

One blocking finding, two should-fix, two minor. Everything else in the note is correct and buildable.

## Gap list (the owner's request, 19:06Z to 19:38Z, and the coordinator's brief)

| Requirement | State | Evidence |
|---|---|---|
| Keys per client and under `defaults`, settable in YAML, properties and Java, with IDE metadata | MET | note tables and three examples; `HttpClientPropertiesCustomizer` runs after the files and wins (`HttpClientProperties.java:153-155`, `:186-192`); nested records already give metadata (`ConfigurationMetadataTest` lists `auth.*`) |
| `retry`: `max-retries`, `backoff-policy` (exponential or fixed), `delay`, `multiplier`, `max-delay`; replaces `retries`, `retry-delay` | MET | note tables; migration table |
| `circuit-breaker`: `enabled` plus the seven Resilience4j keys; replaces `circuit-breaker: true/false` | MET | names match `CircuitBreakerConfig.Builder` 2.4.0 (`failureRateThreshold` :432, `slowCallRateThreshold` :457, `waitDurationInOpenState` :494, `slowCallDurationThreshold` :549, `permittedNumberOfCallsInHalfOpenState` :610, `slidingWindowSize` :754, `minimumNumberOfCalls` :777) |
| Slow-call detection off unless set | PARTLY | intent and mechanism right (Resilience4j defaults 60s/100%, `CircuitBreakerConfig.java:44,49`); the startup check that guards it contradicts the defaults, F1 |
| A default breaker configuration, which a team can override | MET | `STRATUM_DEFAULTS` = today's values; key-by-key fallback as `ClientSettings.orElse` (`ClientSettings.java:96-114`) |
| Full Stratum feature parity, and the C2b `RestClient`/`RestTemplate` beans get it through the request-factory wrapper | MET | the beans run `ClientResilience.call` (C2b note), which builds every client's guards from `properties.clientSettings` (`ClientResilience.java:122-156`, `:211-212`, `:223-248`); customizers still run after the keys (`:233`, `:257-259`) |
| Today's behaviour when nothing is set | MET | back-off today = `ofExponentialRandomBackoff(delay, 2, 0.5, max(delay, 2s))` (`RetryRules.java:133-140`); breaker today = `CircuitBreakerConfig.custom()` defaults (`ClientResilience.java:255-256`), which the note's defaults repeat |

## Findings

1. **Blocking (ST-AG-01, ST-ENG-25): the two cross-key startup checks cannot hold with the Stratum defaults the note
   merges into every client.** The note says each key "falls back ... to Stratum" and `STRATUM_DEFAULTS` holds today's
   values, with `multiplier` `2.0` and `slow-call-rate-threshold` `100` as defaults in its tables. It then fails startup
   when "`multiplier` or `max-delay` [is] set with `backoff-policy: fixed`" and when "`slow-call-rate-threshold` [is] set
   without `slow-call-duration-threshold`", "one `@AssertTrue` per rule". Today `defaults` is merged with
   `STRATUM_DEFAULTS` in the constructor (`HttpClientProperties.java:168-174`) and the checks read `defaults` and the
   clients (`allSettings()`, `:365-368`) or the merged `clientSettings` (`:318-324`). So, built as written:
   `stratum.http-client.defaults.retry.backoff-policy=fixed` fails startup on every reading, because `defaults` always
   carries Stratum's `multiplier`; the note's own `recommendations` client (`fixed`, no multiplier) fails if the check
   reads merged settings and passes if it reads the raw client; and if `STRATUM_DEFAULTS` holds the rate `100`, every
   service fails the slow-call check. **Fix:** say which object each cross-key check reads (`defaults` and
   `clientSettings(name)`, as today, is the right one for "`max-delay` shorter than `delay`"), and make the defaults
   agree with it. Simplest: drop the `multiplier`/`max-delay`-with-`fixed` check, since the table already says "Only
   with `exponential`" (ST-ENG-05); keep the slow-call check on merged settings, with `STRATUM_DEFAULTS` leaving both
   slow-call keys unset and the table saying the rate's `100` is Resilience4j's, used when the duration is set.
2. **Should fix (documentation rules, "Where things are documented", `docs/agents/documentation.md:22`): the note adds a
   line to ADR 0012.** "An accepted record is never edited ... a new record replaces it." ADR 0012 is Accepted and
   merged (`docs/adr/0012-...md:3`, commit e3e902e). The only edit of an accepted ADR so far (2a50969 on ADR 0011) was
   inside the ADR's own open pull request, so there is no precedent. **Fix:** record the two groups and the three
   replaced keys in a new record: lane C's ADR 0013 if it lands first, otherwise this item's own ADR, with its row in
   `docs/README.md`.
3. **Should fix (documentation, "How documentation is checked"): `docs/README.md` is not in the Documents list.** The
   note creates `docs/modules/http-client-resilience.md`; "a user documentation page missing from `docs/README.md`" is a
   finding, and today's index row (`docs/README.md:69`) lists only the module, auth and errors pages. **Fix:** add the
   row to question 4's Documents.
4. **Minor (ST-AG-01): the IDE flag on the old keys reaches `defaults.*` only.** Generated metadata has no per-client
   entries: `ConfigurationMetadataTest.java:21-42` expects `stratum.http-client.defaults.<key>` and
   `stratum.http-client.clients` alone, and the metadata format has no wildcard for a map entry. So
   `clients.payments.retries` is not flagged red. **Fix:** say the deprecation entries cover `defaults.retries`,
   `defaults.retry-delay` and `defaults.circuit-breaker`, and that the migration guide covers the per-client keys.
5. **Minor (ST-AG-01): "`docs/modules/http-client.md` is at 484 lines".** `wc -l` gives 486. **Fix:** correct the count.

## Planted faults

None: this is a design-note review, no code exists.

## Notes

- `minimum-number-of-calls` above `sliding-window-size`, listed as not verified: verified. Resilience4j 2.4.0 caps it
  at the window size for a count-based window (`CircuitBreakerMetrics.java:50-51`, sources from Maven Central); the
  builder stores both as given (`CircuitBreakerConfig.java:1023-1024`). The docs sentence can stay; move it out of
  "Not verified".
- Resilience4j 2.4.0 has no check on the multiplier (`IntervalFunction.java:146-158`); the `1.0` to `10.0` range is
  Stratum's own bound (ST-ENG-52), which is right. The randomization factor `0.5` is within its `0.0` to `1.0` check
  (`:255-256`). `ofRandomized(Duration, double)` exists (`:80`).
- `docs/modules/resilience.md` (the planned resilience starter) names the same Resilience4j settings differently
  (`minimum-calls`, `wait-in-open-state`, `half-open-calls`, `slow-call-threshold`, lines 106-113). When the shared
  Resilience4j setup moves into a library (ADR 0011, 4.1), the two should use one set of names. Not this item.
- Answers are longer than the 3 lines `judge.md` asks for, as every earlier HTTP client note was; not held against this
  one.
