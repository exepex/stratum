# Judge confirmation: design note "Retry and circuit breaker settings in files and Java" (round 2 fixes)

**Note:** `design/http-client-libraries/breaker-thresholds-design-note.md` (round 4 text, fixes marked [r4], 365 lines).
**Track:** Feature. **Step:** design-note review, round 2, confirmation of the fixes Aashish asked for (22:08Z, "Yes fix
them"). **Earlier verdict:** `reviews/judge/breaker-thresholds-note-r2.md`. **Base read:** `main` at e97a97c.
**Judge model:** Claude Opus 5.5.

## Verdict: PASS

| # | Round-2 finding | Now | Evidence |
|---|---|---|---|
| 1 | Java example froze the old defaults into `payments` (should fix) | FIXED | The example builds `withDefaults` first, then reads `withDefaults.clientSettings("payments")` and returns `withDefaults` with the new client. `clientSettings` merges over the `defaults` of the record it is called on (`HttpClientProperties.java:64-68`), and rebuilding through `toBuilder()` runs the constructor merge with `STRATUM_DEFAULTS` again (`:31-37`), which is harmless. So `payments` gets `max-retries` 2 from the new defaults, as in the YAML. Test named: `ClientResilienceTest.javaSettingsApply` |
| 2 | `max-delay` check contradicted "ignored with `fixed`" (minor) | FIXED | The check now runs only where the merged `backoff-policy` is `exponential`. A new hostile-input row proves that `defaults.retry.max-delay: 1s` with a `fixed` client whose `delay` is `2s` starts |
| 3 | Two wrong facts (minor) | FIXED | Range and duration checks read raw settings through `allSettings()` (`:229-231`, checked). Cross-key checks read merged settings, like `isSharedPoolWithoutSslBundle` (`:182-186`, checked). The breaker defaults are cited at line 253 (`docs/modules/http-client.md:253`, checked) |

No approved key, name or default changed between round 3 and round 4: the two tables, the YAML and the properties
examples are the same as the ones checked in round 2.

## Planted faults

None: this is a design-note review, no code exists.

## For the code judge

- The sentence beside the Java example says `payments` gets "`delay: 100ms` from the new `defaults`, as in the YAML". In
  the YAML, `payments` sets its own `delay: 200ms`, so only the inheritance of `max-retries` is "as in the YAML". On the
  docs page, check that the Java example and its sentence match the YAML (ST-DOC-05, "the same settings").
- `ClientRetry.ClientRetryBuilder` and `ClientCircuitBreaker.ClientCircuitBreakerBuilder` go on the module's ArchTest
  API list, as `ClientAuth.ClientAuthBuilder` does (`HttpClientArchTest.java:23-24`).
- Not verified in the note, to be proven by tests in the code PR: how Spring reports a bad `backoff-policy` value, and
  a scalar under the `circuit-breaker` group.
