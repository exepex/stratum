# Design note: A1, auth and mTLS for the blocking HTTP client starter (Feature track), round 2

**Item:** "auth" from ADR 0011's follow-up list (decision 3, and `ssl-bundle` of decision 2.4), lane A. Owner's go:
2026-11-09 13:26Z, "work autonomously as per the previous agreements to finish HTTP client starter", parallel lanes.
Base: `main` at `aabdae8` (H1, PR #242). Built in two pull requests on one note:

- **A1a:** `auth` with `basic`, `bearer`, `api-key` (+ `api-key-header`), and `ssl-bundle` (mTLS, with reload).
- **A1b:** `auth.client-credentials` and `auth.token-relay`, `SX-HTTPC-0004`, and `stratum-starter-oauth2-client`.

**Not in A1:** `headers`, `copy-headers` and the forbidden-header list (A2, lane A next); interceptors (A2);
`token-exchange` (#238); the error mapper and `DownstreamException` (lane B); the reactive starter. `stratumHttpClients.restClient("<name>")` (ADR 9.2)
gets the token itself, since the initializers sit on the builder, but no resend on its own: the owner chose (13:38Z,
lane C) that teams wrap such calls in `resilience(name).call(...)`, which lane C builds and which runs retry, breaker
and `ServiceTokenResend.call` around the team's call (see the 401 bullet).

**Owner decision (card, 13:36Z): option 1.** A team adds `stratum-starter-oauth2-client` to use `client-credentials`.
It brings Spring Boot's OAuth2 client (`spring-boot-security-oauth2-client`) with `spring-boot-security` excluded, so
there is no default lock-down. It has no code and no keys.

## 1. Who uses it, and does anything change for them?
New, existing and hybrid services alike, with Stratum as parent or next to a company framework: a client gets a
credential only from its own `auth` or `ssl-bundle` lines, so nothing changes for any client without them
(`NoAuthIntegrationTest.sendsNoAuthorization`). New API types: `ClientAuth`, `BasicCredentials`; usage 1 to 2 lines.

## 2. How is it built, and why this way?
Reuses Spring Security 7.1.2's manager and token client, Boot's registrations, token store and `SslBundles`, HC5's
`DefaultClientTlsStrategy`, lane B's `ClientExchangeDecorator`; modelled on H1's `ClientConnectionPools`. All new types
in the existing `com.exepex.stratum.restclient`: no new package, no cycle. Rejected: Stratum's own token client and cache.

## 3. Is it safe and fast?
Headers set per attempt by request initializers (no body buffering); no secret in logs, `toString` or startup errors.
Relay fails closed; token fetch waits are bounded by the time left; a downstream's bundle never reaches the IdP or
another client. No thread hop: everything runs on the caller's thread (ST-ENG-53). Details below.

## 4. Which rules and owner decisions does it follow?
ADR 0011 2.3, 2.4, 3.1 to 3.5, 4.3, 5.5, 6.1; gap review A3, A9, A13, B3, B4; owner card 13:36Z; ST-ENG-11, -24, -25,
-34, -42, -51, -52, -55, -56; ST-SEC-01, -02, -06, -11, -18; ST-DOC-04, -05, -08, -09; ST-TEST-01, -20. Tests and
library facts are in the tables below.

## Details

### Keys
Under `clients.<name>` only. Neither `auth` nor `ssl-bundle` is allowed under `defaults`.

| Key | Default | A real case that changes it |
|---|---|---|
| `auth.token-relay` | unset | A BFF calls the orders API as the signed-in user |
| `auth.client-credentials` | unset | A nightly job calls invoices as itself; value = a `spring.security.oauth2.client.registration` name |
| `auth.api-key` | unset | A vendor's geocoding API |
| `auth.api-key-header` | `X-Api-Key` | The vendor wants `Ocp-Apim-Subscription-Key` |
| `auth.bearer` | unset | A partner gave a long-lived token |
| `auth.basic.username`, `auth.basic.password` | unset | A legacy bridge with Basic auth |
| `ssl-bundle` | unset | The bank needs a client certificate; value = a `spring.ssl.bundle.*` name |

- Validation (`SX-CORE-1003`, ST-ENG-31): `auth-one-kind`, `basic-complete`, `api-key-header-with-api-key`,
  `defaults-without-auth` and `defaults-without-ssl-bundle`. One secret or one company truststore under `defaults`
  would reach every client, external vendors included, and a truststore with only the internal CA would break them
  (judge F1). A company with one certificate for every internal client sets it per client or in its Java customizer.
- `shared-pool-without-ssl-bundle`: a client with `shared-pool: true` and an `ssl-bundle` stops startup, since the
  shared pool serves several clients and has no bundle.
- Spring Boot has no per-client key for these; ADR 0011's ST-ENG-34 exception applies. Registrations and bundles stay
  Spring's keys (ADR 2.3). Java: `HttpClientPropertiesCustomizer` sets every key through the `ClientAuth` and
  `BasicCredentials` builders, which fits secrets from a vault.
- Every example uses `${...}` placeholders (ADR 3.4). There is no startup check that a value came from one: an
  environment variable or the Java customizer are legitimate sources too, and Boot resolves placeholders first.

### API and internal types (ST-ENG-56)
- **API (lib):** `ClientAuth`, `BasicCredentials` and their builders, as fields of `ClientSettings`.
- **`@StratumInternal` (starter):** `StaticCredentialInitializer`, `SslBundleTlsStrategy`, `ServiceTokenInitializer`,
  `ServiceTokenResend`, `SingleFetchTokenManager`, `TokenRelayInitializer`, `ClientAuthInitializers` (picks the one
  initializer for a client's kind), and the new OAuth auto-configuration class.
- `ClientAuth` and `BasicCredentials` override `toString` to show only which kind is set (records print every field;
  PMD's `UseLombokToString` matches classes only, `pmd-lombok.xml:121`).

### How each kind works
No kind uses a `ClientHttpRequestInterceptor`, which makes Spring buffer the whole request body (lane C, 13:32Z). Each
sets its header in a `ClientHttpRequestInitializer`, which Spring runs each time it creates a request: once per attempt.

- **basic, bearer, api-key:** one `StaticCredentialInitializer(headerName, headerValue)` per client, built once at
  startup (`HttpHeaders.encodeBasicAuth` for Basic).
- **client-credentials:**
  - `ServiceTokenInitializer` asks the manager for the registration's token with the service as principal (Spring's
    anonymous principal, as `OAuth2ClientHttpRequestInterceptor` uses when no user resolves; bytecode of 7.1.2).
    Never the signed-in user: Spring's default resolver keys the token by user, so a busy API would fetch one per user.
  - Manager: `AuthorizedClientServiceOAuth2AuthorizedClientManager` on Boot's `OAuth2AuthorizedClientService`, wrapped
    by `SingleFetchTokenManager`. While a valid token is cached, it returns it with no lock. Otherwise the first caller
    starts the fetch on its own thread and publishes it as a `CompletableFuture` per registration; every other caller
    waits on that future for at most the time left (lane B's `ScopedValue`, else the client's `timeout`), and gets the
    same token or the same failure. So N concurrent calls make 1 token request and all end within `total-timeout`,
    even when the IdP hangs (judge F2).
  - Token request: Spring's `RestClientClientCredentialsTokenResponseClient` on a `RestClient` with its own request
    factory from `ClientConnectionPools.tokenRequestFactory(client, settings)`: the client's `connect-timeout`,
    `timeout` and `max-wait` (A3), its own small pool, and the JVM's default TLS, never the client's `ssl-bundle`
    (judge F1). Plus the three converters and the error handler the spike found missing otherwise (spike finding 4).
    The initializer runs before Apache leases the downstream connection, so no connection is held during the fetch.
  - **Token fetch fails** (IdP timeout, I/O error, 5xx, or `invalid_client` after a secret rotation; Spring raises
    `OAuth2AuthorizationException`): the call stops before sending with `SX-HTTPC-0002` (unavailable, 503), an existing
    ADR code, with detail naming the client and registration. It is not retried (a `StratumException` is not on lane B's
    retry list) and not counted against the downstream's breaker (not a downstream failure). One WARN per client per
    minute names the client, registration and the OAuth2 error code, never a secret. Agreed with lane B2 through the
    coordinator (judge F4).
  - **401 (B3):** `ServiceTokenResend` is a `ClientExchangeDecorator` bean at lane B's resend order in
    `HttpClientConstants` (retry, breaker, resend; judge F3). Its one method of work is `call(client, Supplier<T> attempt)`,
    which the decorator uses for proxy calls and lane C's `resilience(name).call(...)` uses for plain calls. It
    catches the 401 `HttpClientErrorException`,
    removes the cached token only if it is still the one that was sent (judge N1), and calls once more, POST included
    (ST-ENG-51). A call with a streamed body (lane B's `StreamedBody`) is not resent (ADR 4.3). A second 401 is thrown
    as is and logs one WARN per client per minute, never the token (`Clock` injected). A team status handler that does
    not throw on 401 turns the resend off for that client; the auth page says so.
- **token-relay:** `TokenRelayInitializer` sends the token of an `AbstractOAuth2TokenAuthenticationToken` in Spring's
  security context, which Spring Security's resource server puts there only after verifying it (JWT or introspected
  opaque). An ID token (`OAuth2AuthenticationToken`), a raw incoming header or no authentication throws `SX-HTTPC-0004`
  before anything is sent. A 401 is passed back unchanged (ADR 3.3). **Reading of ADR 3.2, stated in the Claims for
  the owner (judge N2):** "without the security starter" is checked as "without Spring's resource server", because
  the client starter cannot read that starter's classes (ST-ENG-11) and the security starter is not built yet.
- **mTLS:** `SslBundleTlsStrategy` implements HC5's `TlsSocketStrategy`, holding a `DefaultClientTlsStrategy` from the
  bundle's `SSLContext`, protocols and ciphers, with HC5's default hostname check, set on the client's own pool. On
  `SslBundles.addBundleUpdateHandler` (spike item 8) it swaps in a new strategy: new connections use the new
  certificate, open ones end within `connection-lifetime` (5m). TLS verification is never turned off (ST-SEC-11).

### Order
- Proxy factory: lane B composes the `ClientExchangeDecorator` beans in `StratumClientGroups`, outermost first:
  retry, breaker, `ServiceTokenResend`. The resend happens within one attempt, and B2's error mapping must keep
  the 401 visible to it as `HttpClientErrorException` (judge N3; test below).
- Builder: the one auth initializer, then `StratumClientCustomizer` beans. Lane C's C2 moves this set-up into
  `StratumClientBuilders.configure`; whichever lands second rebases.

### Dependencies
- A1a adds none.
- A1b: the starter gets `spring-security-oauth2-client` and `spring-security-oauth2-resource-server` as **optional**
  dependencies. The OAuth parts sit in their own auto-configuration class with `@ConditionalOnClass`, so a service
  without Spring Security loads none of them.
- `stratum-starter-oauth2-client` gets `spring-boot-security-oauth2-client` with `spring-boot-security` excluded (its
  4.1.2 POM has that dependency at runtime scope; the judge confirmed it). Its `OAuth2ClientAutoConfiguration` makes
  the repository and `InMemoryOAuth2AuthorizedClientService`; `OAuth2ClientWebSecurityAutoConfiguration` needs
  `@EnableWebSecurity`, absent then (bytecode of 4.1.2). A test keeps proving no `SecurityFilterChain` and no
  generated password, so a Boot upgrade that adds a dependency is caught.

### Startup checks (config only, no network; A13)
| Code | When | Detail names |
|---|---|---|
| `SX-HTTPC-1005` | `client-credentials` names no registration, or one whose grant is not `client_credentials` | the key and registration name |
| `SX-HTTPC-1006` | `client-credentials` without Spring's OAuth2 client, or `token-relay` without Spring's resource server | the key and the dependency to add |
| (Boot's own) | `ssl-bundle` names no bundle | Boot's `NoSuchSslBundleException` |

A registration with `issuer-uri` makes Boot call the provider at startup; the docs show `token-uri`, which does not
(not verified in 4.1.2: a test checks the `token-uri` path).

### Runtime codes
- `SX-HTTPC-0004`: no caller token for relay, 500 (ADR 6.1). A1b adds `stratum.web.error.statuses[SX-HTTPC-0002]=503` and `[SX-HTTPC-0004]=500` to the starter's defaults in `RestClientEnvironmentPostProcessor` (agreed with lane B, 17:45Z).
- `SX-HTTPC-0002`: also used when the service token cannot be fetched (above).

### Safe and fast (ST-AG-14)
- Speed: a header set per attempt; cached token read with no lock.
- Throughput: one token fetch per registration; waits bounded by the time left.
- Log volume: one WARN per client per minute for a repeated 401 or a failed token fetch.
- Memory and leaks: one token per registration (not per user); 401 answers closed before the resend; the in-flight
  future is removed when it completes.
- Data leaks: no value in `toString`, startup errors or logs; the relay never sends an ID token or raw header.
- Security: fail-closed relay; no `auth` or bundle in `defaults`; the IdP never sees a downstream's bundle; TLS on.
- Maintainability: Spring's manager, token client and bundles; Stratum adds glue and checks.
- Scalability: certificate reload without restart; single fetch protects the provider at scale.

Hostile inputs, each a test: a downstream answering 401 forever (2 sends, then the 401); a token endpoint that hangs,
with one call and with 20 concurrent calls; a token endpoint answering 401 (`invalid_client`); a context with an ID
token, a non-OAuth2 `Authentication` or nothing, on the request thread and on an `@Async` thread.

### Decisions and their tests
| Decision | Source | Test |
|---|---|---|
| basic / bearer / api-key header on every attempt | ADR 3.1 | `StaticCredentialIntegrationTest.basic`, `.bearer`, `.apiKeyDefaultHeader`, `.apiKeyOwnHeader` |
| one kind; Basic complete; api-key-header only with api-key; no `auth` or `ssl-bundle` in `defaults`; no bundle on the shared pool | ADR 3.1, judge F1 | `HttpClientPropertiesTest.AuthRules`, `.SslBundleRules` |
| no value in `toString` or startup errors | ST-SEC-02 | `ClientAuthTest.toStringHidesSecrets`, `HttpClientPropertiesTest.AuthRules.errorShowsNoValue` |
| `auth` set in Java wins | ST-ENG-34 | `HttpClientPropertiesTest.javaSetsAuth` |
| client certificate sent; client without a bundle unaffected | ADR 2.4, judge F1 | `SslBundleIntegrationTest.sendsClientCertificate`, `.otherClientWithoutBundle` |
| reloaded bundle used for new connections | spike 8 | `SslBundleIntegrationTest.reloadedCertificateUsed` |
| token fetch uses default TLS, not the client's bundle | judge F1 | `ClientCredentialsIntegrationTest.tokenFetchIgnoresClientBundle` (IdP on a certificate the bundle's truststore rejects) |
| service token, cached, keyed by service not user | ADR 3.1, spike 4 | `ClientCredentialsIntegrationTest.sendsServiceToken`, `.oneFetchForManyCalls`, `.sameTokenForTwoUsers` |
| concurrent calls: 1 fetch, all end within the time left, IdP hung | judge F2 | `ClientCredentialsIntegrationTest.concurrentCallsFetchOnce`, `.hungTokenEndpointUnderConcurrency` |
| token fetch fails: `SX-HTTPC-0002`, not sent, not retried, one WARN | judge F4 | `ClientCredentialsIntegrationTest.TokenFetchFails` (endpoint 401, endpoint hangs) |
| 401: drop, fetch, resend once (POST too); second 401 thrown, one WARN a minute; streamed body not resent | B3, ST-ENG-51 | `ClientCredentialsIntegrationTest.ResendOn401` (proxy call, and `.plainCallThroughResilience`) |
| resend sits at its order in lane B's chain, and works with B2's mapper on | judge F3, N3 | `StratumClientGroupsTest.decoratorsInOrder`, `ClientCredentialsIntegrationTest.ResendOn401.withErrorMapping` |
| relay sends only a verified access token; none, ID token or other thread: `SX-HTTPC-0004`, nothing sent; 401 passed back | ADR 3.2, 3.3, A9 | `TokenRelayIntegrationTest` |
| missing registration / wrong grant: `SX-HTTPC-1005` | this note | `StartupChecksTest.unknownRegistrationStops`, `.wrongGrantStops` |
| missing dependency: `SX-HTTPC-1006` | ADR 3.2 | `RestClientAutoConfigurationTest` (`FilteredClassLoader`) |
| no network at startup with `token-uri` | A13 | `ClientCredentialsIntegrationTest.startsWithProviderDown` |
| oauth2-client starter brings no lock-down | owner card | `OAuth2ClientStarterIntegrationTest.noSecurityFilterChain` |
| no `auth` set: nothing sent, nothing loaded | this note | `NoAuthIntegrationTest.sendsNoAuthorization` |

### Documents
- `docs/modules/http-client.md`: the new keys in the table (Javadoc says the same), "Configure in Java", the planned
  list shortened. Auth gets its own page, `docs/modules/http-client-auth.md`, linked from `docs/README.md`
  (http-client.md is 306 lines; ST-DOC-09), every example in both file formats.
- `docs/modules/oauth2-client.md`: rewritten per the owner's card (no `stratum.oauth2-client.*` keys).
- `docs/guides/error-codes.md`: 1005, 1006, 0004, and 0002's token case. `ConfigurationMetadataTest` gains the keys.

## Round 1 findings (verdict `reviews/judge/A1-auth-note.md`)
- **F1, accepted:** `ssl-bundle` not allowed in `defaults`; the token fetch uses its own pool with default TLS.
- **F2, accepted:** waiters share the in-flight fetch, bounded by the time left.
- **F3, accepted:** `ServiceTokenResend` is a `ClientExchangeDecorator` bean at lane B's order.
- **F4, accepted:** a failed token fetch is `SX-HTTPC-0002`, not retried, not counted, one WARN a minute.
- **F5, accepted:** pointer under "Not in A1"; per the owner's 13:38Z choice, `ServiceTokenResend.call` also serves
  lane C's `resilience(name).call(...)` (test `ClientCredentialsIntegrationTest.ResendOn401.plainCallThroughResilience`).
- **M1, accepted:** answers 1 to 3 shortened and completed. **N1, N2, N3** taken as written above.

## Not verified
- JDK 25 (the build runs it).
- Boot 4.1.2 with `issuer-uri` calling the provider at startup.
- HC5 `TlsSocketStrategy` swap under load (the reload test checks one new connection).