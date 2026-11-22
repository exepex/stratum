# Judge: A1 auth and mTLS design note, round 2 (HTTP client starter, lane A step 1)

Note: `design/http-client-libraries/a1-auth-design-note.md` (round 2). Base `main` at `aabdae8`. Feature track,
design-note review, round 2, steps 1, 2, 3 and 5. Judge model: Fable (round 1 was Opus).

**PASS.** Every round-1 finding is fixed at its cause, not patched. Three notes go to the code step; none changes
the design, and the code judge checks each one.

I read AGENTS.md and all six topic files, ADR 0011, `gap-review-merged.md`, the round-1 verdict, lane B's note
(round 2), and the H1 code on `main` (`ClientSettings`, `HttpClientProperties`, `ClientConnectionPools`,
`StratumClientGroups`, `HttpClientErrorCode`, `StratumClientCustomizer`). I checked the library facts below in the
jars themselves (`javap`): Spring Web 7.0.10, Spring Security 7.1.2 (oauth2-client, oauth2-core, resource-server),
Spring Boot 4.1.2 (`spring-boot`, `spring-boot-security-oauth2-client` jar and POM), Apache HttpClient 5.5.1.

## Round-1 findings

| # | Fix in the note | Verdict | Evidence |
|---|---|---|---|
| F1 | `ssl-bundle` not allowed under `defaults` (`defaults-without-ssl-bundle`), no bundle on the shared pool, token fetch on its own pool with the JVM's default TLS | ACCEPTED, root fix | `ClientSettings.orElse` (`ClientSettings.java:56-66`) still copies every unset value, so forbidding the key is the only clean way; `PoolingHttpClientConnectionManagerBuilder.setTlsSocketStrategy` is per pool (5.5.1 bytecode), and with none set the builder uses `createSystemDefault` under `useSystemProperties` (bytecode lines 214-226). Tests `tokenFetchIgnoresClientBundle`, `otherClientWithoutBundle`, `SslBundleRules` |
| F2 | Waiters share the in-flight `CompletableFuture` per registration, bounded by the time left (lane B's `ScopedValue`, else `timeout`); same token or same failure | ACCEPTED | Design is sound; `hungTokenEndpointUnderConcurrency` tests N callers, 1 fetch, all within `total-timeout`. See Note 1 on the caller that runs the fetch |
| F3 | `ServiceTokenResend` is a `ClientExchangeDecorator` bean at lane B's resend order; reuses `StreamedBody` and `decoratorsInOrder` | ACCEPTED | Matches lane B's round-2 note ("no lane calls `exchangeAdapterDecorator` itself"); `HttpExchangeAdapterDecorator` has the five `exchange*` methods to wrap (7.0.10 bytecode) |
| F4 | Token fetch failure: `SX-HTTPC-0002`, not sent, not retried, not counted, one WARN per client per minute, OAuth2 error code only | ACCEPTED | Spring wraps every `RestClientException` of the token call into `OAuth2AuthorizationException` (`invalid_token_response`; exception table of `AbstractRestClientOAuth2AccessTokenResponseClient.getTokenResponse`), and `DefaultRestClient.exchangeInternal` catches only `IOException`, so a `StratumException` from the initializer reaches lane B's chain unchanged. See Note 2 on the "not counted" test |
| F5 | `ServiceTokenResend.call(client, Supplier)` also serves lane C's `resilience(name).call(...)`; `restClient(name)` alone does not resend (owner, 13:38Z) | ACCEPTED | Owner decision respected; `ResendOn401.plainCallThroughResilience` |
| M1, N1, N2, N3 | Short answers; remove-if-still-the-sent-token; ADR 3.2 reading in the Claims; `withErrorMapping` test | ACCEPTED | As written in the note |

## Gap list (ADR 0011 / gap review / owner decisions)

| Requirement | Result | Evidence |
|---|---|---|
| `auth` holds exactly one kind (3.1); no `auth` or `ssl-bundle` in `defaults` | MET | Keys table, five validation rules, `HttpClientPropertiesTest.AuthRules`, `.SslBundleRules` |
| No request interceptor; header set per attempt (lane C fact, ADR 1.3) | MET | `DefaultRestClient.createRequest` runs `initializers` on every created request (7.0.10 bytecode, `DefaultRequestBodyUriSpec`), and each attempt is a new exchange |
| Client credentials as the service, one token per registration (3.1, spike 4) | MET | `OAuth2ClientHttpRequestInterceptor.ANONYMOUS_AUTHENTICATION` is an `AnonymousAuthenticationToken` (7.1.2 bytecode); `InMemoryOAuth2AuthorizedClientService` keys by `OAuth2AuthorizedClientId`; `sameTokenForTwoUsers` |
| Token request on the client's timeouts, counts toward `total-timeout` (3.3, A3) | MET, see Note 1 | Own factory with `connect-timeout`, `timeout`, `max-wait`; `setRestClient` exists on the token client and its default converters and error handler are the ones the note adds (7.1.2 bytecode) |
| 401 on client credentials: drop, refetch, resend once, POST too; second 401 fails, WARN once a minute; streamed body not resent (3.3, B3, 4.3) | MET | `ResendOn401` (proxy and plain), `StreamedBody`, `Clock` injected |
| Relay: verified access token only; none, ID token or other thread → `SX-HTTPC-0004` before sending; 401 passed back (3.2, 3.3, A9) | MET | `AbstractOAuth2TokenAuthenticationToken` is in the resource-server jar (7.1.2); `TokenRelayIntegrationTest`, hostile inputs on request and `@Async` threads |
| Relay without the security starter stops startup (3.2) | MET as interpreted | `SX-HTTPC-1006` on the resource-server class; the Claims state the reading (N2) |
| mTLS through `ssl-bundle`, reload without restart, TLS verification never off (2.3, 2.4, ST-SEC-11) | MET | `TlsSocketStrategy` is a one-method interface; `DefaultClientTlsStrategy(SSLContext, String[], String[], SSLBufferMode, HostnameVerifier)` exists; `SslBundles.addBundleUpdateHandler(String, Consumer<SslBundle>)` exists (4.1.2); `reloadedCertificateUsed` |
| Client credentials in a non-web service (the "nightly job" case) | MET | Boot's `OAuth2ClientAutoConfiguration` is `@Conditional(NonReactiveWebApplicationCondition)` and `@ConditionalOnClass(ClientRegistration)`, so it runs in a non-web application too (4.1.2 bytecode) |
| `stratum-starter-oauth2-client` brings no lock-down (owner card 13:36Z) | MET | The 4.1.2 POM has `spring-boot-security` at runtime scope, so the exclusion is needed; `OAuth2ClientWebSecurityAutoConfiguration` is `@ConditionalOnWebApplication(SERVLET)` and `@ConditionalOnClass(EnableWebSecurity)`; `noSecurityFilterChain` stays |
| No network at startup (5.5, A13) | MET | Config-only checks; `startsWithProviderDown`; `issuer-uri` caveat stated and documented |
| Secrets never in logs, `toString` or startup errors (ST-SEC-01, -02); placeholders only (3.4) | MET | `toStringHidesSecrets`, `errorShowsNoValue`; ST-ENG-31 errors name keys only |
| `SX-HTTPC-0004` is 500 (6.1) | MET | Runtime codes section; lane B2 registers the status |
| Documents (ST-DOC-01, -04, -05, -09) | MET for a note | New `http-client-auth.md` page, both formats, Java section, `docs/README.md` link, `error-codes.md`, `oauth2-client.md` rewritten; `http-client.md` is 306 lines today |
| Nothing built that the request did not ask for (ST-AG-06) | MET | Every new type has a use in the tables; headers, interceptors and token exchange are out |

## Findings
None.

## Planted faults
None: design-note review (judge.md, steps 1, 2, 3 and 5).

## Notes (for the code step; the code judge checks each)

1. **The caller that runs the token fetch must be bounded by the time left too.** The note bounds the waiters by the
   time left but lists only `connect-timeout`, `timeout` and `max-wait` for the fetch itself. With `timeout: 20s` and
   the default `total-timeout` of 8s, a hung IdP would hold the first caller for 20s unless the token request
   factory also gets lane B's context factory (min of `timeout` and the time left). The code should give
   `tokenRequestFactory` the same context factory as `requestFactory`, and `hungTokenEndpointUnderConcurrency`
   should set `timeout` above `total-timeout` so the assertion covers the fetching caller.
2. **"Not retried, not counted" needs its own assertion.** Resilience4j records every exception unless the predicate
   says otherwise, so whether a `StratumException` (`SX-HTTPC-0002` from the fetch, `SX-HTTPC-0004` from the relay)
   counts against the breaker is lane B's predicate, not lane A's code. `TokenFetchFails` should assert the breaker's
   failure count stays 0 and the attempt count is 1; the relay case needs the same two assertions.
3. **`SX-HTTPC-1005` when no registration exists at all.** Boot creates `ClientRegistrationRepository` only when a
   `spring.security.oauth2.client.registration.*` block is present (`@ConditionalOnOAuth2ClientRegistrationProperties`,
   4.1.2). A team that adds the starter and forgets the block has the class but no bean. The startup check should read
   the repository through an `ObjectProvider` and give `SX-HTTPC-1005` with the registration name, not a Spring
   "no such bean" failure; one test case covers it.
4. Boot's own binder prints a value in its failure report when a property cannot be converted, for example `basic`
   given as one string. That is Boot's behaviour for every property, not Stratum's error path; the auth page could say
   that `basic` is an object with `username` and `password`. Not a finding.
5. A WebFlux service that uses the blocking client starter gets Boot's reactive OAuth2 beans only, so
   `client-credentials` would find no `ClientRegistrationRepository`. The blocking starter is not for WebFlux services
   (ADR 0006, ADR 0011 reactive starter), so this is theoretical today.

## Not verified
- The dependency list of `spring-security-oauth2-client` 7.1.2 (Maven Central answered 429 for its POM), so whether
  it brings `spring-security-config`; the `noSecurityFilterChain` test covers the outcome either way.
- The exact HttpClient 5 version Boot 4.1.2 manages; the APIs cited exist in 5.5.1 and `setTlsSocketStrategy` since 5.4.
- JDK 25, HC5 strategy swap under load, Boot's `issuer-uri` startup call: as the note's own list says.
