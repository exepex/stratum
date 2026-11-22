# Judge verdict: design note "Retry and circuit breaker settings in files and Java" (round 2)

**Item:** HTTP client starter, retry and circuit-breaker keys (lane B). **Note:**
`design/http-client-libraries/breaker-thresholds-design-note.md` (round 3 text, fixes marked [r3], 352 lines).
**Track:** Feature. **Step:** design-note review, round 2. **Base read:** `main` at e97a97c. **Earlier verdict:**
`reviews/judge/breaker-thresholds-note.md` (round 1, FAIL). **Judge model:** Claude Opus 5.5. I read AGENTS.md and all
six topic files in full.

## Verdict: FAIL

One should-fix and two minor findings, all text fixes of a few lines. Every round-1 finding is fixed, and no
approved key, name or effective default changed.

## Round-1 findings

| # | Round 1 | Now | Evidence |
|---|---|---|---|
| 1 | Cross-key checks contradict merged Stratum defaults (blocking) | FIXED | `fixed` check dropped; both slow-call keys unset in `STRATUM_DEFAULTS`; slow-call check reads `defaults` and `clientSettings(name)`. Against `HttpClientProperties.java:31-37` (defaults merged with `STRATUM_DEFAULTS`) and `:64-68` (`clientSettings`), nothing Stratum ships can trip the slow-call check, and `defaults.retry.backoff-policy=fixed` alone now starts (new hostile-input row). See finding 2 for the one leftover of the same kind |
| 2 | Edit to accepted ADR 0012 (should fix) | FIXED | ADR 0012 not edited; new record (ADR 0013 if open, else own ADR) with its `docs/README.md` row. ADR 0013 is not on `main` (`ls docs/adr`), so both branches are needed |
| 3 | `docs/README.md` row missing (should fix) | FIXED | Documents list adds the row next to `docs/README.md:69` (checked: module, auth, errors) |
| 4 | IDE flag only on `defaults.*` (minor) | FIXED | The note now says so and points per-client keys to the migration guide |
| 5 | Line count 484 (minor) | FIXED | `wc -l docs/modules/http-client.md` = 486 |

## Gap list (owner's request 19:06Z-21:43Z and the coordinator's brief)

| Requirement | State | Evidence |
|---|---|---|
| `retry` keys: `max-retries`, `backoff-policy`, `delay`, `multiplier`, `max-delay` | MET | names unchanged from the approved list; ranges and defaults = today (`RetryRules.java:64,133-140`, max 9 = `HttpClientConstants.MAX_RETRIES`) |
| `circuit-breaker` keys: `enabled` + seven Resilience4j names | MET | all eight present, spelled as approved |
| Under `defaults` and per client, key-by-key fallback | MET | `orElse` model of `ClientSettings.java:96-114`; test `keysFallBackOneByOne` named |
| YAML, properties and Java | PARTLY | YAML and properties identical values (ST-DOC-05); the Java example does not give the same result, finding 1 |
| A default breaker a team can override | MET | today's values (`ClientResilience.java:252-259`); `defaultsAreTodays` compares with `CircuitBreakerConfig.ofDefaults()` |
| Slow-call off unless set | MET | pair passed only when the duration is set; planted fault listed |
| Parity for interfaces and C2b beans | MET | one build point `ClientResilience.newGuards` (`:223`); customizers still run after (`:233`, `:259`) |
| No approved key, name or value changed in r3 | MET | slow-call rate default reads "not set; Resilience4j's 100 applies", which is the same effective value |

## Findings

1. **Should fix (ST-DOC-05, ST-DOC-06): the Java example gives `payments` other retries than the YAML.** It builds
   `payments` from `properties.clientSettings("payments")` and only then replaces `defaults`. `clientSettings` returns the
   client merged over the *old* defaults (`HttpClientProperties.java:64-68`), so every retry value is copied into the
   client and the new `defaults` never reach it. With only this customizer, `payments` gets `max-retries` 3 and `delay`
   200ms, while the YAML gives it 2 (from `defaults`). A team copying the page gets a silent difference. This is the
   first page example that changes `defaults` and a client in one customizer; the existing ones (`http-client.md:297`,
   `http-client-auth.md:251`) change one client only. **Fix:** take the client from the new defaults
   (`properties.toBuilder().defaults(defaults).build().clientSettings("payments")`), or show the same settings as the
   YAML in the same order, and say in one line that `clientSettings` returns merged values.
2. **Minor (ST-AG-01): the `max-delay` check contradicts "with `fixed` it is ignored".** The check "`max-delay`
   shorter than `delay`" reads merged settings. A team with `defaults.retry.max-delay: 1s` and a `fixed` client with
   `delay: 2s` fails startup on a key the table calls ignored. **Fix:** run that check only where the merged policy is
   `exponential`, with a hostile-input row for the `fixed` case.
3. **Minor (ST-AG-01): two small wrong facts.** "as the checks do today": today's range checks read the raw clients
   (`allSettings()`, `HttpClientProperties.java:229-231`); only `isSharedPoolWithoutSslBundle` reads merged ones
   (`:182-186`). And "The module page states the first five at line 251": they are at line 253. **Fix:** say the new
   checks read merged settings (unlike today's range checks), and cite line 253.

## Planted faults

None: this is a design-note review, no code exists.

## Notes

- `ClientRetry.ClientRetryBuilder` and `ClientCircuitBreaker.ClientCircuitBreakerBuilder` are public nested types a
  service uses (the Java example); they go on the ArchTest API list with the records, as `ClientAuth.ClientAuthBuilder`
  does (`HttpClientArchTest.java:23-24`). The ArchTest fails the build if missed, so not a finding.
- `docs/guides/error-codes.md` names no old key today (`grep` finds only prose "retries"), so its line in Documents may
  turn out to need no change.
- Round 2 of this review step: if the author answers these with evidence instead of fixes, the owner decides
  (judge.md, Rounds 3).
