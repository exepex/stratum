# Judge verdict: PR #251, HTTP client auth A1a (API key, bearer, Basic, ssl-bundle)

**Verdict: FAIL on head `981dc01522a428d9475259cc3cd8ae8e41eafa38`** (1 should fix, 2 minor). The design is right
and the code is small and clean; the gaps are two tests that do not catch a broken security property, and two
lines of the Claims.

Judge: Opus 5.5, fresh agent, no part in building the change. I read AGENTS.md and the six topic files in
`docs/agents/`, the A1 design note, both A1 note verdicts and ADR 0011.

## Gate

| Check | Result |
|---|---|
| Claims and design note present | Yes (note passed r2, Fable) |
| Open ready for review, not a draft | Yes |
| CI at head | Green at 981dc01: Verify and SBOM (success 14:18Z), CodeQL, Analyze, wiki check |
| Review bots | Sonar quality gate passed (0 issues). Codex P1 (bundle let through when `defaults.shared-pool=true`) answered and resolved; fix in 981dc01 checked below. |

The first head, 851d515, had the Codex P1 bug. I reproduced it on my own before the fix landed: a probe test with
`defaults.shared-pool=true` plus `clients.inventory.ssl-bundle=bank` gave "Expecting code to raise a throwable"
(validation passed), and `ClientConnectionPools.poolFor` then picks the shared pool built from the defaults, so the
bundle was silently dropped. 981dc01 validates each client's merged settings (`clientSettings(name)`); the new test
`sslBundleOnSharedPoolFromDefaults` is the same case as my probe, and `./mvnw -pl libs/stratum-http-client install`
passes at the new head. Sibling case (client sets `shared-pool: false` under shared defaults) is allowed, as the doc
now says. Accepted.

## Requirements (own list, from ADR 0011 3.1/2.4 and the A1 note, A1a part)

| Requirement | Status | Evidence |
|---|---|---|
| `auth` holds exactly one of api-key (+ header, default `X-Api-Key`), bearer, basic | MET | `ClientAuth.hasOneKind`, compact constructor; `HttpClientProperties.isAuthOneKind/isBasicComplete/isApiKeyHeaderWithApiKey`; `InvalidSettings` tests |
| Credential set by a `ClientHttpRequestInitializer`, not an interceptor (no body buffering) | MET | `StratumClientGroups.configureClient` adds `builder.requestInitializer`; `StaticCredentialInitializer` sets one header |
| Header on every call, other clients unchanged | MET | `StaticCredentialIntegrationTest` (two calls + an un-authed client, `containsExactly(..., "")`) |
| No `auth` / `ssl-bundle` under `defaults`; no bundle on the shared pool | MET at 981dc01 | three `@AssertTrue` rules; tests incl. `sslBundleOnSharedPoolFromDefaults` |
| Secrets never in `toString` or startup errors | MET in code, test partly hollow (finding 2) | `ClientAuth.toString`, `BasicCredentials.toString`; messages asserted exactly |
| mTLS from a Spring SSL bundle, per client pool, TLS checks on | MET in code, host check untested (finding 1) | `SslBundleTlsStrategy` uses `DefaultClientTlsStrategy` + `HttpsSupport.getDefaultHostnameVerifier()`; HC5 builder uses a set strategy over `useSystemProperties` (r1 note verdict, bytecode) and `sendsClientCertificate` proves the bundle is used |
| Bundle reload: new connections use the renewed certificate | MET | `addBundleUpdateHandler` swaps an `AtomicReference`; `reloadedCertificateUsed` |
| Unknown bundle name fails at startup | MET (by reading) | `SslBundles.getBundle` throws `NoSuchSslBundleException` while the group is configured at startup |
| Every key from files and Java, IDE metadata | MET | `javaSetsAuth`, `authFromProperties`, `basicFromYaml`, `ConfigurationMetadataTest` |
| Docs updated, both formats | MET | `http-client-auth.md` (properties + YAML + Java), key table, planned list, `docs/README.md`, reference comments |
| A1b, A2, #238 out of scope | Declared | Claims "Out of scope" |

The three code-judge notes in `A1-auth-note-r2.md` (fetch bounded by time left, breaker ignores SX-HTTPC-0002/0004,
SX-HTTPC-1005 with no repository) are all A1b; not applicable here. Note 4 (`basic` is an object) is in the doc.

## Findings

### 1. Should fix: nothing proves the other service's host name is checked (ST-SEC-11, ST-TEST-01)

The Claims list "with TLS checks on" as met. I replaced `HttpsSupport.getDefaultHostnameVerifier()` with
`NoopHostnameVerifier.INSTANCE` in `SslBundleTlsStrategy.strategyOf` and ran
`mvn -pl starters/stratum-starter-http-client verify -Dit.test=SslBundleIntegrationTest`: **3 of 3 pass**. The test
server's certificate has `SAN=ip:127.0.0.1` (`GeneratedKeyStores:82-83`) and the client calls `https://127.0.0.1`,
so a broken host check is never seen. ST-SEC-11 says hostname verification is never turned off, and this strategy is
the one place Stratum builds its own TLS for every mTLS client in every service.

Fix: one test in `SslBundleIntegrationTest` that calls the bank by a name its certificate does not hold (for example
`https://localhost:<port>`, the cert being for `ip:127.0.0.1` only) and expects the call to fail. That test catches
the planted fault.

### 2. Minor: `ClientAuthTest.toStringHidesSecrets` does not test `BasicCredentials.toString`

Its display name says "its text and that of Basic credentials name the kind only", and the Claims cite it for
`BasicCredentials.toString`. I changed `BasicCredentials.toString` to return `username + ":" + password`;
`ClientAuthTest` still passes 2 of 2, because `ClientAuth.toString` never calls it. Fix: add
`assertThat(auth.basic().toString()).doesNotContain(HttpClientTestData.SECRET)` to the same test.

### 3. Minor: two lines of the Claims are wrong

- "Deviation: `BasicCredentials` is a two-field record without a builder (ST-ENG-42 asks for a builder from three
  fields)." ST-ENG-42 (`engineering.md:237-239`) asks for `@Builder` on records with three or more fields, so a
  two-field record is within the rule. Remove the deviation.
- "CI and bot results will be added once they finish." ST-AG-17 asks for the Claims updated with CI and both bots
  at the head before asking for the judge. Add them (and the Codex fix) for the next head.

## ST-ENG code rules

| Rule | Result |
|---|---|
| ST-ENG-42 Lombok | `@Builder(toBuilder = true)` on `ClientAuth`, `@RequiredArgsConstructor` on `StaticCredentialInitializer`, `@UtilityClass` on `ClientAuthInitializers`. Hand-written `toString` on the two secret-holding records is needed (records print every field); PMD's `UseLombokToString` targets classes only. |
| ST-ENG-43 builders | `ClientAuth.builder()` used everywhere; `new BasicCredentials(..)` is fine (no builder). |
| ST-ENG-46 lambdas | All single expressions or method references. |
| ST-ENG-47 static logic | Only in the `@UtilityClass`. |
| ST-ENG-16 one job | Each new class has one job; `ClientAuthInitializers` (52 lines) picks the initializer, used once. Not a tiny class worth merging. |
| ST-ENG-56 API | `ClientAuth`, its builder and `BasicCredentials` are API (arch test updated); starter types are `@StratumInternal`. |
| ST-ENG-14/15 Javadoc | Every class, method and property documented, briefly. |
| ST-ENG-18 / ST-TEST-16 shared helpers | `RestClientTestData.ownPools` replaces two copies; `GeneratedKeyStores` and `TlsDownstream` in `testsupport`. |
| ST-ENG-31/34 keys | Validation through `@AssertTrue`, message names only the rule; Java customizer wins (`javaSetsAuth`). |
| ST-SEC-01 | No key or password in the repo: stores made with `keytool` at test time, random password. The YAML fixture's `not-a-real-secret-4f2a` is a test placeholder, not a credential. |
| ST-AG-10 line counts | Largest: `ScriptedDownstream` 335, `http-client.md` 311, `HttpClientPropertiesTest` 283. All within limits. |

## Fault plants

| Fault | Caught? |
|---|---|
| Shared-pool check reads raw client settings (the 851d515 code) with `defaults.shared-pool=true` | Yes at 981dc01 (`sslBundleOnSharedPoolFromDefaults`); not at 851d515 |
| Hostname verifier replaced with `NoopHostnameVerifier` | **No** (finding 1) |
| `BasicCredentials.toString` prints both values | **No** (finding 2) |
| Implementer's three (credential value, reload handler, pool strategy) | Accepted from the Claims, not re-run |

## Notes (not findings)

- An empty placeholder such as `${GEO_API_KEY:}` binds as `""`, passes `auth-one-kind` and sends an empty header;
  the downstream answers 401. Loud at the first call, so not a finding.
- If a call also sets `Authorization` itself (for example `@RequestHeader`), Spring adds that header after the
  initializers run, so two values may go out (read from memory of Spring's `DefaultRestClient`, not verified). No Stratum client does that today; theoretical.
- HC5 strategy swap under load is unverified, as the Claims say.

## Not verified

- The full `mvn verify` locally (I ran the http-client lib install with tests, and the starter's
  `SslBundleIntegrationTest` and `RestClientAutoConfigurationTest`); CI covers the rest.
