# Lane C handover (updated 2026-11-09 22:08Z)

## Done
- C1 streaming: PR #253, merged.
- C2a own clients: PR #252, merged.
- C3 mocks: PR #259, merged 2026-11-09 22:07Z.

## Not started: C2b, injectable RestClient and RestTemplate beans per client (needs an issue)
- The design note is at round 3: `c2b-design-note.md` in this folder.
  - Judge r1 (Opus) and r2 (Fable) both FAILed; every finding is fixed in the note.
  - Before code, it needs a fresh judge or Aashish's OK.
- The full user examples are in `c2b-explained.md`.

What the issue should carry:
- `StratumClientBeansRegistrar`: non-default-candidate beans with a qualifier, injected as `@Qualifier("<name>")`.
- `ResilientRequestFactory` runs each attempt through `ClientResilience.call`:
  - retry for bodiless calls only;
  - the breaker shared with the client's interfaces;
  - B2's `SX-HTTPC-0003` (503) when the breaker is open;
  - initializers per attempt, so the token is set and lane A's 401 resend works.
- `RestTemplate` takes the wrapped factory via `RestTemplateBuilder.requestFactoryBuilder(settings -> f)`, not via
  `requestFactory`.
- A new public method, `StratumClientCustomizer.customizeRestTemplate(String, RestTemplateBuilder)`.
- One span per call, not one per attempt, stated in the docs.
- A new docs page `http-client-own-clients.md`, ADR 0013 and a new `ClientBeansIntegrationTest`.
- Before the code PR, a separate issue for retry and breaker on SDK clients with their own HTTP engine.

## Follow-ups
- C2b's docs and examples must use Aashish's final keys from 19:38Z:
  - `retry`: `max-retries`, `backoff-policy`, `delay`, `multiplier`, `max-delay`.
  - `circuit-breaker`: `enabled`, `failure-rate-threshold`, `sliding-window-size`, `minimum-number-of-calls`,
    `wait-duration-in-open-state`, `permitted-number-of-calls-in-half-open-state`, `slow-call-duration-threshold`,
    `slow-call-rate-threshold`.
  - The beans read them only through `ClientResilience`, so they need no code of their own. Lane B builds the keys.
- `c2b-explained.md` still shows the old flat keys, and a retry table without `PUT`. Align it with the final keys and
  B1's rules.
- B2b's Optional-on-404 needs one docs line on the own-clients page, added by whichever PR lands second.
- C3: lane A's client-credentials is now in main; add a test that a mocked client set to client-credentials fetches no token (holds by construction in `ClientTransport.Mocked`, not yet tested).
- C3: two planned issues. One for a mocked client in a metric or actuator info, one for mocks in the reactive
  starter.
