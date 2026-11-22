# Judge: A1 auth and mTLS design note (HTTP client starter, lane A step 1)

Note: `design/http-client-libraries/a1-auth-design-note.md` (13:33Z). Base `main` at `aabdae8`. Feature track,
design-note review, round 1. Steps 1, 2, 3 and 5.

**FAIL**: one blocking and four should-fix findings. The design is sound overall: it reuses Spring's manager,
token client and SSL bundles, uses no request interceptor, and fails closed on relay. The gaps are in how far
`ssl-bundle` reaches, how long the single-fetch lock is held, and how it wires into lane B.

I read AGENTS.md and all six topic files. I also read ADR 0011, `gap-review-merged.md`, the H1 code on `main`
(`StratumClientGroups`, `ClientConnectionPools`, `ClientSettings`, `HttpClientProperties`, `HttpClientErrorCode`),
the lane B note (revised 13:40Z) and `handoff/stratum-judge-process.md`. I could not read issue #238 because the
GitHub tools failed to connect. Token exchange is out of A1's scope, so this does not change the verdict.

## Gap list

| Requirement (ADR 0011 / gap review) | Result | Evidence |
|---|---|---|
| `auth` holds exactly one kind: relay, client-credentials, api-key (+header), bearer, basic (3.1) | MET | Keys table, `auth-one-kind` and the other validation rules, tests in `HttpClientPropertiesTest.AuthRules` |
| mTLS through `ssl-bundle` naming `spring.ssl.bundle.*` (2.3, 2.4) | PARTLY | Built, but `defaults.ssl-bundle` reaches every client and the token endpoint, with no way to opt out (F1) |
| Relay sends only a verified access token, never an ID token or raw header; none: `SX-HTTPC-0004` (3.2, A9) | MET | Reads `AbstractOAuth2TokenAuthenticationToken`; `TokenRelayIntegrationTest.NoCallerToken` |
| Relay without the security starter stops startup (3.2) | MET, as interpreted | `SX-HTTPC-1006` on the resource-server class; see Note N2 |
| Client credentials: on a 401, drop the token, refetch, resend once, POST too; 2nd 401 fails, WARN once a minute (3.3, B3) | PARTLY | Decorator path is designed; how it is wired conflicts with lane B (F3); `restClient(name)` not covered (F5) |
| Token request uses the client's timeouts and counts toward `total-timeout` (3.3, A3) | PARTLY | Uses the client's request factory, so lane B's per-attempt limit applies; the wait for the lock is unbounded (F2) |
| Relay 401 passed back unchanged (3.3) | MET | `TokenRelayIntegrationTest.passes401Back` |
| Secrets only from placeholders (3.4) | MET | All examples use `${...}`; the reasoning against a placeholder check holds, since Boot resolves first |
| No network at startup (5.5, A13) | MET | Config-only checks; `startsWithProviderDown` |
| `SX-HTTPC-0004` is 500 (6.1) | MET | Runtime-code section, joins lane B's status list |
| Secrets never logged or in `toString` (ST-SEC-02) | MET | `ClientAuthTest.toStringHidesSecrets`, `errorShowsNoValue` |
| No request body buffering (lane C fact) | MET | Initializers only, no `ClientHttpRequestInterceptor` |
| Failure of the token fetch itself (IdP down, `invalid_client`) | NOT MET | Not described (F4) |

## Findings

**F1, blocking (ST-SEC-11 area / ADR 2.4; wrong behaviour in a setup the note itself recommends).**
The note allows `ssl-bundle` in `defaults` ("one company certificate for every internal call"). `ClientSettings.orElse`
(`ClientSettings.java:56-66`) fills every unset value from `defaults`, and there is no "none" value, so every client
inherits the bundle, external vendors included. The note also builds the token request "on a `RestClient` with the
client's own request factory". The TLS strategy sits on that pool, so the IdP connection uses the downstream's bundle.
A company bundle normally carries a truststore with only the internal CA. A JSSE `SSLContext` built from it rejects
any public-CA chain, so in that setup two things break:
- a vendor client fails TLS on every call;
- a `client-credentials` client whose IdP is Okta, Entra, Auth0 or Cognito fails every token fetch.

**Fix:** (a) the token request uses the client's timeouts but its own TLS, either the JVM default or, later, one set
on the registration, never the downstream's bundle. (b) Either forbid `ssl-bundle` in `defaults`, like `url` and
`auth`, which also removes the shared-pool rule, or let a client clear it. The first is simpler.
Tests: an IdP on a public-CA-style certificate while the client uses a private-CA bundle; and a client without a
bundle next to a defaults bundle.

**F2, should fix (ADR 3.3 "counts toward `total-timeout`", ST-ENG-52).** `SingleFetchTokenManager` "holds a lock per
registration only while no valid token is cached". The note does not say how long a waiter may wait for that lock.
In an IdP outage under load, each holder runs its fetch to its own timeout, and the other callers queue behind it
one after another. Request threads then pile up beyond `total-timeout`. The hostile-input test covers one call only.
**Fix:** waiters either share the in-flight fetch's result, failure included, or `tryLock` for at most the time
left, using lane B's `ScopedValue`. Add `ClientCredentialsIntegrationTest.hungTokenEndpointUnderConcurrency`:
N concurrent calls all end within `total-timeout` and make 1 token request.

**F3, should fix (consistency with lane B, one decorator owner).** The note sets the resend "through
`HttpServiceProxyFactory.Builder.exchangeAdapterDecorator` ... through `Groups.forEachProxyFactory`". Lane B (13:40Z)
says "no lane calls `exchangeAdapterDecorator` itself". Instead, each lane adds a `ClientExchangeDecorator` bean with a
fixed order in `HttpClientConstants` (retry, breaker, 401 resend, mock), and `StratumClientGroups` composes them.
**Fix:** make `ServiceTokenResend` a `ClientExchangeDecorator` bean at lane B's resend order. Use lane B's
`StreamedBody` and `StratumClientGroupsTest.decoratorsInOrder`.

**F4, should fix (ST-ENG-55, design-note Q3).** The note does not say what the caller gets when the token fetch
itself fails: IdP timeout, 5xx, or `invalid_client` after a secret rotation. Spring raises an
`OAuth2AuthorizationException` from inside the initializer. That is neither lane B's `ResourceAccessException` nor a
`RestClientResponseException`. So the note leaves open three things:
- whether it is retried;
- whether it counts against the downstream's breaker;
- which code and log line the service sees. Today it would be an unmapped 500.

**Fix:** state the outcome in one row: not retried, not counted against the downstream breaker, logged once without
secrets, and the code it maps to, agreed with lane B2. Add a test with the token endpoint answering 401 and one with
it hanging.

**F5, should fix (ADR 3.3 on every path, ADR 9.2).** The initializers sit on the builder, so
`stratumHttpClients.restClient("<name>")` gets the token. The 401 resend is an exchange-adapter decorator, though,
and that path has no proxy (lane B: "`restClient(name)` ... has no proxy ... gets the same Retry and breaker in lane
C's C2"). A generated client with `client-credentials` would then fail on the first 401 after an early revocation.
The note does not mention this path. **Fix:** add a line to "Not in A1" saying that C2 gives `restClient(name)` the
401 resend together with retry and breaker, and tell lane C.

**M1, minor (judge.md, the design note).** Answers 2 and 3 run past 3 lines. Answer 1 omits new, existing and hybrid
services and adoption next to a company framework. Answer 2 omits the class it is modelled on and the "no package
cycle" statement (all new types are in `com.exepex.stratum.restclient`, so no new package). **Fix:** add these to
the short answers.

## Planted faults
None: this is a design-note review (judge.md, steps 1, 2, 3 and 5).

## Notes (not findings)
- **N1.** After an early revocation, many concurrent 401s each remove the cached token, and a late one can remove the
  token that was just refetched, causing an extra fetch. That fetch is bounded by single-fetch. Removing only when the
  cached token is the one sent avoids it cheaply.
- **N2.** ADR 3.2 says "without the security starter". The note checks for Spring's resource-server class instead,
  because that starter is not built yet. It is fail-closed at runtime, so this is acceptable. Say it in one line of
  the Claims as an interpretation of the ADR, so the owner sees it.
- **N3.** The 401 resend depends on the 401 reaching it as `HttpClientErrorException`. If B2 maps errors in a status
  handler, inside the decorator, the resend stops working. Keep one test that runs the resend with B2's mapper on.
- **Owner card (my view, not blocking).** Option 1 is reasonable. I confirmed that the 4.1.2 POM of
  `spring-boot-security-oauth2-client` depends on `spring-boot-security` at runtime scope (Maven Central), so the
  exclusion is needed. `noSecurityFilterChain` must stay, because a Boot upgrade could add a dependency.

## Not verified
- Spring Security 7.1.2 and Boot 4.1.2 bytecode facts in the note (manager, anonymous principal, `OAuth2ClientAutoConfiguration`
  without `@EnableWebSecurity`). I checked only the POM dependency above.
- Issue #238 (GitHub tools unavailable in this session).
