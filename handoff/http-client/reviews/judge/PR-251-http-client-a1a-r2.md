# Judge verdict: PR #251, HTTP client auth A1a, round 2

**PR #251, branch `claude/project-thread-8ivbzo`, head `466885f76b7fccb1864f24e8546004bb9aed1b6d`, Feature track,
code review, round 2.**

**Verdict: PASS on `466885f`.** No open finding of any severity.

Judge: Fable 5.1, a fresh agent with no part in building the change and no access to the implementer's
conversation. I read `AGENTS.md` and the six topic files in `docs/agents/`, the A1 design note, both note verdicts
and the round-1 code verdict (`PR-251-http-client-a1a.md`, Opus).

## Gate

| Check | Result |
|---|---|
| Claims complete, with the design note | Yes; line counts checked against `wc -l`, all match |
| Open, ready for review | Yes, not a draft |
| CI at head | Verify and SBOM, CodeQL (2 jobs), wiki check: all success at 466885f (15:12 to 15:19Z) |
| Review bots | Sonar quality gate passed, 0 issues; Codex code review at 466885f: no findings; all 4 Codex P1 threads answered and resolved |

## Round-1 findings

| Finding | Fix | Judge's check |
|---|---|---|
| 1 Should fix: host name not proven checked | `SslBundleIntegrationTest$OtherName.otherHostNameRefused`: bank certificate `dns:bank.test`, called at `https://127.0.0.1` | Accepted. With `HostnameVerificationPolicy.CLIENT` + `NoopHostnameVerifier` planted, the test fails ("Expecting code to raise a throwable"); with `NoopHostnameVerifier` alone it passes, which is correct: HC5 5.6.5's 5-argument `DefaultClientTlsStrategy` constructor passes a null policy (bytecode: `aconst_null`), and `AbstractClientTlsStrategy` turns null into `HostnameVerificationPolicy.BOTH`, so the JDK endpoint check still runs |
| 2 Minor: `BasicCredentials.toString` untested | `ClientAuthTest.toStringHidesSecrets` asserts `auth.basic().toString()` | Accepted. Planted `username + ":" + password`: the test fails |
| 3 Minor: two Claims lines | Deviation line removed; CI and bot results at head listed | Accepted, read in the PR body |

## Gap list (own list from ADR 0011 3.1 and the A1 note, A1a part)

| Requirement | Status | Evidence |
|---|---|---|
| `auth` holds one of `api-key` (+ `api-key-header`, default `X-Api-Key`), `bearer`, `basic` | MET | `ClientAuth.java:28-59`, `HttpClientProperties.java:181-207`; `InvalidSettings` tests, 21 of 21 pass |
| Credential by a `ClientHttpRequestInitializer`, no interceptor | MET | `StratumClientBuilders.java:46-48`, `StaticCredentialInitializer.java:30` |
| Sent on every call of the interfaces and of `restClient(name)`, other clients unchanged | MET | `StaticCredentialIntegrationTest`, 5 of 5 pass, incl. `apiKeyOnAdHocCall`; each group asserts `""` for the pricing client |
| No `auth` or `ssl-bundle` under `defaults`; no bundle on the shared pool, own or inherited | MET | three `@AssertTrue` rules; planted `return true` in `isSharedPoolWithoutSslBundle`: `sslBundleOnSharedPool` and `sslBundleOnSharedPoolFromDefaults` fail |
| Secrets never in `toString` or startup errors | MET | `ClientAuth.toString`, `BasicCredentials.toString`; message asserted exactly in `assertSettingsInvalid` |
| mTLS from a bundle, per client pool, certificate and host checked | MET | `SslBundleTlsStrategy.java:60-68`, `ClientConnectionPools.java:114-116`; `sendsClientCertificate`, `otherHostNameRefused` |
| Bundle reload: new connections present the renewed certificate | MET | planted a no-op update handler: `reloadedCertificateUsed` fails (Awaitility 10 s) |
| Unknown bundle name stops startup (doc promise) | MET | my probe `SpringApplicationBuilder` run with `ssl-bundle=nowhere` fails with root cause `NoSuchSslBundleException`; probe removed afterwards |
| Every key from files and Java, Java wins; IDE metadata | MET | `authFromProperties`, `basicFromYaml`, `javaSetsAuth`, `ConfigurationMetadataTest` |
| Docs, both formats, Java section, index | MET | `http-client-auth.md` (168 lines), key table in `http-client.md`, `docs/README.md`, `reference.md` comments; planned items labelled (`auth.client-credentials`, `auth.token-relay`) |
| A1b, A2, #238 out of scope | Declared | Claims "Out of scope" |
| Nothing built that was not asked | MET | diff is 29 files, all for A1a |

The three code-judge notes of `A1-auth-note-r2.md` are A1b (token fetch, breaker, `SX-HTTPC-1005`); not applicable here.

## Rules

| Rule | Result |
|---|---|
| ST-ENG-42/43 | `@Builder(toBuilder = true)` on `ClientAuth` (4 fields); `BasicCredentials` has 2 fields, no builder needed; `@RequiredArgsConstructor`, `@UtilityClass` used; hand-written `toString` only on the two secret-holding records, which PMD's rule does not cover for records and ST-ENG-42 requires (no `@ToString` on a secret) |
| ST-ENG-46/47 | Every lambda is one expression; no static logic outside the utility class; IT helpers are instance methods (2b7cf4d) |
| ST-ENG-56/57 | API list gains `ClientAuth`, its builder, `BasicCredentials`; starter types `@StratumInternal`; arch tests pass |
| ST-ENG-14/15 | Javadoc on every type, method and field; brief |
| ST-TEST-12/13/15/16/17/31 | `@DisplayName` everywhere; `@Nested` per behaviour (466885f); ITs under `@SpringBootTest` (6252b98); `RestClientTestData.ownPools` replaces two copies |
| ST-SEC-01/02/03 | Keys and random password made by `keytool` at test time; no credential in the repository (`not-a-real-secret-4f2a` is a placeholder); no logging in either module (`grep` for `log.`/`@Slf4j`: 0 hits in main) |
| ST-SEC-11 | Verifier and JDK endpoint check both on, proven by the plant |
| ST-AG-10 | Largest changed files: `http-client.md` 366, `ScriptedDownstream` 335, `HttpClientPropertiesTest` 322; all under the limits; every file ends with a newline |
| ST-DOC-05/08 | Every example in properties and YAML; "Configure in Java" present; planned parts labelled |

## Planted faults

6 planted, 5 caught, 1 survived as expected.

| Fault | Caught by |
|---|---|
| `NoopHostnameVerifier` alone (round-1 plant) | survives: JDK endpoint check still refuses the misnamed bank (see above); not a gap |
| `HostnameVerificationPolicy.CLIENT` + `NoopHostnameVerifier` | `otherHostNameRefused` |
| `BasicCredentials.toString` returns `username:password` | `toStringHidesSecrets` |
| Basic header sent as `Basic <username>`, unencoded | `StaticCredentialIntegrationTest$Basic.basic` |
| `isSharedPoolWithoutSslBundle` returns `true` | `sslBundleOnSharedPool`, `sslBundleOnSharedPoolFromDefaults` |
| Bundle update handler does nothing | `reloadedCertificateUsed` |

Baseline at 466885f before planting: lib unit tests 26 of 26, `SslBundleIntegrationTest` 4 of 4,
`StaticCredentialIntegrationTest` 5 of 5 (`./mvnw -pl libs/stratum-http-client,starters/stratum-starter-http-client
verify -Dit.test=...`, JDK 25, static checks skipped locally; CI ran them).

## Notes (not findings)

- `RestClientTestData.SECRET` and `HttpClientTestData.SECRET` hold the same placeholder in two modules. The starter
  has no dependency on the library's test classes, so a shared constant would need a test jar; not worth it.
- An empty placeholder (`${GEO_API_KEY:}`) binds as `""` and sends an empty header; the downstream's 401 makes it
  loud at the first call (carried over from round 1).
- Surefire's `.txt` reports show "Tests run: 0" for classes whose tests are all in `@Nested` groups with
  `@DisplayName`; the XML reports hold the real counts. Nothing to fix in the PR.

## Not verified

- The full `mvn verify` locally (the two modules' tests ran here; CI ran the rest at the head).
- The HC5 strategy swap under load, as the Claims say.
