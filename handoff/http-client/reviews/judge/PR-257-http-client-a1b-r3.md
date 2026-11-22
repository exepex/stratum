# Judge verdict: PR #257 (A1b, service token and token relay), round 3

PR #257, branch `claude/project-thread-8ivbzo`, head `85c14ef`, base `main` at `c8b6d6a`, track Feature, review step:
code, round 3. Earlier verdicts: `PR-257-http-client-a1b.md` (gate), `-r1.md` (Opus, FAIL on 6 findings), `-r2.md`
(Sonnet, FAIL on the gate only). This judge ran on a third model (Fable).

**PASS.** No open finding.

## Gate

- Open, ready for review, not a draft. GitHub: `mergeable_state: clean`; `git merge-tree --write-tree origin/main 85c14ef`
  exits 0. The branch is rebased on `c8b6d6a` (its merge base with `main` is `c8b6d6a`).
- CI at `85c14ef` green: Verify and SBOM, CodeQL (java-kotlin and actions), SonarCloud quality gate, wiki check.
- Codex: all 10 threads resolved; its state at `85c14ef` is `has_unresolved_findings: false`.
- Sonar: 1 new issue. Sonar's API for PR 257 lists exactly one: `java:S1118` on
  `RestClientTokenAutoConfiguration.java:46`. That is the one answered in the Claims and reproduced by the round-1
  judge (a private constructor fails PMD). Accepted.
- Claims complete: request and track, requirements with evidence, design note (PASS r2), deviations, broken-code
  proof, self-review, out of scope, files read, line counts.
- Line counts (ST-AG-10): every changed file counted; the largest are `docs/modules/http-client.md` 486 and
  `README.md` 459 (limit 500), `HttpClientPropertiesTest.java` 383 and `ClientResilience.java` 334 (limit 400). Every
  changed file ends with a newline.

## What changed since round 2 (`62e8f5d`)

- The rebase: the interdiff of `abeaa2a..62e8f5d` against `c8b6d6a..0617522` differs only in context lines that #265
  added (the 404 → `Optional` row in `http-client-errors.md`, the `OptionalNotFound` import and bean in
  `RestClientAutoConfiguration`, the Planned list). No line of this PR's own change moved.
- `85c14ef` (Codex P1, ST-TEST-10): the `TimeLeft` group left `ClientConnectionPoolsTest`, which now opens no socket;
  `it/DownloadTokenIntegrationTest` makes a real streamed `FilesClient.download()` with `timeout=30s`,
  `total-timeout=300ms` and a holding identity provider, and expects `SX-HTTPC-0002` within 1.5 s with nothing sent.
  `RestClientTestData.pointRegistrationAtTokenServer` is split out and shared (3 hits).
- Merged result with #265: `ClientErrors` stays outermost, `OptionalNotFound` reads 404 only, the resend inside
  `ClientResilience.call` still sees Spring's 401. `second401Thrown` and `refusedTokenPassedBack` pass on the merged
  code.

## Round-1 fixes, checked again at the rebased head

| Round 1 finding | Status |
| --- | --- |
| F1 servlet service does not start | Holds. Plant J1 (filter not registered) fails `ClientTokenWebIntegrationTest` with the round-1 message "required a bean of type HttpSecurity". |
| F2 token expiry untested | Holds. Plant J2 (`isValidAfter` always true) fails `Expiry.fetchesTokenAboutToExpire` (expected `t-1`, got `about-to-expire`). |
| F3 0004 → 500 untested | Holds. `RestClientEnvironmentPostProcessorTest.defaultStatuses` asserts all four codes with no team value (read; passed in my build). |
| F4 docs promised a resend on `restClient(name)` | Holds. `http-client-auth.md` says such a call gets no second try until `resilience(name)`, linked to Planned, which lists `resilience(name)`. |
| F5 loops, `Duration` | Holds. Streams or a reason comment on each loop left; `IssuingTokenStore.SHORT_LIFETIME` used. |
| F6 1005/1006 row order | Holds. Rows in code order in `error-codes.md`. |

## Gap list

- `auth.client-credentials` sends the service token on interface and `restClient(name)` calls: **MET**
  (`sendsServiceToken`, `adHocCallSendsToken` passed).
- One fetch at a time: **MET** (`AtOnce.oneFetchForManyCalls`: 20 calls, 1 token request).
- Token kept until 60 s before expiry: **MET** (`Expiry.*`; plant J2 caught).
- Refused client → `SX-HTTPC-0002`, unsent, unretried, not a breaker failure, nothing internal in the message:
  **MET** (`Refused.refusedTokenStopsCall`).
- Held identity provider → every waiting call fails within the time left after one fetch: **MET** (`Held`).
- Streamed download's token request ends by the call's time left: **MET** (`DownloadTokenIntegrationTest`; plant J3
  caught: with the token pool keeping the answer timeout the test fails after 10.6 s against 1.5 s).
- Token fetched before the attempt's request, and that token sent: **MET** (`ServiceTokenResendTest.Fetch.*`).
- One resend after a 401, second 401 thrown as `DownstreamException` 401: **MET** (`RefusedOnce`, `RefusedAlways`).
- Renewal drops only the refused token and runs as one fetch: **MET** (`Renew.*`).
- Token pool without `ssl-bundle`: **MET** (`tokenPoolIgnoresBundle`).
- Token relay sends only a resource-server-verified token; `SX-HTTPC-0004` otherwise; 401 passed back: **MET**
  (`TokenRelayIntegrationTest`; plant J6 caught).
- `SX-HTTPC-1005`, `SX-HTTPC-1006`, two kinds stop startup: **MET** (`StartupChecksTest.TokenAuth`, `ClientAuthTest.Token`;
  plant J4 caught).
- Both keys from properties, YAML and Java, with IDE metadata: **MET** (`tokenAuthFromBothFormats`,
  `ConfigurationMetadataTest`).
- 0002 → 503, 0004 → 500 defaults; team value wins: **MET** (`defaultStatuses`, `addsCallCodeStatuses`).
- `stratum-starter-oauth2-client` without web lock-down; a servlet service starts and answers 200: **MET**
  (`ClientTokenStarterIntegrationTest`, `ClientTokenWebIntegrationTest`; plant J1 caught).
- Owner's choices of 15:19Z (no `auth`/`ssl-bundle` under `defaults`, fetch failure = 0002/503, relay needs the
  resource server, one fetch at a time): **MET**.
- Docs: **MET**. Each changed page read against the code; both formats in every example; `oauth2-client.md` has no
  `stratum.*` key, so needs no Configure in Java; `docs/README.md`, `README.md`, `reference.md` consistent (the
  `stratum.oauth2-client` keys are gone everywhere: 0 hits).

## Build

JDK 25 (`openjdk-25-jdk-headless`), `./mvnw -pl libs/stratum-http-client,starters/stratum-starter-oauth2-client,starters/stratum-starter-http-client -am install`:
BUILD SUCCESS, 3:22 min. Test cases from the XML reports: lib 33, OAuth2 client starter 5, HTTP client starter 226,
0 failures. Spotless, Checkstyle, PMD, SpotBugs, Enforcer and the JaCoCo gate all passed.

## Rules

| Rule | Result |
| --- | --- |
| ST-ENG-42/43 Lombok, builders | `@RequiredArgsConstructor`, `@Slf4j`; `ClientAuth` built with its builder; records for values. |
| ST-ENG-46/47 lambdas, statics | No block lambda; statics only in `@UtilityClass` `StratumClientChecks` and the JUnit-required ones. |
| ST-ENG-56/57 API, cycles | Every new public type `@StratumInternal`, `TokenInitializers` included; all in `restclient`; ArchUnit tests passed. |
| ST-ENG-14/15 Javadoc | On every member; short and functional. |
| ST-ENG-20/21/22 beans | `@Fallback`, `@ConditionalOnMissingBean`, `@ConditionalOnClass`, module switch, `@AutoConfigureOrder`, `afterName` on Boot's OAuth2 client. |
| ST-TEST-10/15/17/20/30/31 | Unit tests open no socket now; `@Nested` per behaviour; `FilteredClassLoader` for each missing library; ITs with `@SpringBootTest`. |
| ST-SEC-01/02/06 | Only the placeholder secret `not-a-real-secret-4f2a`; warnings log client, registration and the provider's error code, never a token; relay only with `token-relay: true` and only a verified token. |
| ST-DOC-01/04/05/08 | Property rows, error rows, both formats, `resilience(name)` labelled planned. |

## Planted faults

6 planted, 5 caught, 1 survived with no finding. Every file restored (`git status` clean).

- J1, `LoginChainImportFilter` not in `spring.factories`: caught by `ClientTokenWebIntegrationTest`.
- J2, a token never expires: caught by `SingleFetchTokenManagerTest.Expiry.fetchesTokenAboutToExpire`.
- J3, the token pool keeps a download's answer timeout: caught by `DownloadTokenIntegrationTest` (10.6 s > 1.5 s).
- J4, `token-relay: false` counts as a kind: caught by `ClientAuthTest.Token.relayOffIsNoKind`.
- **J5, the relay library check removed from `StratumClientChecks.checkAuthLibrary`: survived.**
  `StartupChecksTest` (17 tests) still passes, because `StratumClientBuilders.authInitializer` throws the same
  `SX-HTTPC-1006` when no `TokenInitializers` bean serves the kind. A service sees the same error either way, so this
  is not a finding; see the note.
- J6, relay sends any `Authentication`'s principal: caught by `TokenRelayIntegrationTest.NoCallerToken.otherAuthentication`.

## Notes

- **The early library check matters only for a client with no interface.** `restClient(name)` builds its client on
  the first call (`DefaultStratumHttpClients.build`), so for a client used only that way, the check in
  `RestClientAutoConfiguration`'s constructor is what turns a missing library into a startup failure instead of a
  failure on the first call. No test covers that client; the check is small and the behaviour is right, so a test is
  worth adding only if that case comes up.
- **A team status handler that swallows 401** (the design note says the auth page mentions that it turns the resend
  off) is not on the page. A team would have to replace Spring's default status handler on the builder through a
  customizer; no realistic setup is known, so this stays a note.
- **The Codex renewal race** answered as theoretical, and **Sonar S1118**: both accepted in round 1 with the judge's
  own reproduction; nothing in the rebase changes them.
- **WebFlux** is not covered by this blocking starter, as the Claims say.

Judge: independent code judge, Fable 5.1 (configured `claude-fable-5-1`; the serving model may differ). I read
AGENTS.md, all six topic files, the A1 design note, the three earlier verdicts, every changed file and the merged
result with #265. Nothing was pushed, merged or changed by this review.
