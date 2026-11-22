# Judge verdict: PR #257 (A1b, service token and token relay), round 1

PR #257, branch `claude/project-thread-8ivbzo`, head `1bb3b01`, base `abeaa2a`, track Feature, review step: code,
round 1. The earlier gate verdict is `PR-257-http-client-a1b.md`.

**FAIL**: 1 blocking finding, 3 should-fix findings and 2 minor findings.

## Gate

- The PR is open and ready for review. GitHub reports it `mergeable_state: clean`, and
  `git merge-tree origin/main <head>` exits 0.
- CI at `1bb3b01` is green: Verify and SBOM, CodeQL, the wiki check and Sonar's quality gate all passed.
- All 9 Codex threads are resolved. Codex's code review at `1bb3b01` has no unresolved findings.
- The Claims now include the rebase, the "Files read" line and the line counts.

## Gap list

- `auth.client-credentials` sends the service token on interface calls and on `restClient(name)`: **MET**. The ITs
  passed: `sendsServiceToken` and `adHocCallSendsToken`.
- One fetch at a time: **MET**. `AtOnce` sends 20 calls and the identity provider sees 1 token request; planted
  fault P2 was caught.
- Token kept until 60 s before expiry: **PARTLY**. The code does it (`SingleFetchTokenManager:215-224`), but no test
  covers expiry (Finding 2).
- A refused client fails with `SX-HTTPC-0002`; the call is not sent, not retried and not counted by the breaker:
  **MET** (`Refused`).
- An identity provider that does not answer: **MET**. `Held` passes, and planted fault P5 was caught.
- The token is fetched before the request is created, and that same token is sent: **MET** (`Fetch.*`; planted
  fault P2 was caught).
- After a 401, one resend with a new token, and a second 401 is thrown as `DownstreamException` 401: **MET**
  (planted fault P1 was caught).
- A renewal keeps a newer token and runs as one fetch: **MET** (planted fault P3 was caught).
- The token pool has no `ssl-bundle`, and its timeout ends within the time the call has left: **MET** (planted
  fault P5 was caught).
- Token relay, `SX-HTTPC-0004`, and a 401 passed back without a resend: **MET** (`TokenRelayIntegrationTest`
  passed).
- `SX-HTTPC-1005` and `SX-HTTPC-1006`, and two auth kinds stopping startup: **MET** (`StartupChecksTest`; planted
  fault P6 was caught).
- Both keys work from properties, YAML and Java, with IDE metadata: **MET** (`HttpClientPropertiesTest` and
  `ConfigurationMetadataTest` passed).
- `SX-HTTPC-0002` → 503 and `SX-HTTPC-0004` → 500 defaults: **PARTLY**. 0004's 500 is not tested (Finding 3).
- `stratum-starter-oauth2-client` adds no web lock-down: **NOT MET** for a servlet service (Finding 1).
- Owner's choices of 15:19Z (no `auth` or `ssl-bundle` under `defaults`, fetch failure is 0002 with 503, relay needs
  the resource server, one fetch at a time): **MET**.
- Docs: **PARTLY** (Findings 4 and 6).

My build: `./mvnw -pl libs/stratum-http-client,starters/stratum-starter-oauth2-client,starters/stratum-starter-http-client -am install`
on JDK 25 exits 0. It ran 212 tests in the HTTP client starter, 2 in the OAuth2 client starter and 33 in the HTTP
client lib, with 0 failures.

## Findings

1. **Blocking: ST-AG-02, ST-TEST-31 and ST-DOC-01.** A servlet service that adds `stratum-starter-oauth2-client`
   and a registration does not start.
   - **Probe:** a Boot 4.1.2 app with `spring-boot-starter-webmvc`, `stratum-starter-oauth2-client`,
     `stratum-starter-http-client`, the docs' `invoices` registration and one `@GetMapping`.
   - **Result:** "APPLICATION FAILED TO START: Parameter 0 of method oauth2SecurityFilterChain in
     OAuth2ClientWebSecurityAutoConfiguration$OAuth2SecurityFilterChainConfiguration required a bean of type
     HttpSecurity that could not be found."
   - **Cause:** that auto-configuration needs only `EnableWebSecurity` (from the `spring-security-config` this PR
     adds), `OAuth2AuthorizedClientRepository` and a servlet app. Its nested condition
     `@ConditionalOnDefaultServletWebSecurity` lives in the excluded `spring-boot-security`, so the condition is
     lost, and nothing creates `HttpSecurity`.
   - **Why the tests missed it:** `ClientTokenStarterIntegrationTest` starts a non-web app.
   - **Docs affected:** `oauth2-client.md` says "Adding the starter protects no endpoint" for the case that now
     fails.
   - **Fix:** make a servlet service with the starter start, with no filter chain and no login. Add an IT with a
     servlet web application that calls an endpoint and gets 200. If the fix changes the starter's dependencies or
     excludes a Boot auto-configuration, say so in the Claims. That is a dependency change, so it may need the
     owner (ST-AG-12).
2. **Should fix: ST-TEST-01 and ST-TEST-02.** The token's expiry is not tested.
   - **Planted fault P4:** `isValidAfter` made to always return `true`, so a token never expires. All unit tests and
     `ClientCredentialsIntegrationTest` still pass.
   - **What a real service would see:** an expired token is sent until a 401 renews it. A `restClient(name)` call
     never renews it.
   - **Claims:** the Claims row cites code and `sendsServiceToken`, which issues one hour-long token.
   - **Fix:** a unit test with a token inside the 60 s skew, expecting a new fetch.
3. **Should fix: ST-TEST-01 and ST-AG-01.** Nothing checks that `SX-HTTPC-0004` maps to 500.
   - **Planted fault P7:** 0004 mapped to `BAD_GATEWAY` in `RestClientEnvironmentPostProcessor`. No test fails.
   - **Claims:** the Claims cite `addsCallCodeStatuses` for it. That test sets 0004 to `418` itself, and checks only
     that the team's value wins and that 0002 is 503.
   - **Fix:** assert 0004 → `500` with no team value.
4. **Should fix: ST-DOC-08 and ST-DOC-01.** `http-client-auth.md:161-163` promises a resend that `restClient(name)`
   calls never get.
   - **The doc says:** "A 401 gets one more try. When the other service refuses the token … the call is sent once
     more with a new one." Lines 9-10 say the credential goes with `restClient("<name>")` calls too.
   - **The code:** that `RestClient` has no `ClientResilience` (`DefaultStratumHttpClients.build`), so it gets no
     resend and the refused token is never dropped. The design note (lines 11-14) records the owner's choice that
     such calls get the resend through the planned `resilience(name)`.
   - **Fix:** state on the page that `restClient(name)` calls get no second try until `resilience(name)`, and link
     the Planned list.
5. **Minor: ST-ENG-39 and ST-TEST-41.** Five new loops in test code have no comment.
   - The loops: `ClientCredentialsIntegrationTest` (3: `sendsServiceToken`, `oneFetchForManyCalls`,
     `heldTokenEndpointFailsFast`), `RestClientTestData.pointClientsAtTokenServer` and
     `TokenDownstream.refuseIssuedTokens`.
   - Separately, `SingleFetchTokenManagerTest:14` writes `java.time.Duration` in full instead of importing it.
   - **Fix:** use streams, or keep a loop with its reason, such as the checked `Future.get`. Import `Duration`.
6. **Minor: clarity.** In `docs/guides/error-codes.md`, the new `SX-HTTPC-1005` and `1006` rows sit after `1008`.
   **Fix:** put them in code order.

## Planted faults

7 planted, 5 caught, 2 survived.

- P1, no 401 resend: caught by `newTokenAfter401`, `second401Thrown` and `replacesTokenSent`.
- P2, the attempt's token ignored: caught by `sendsFetchedToken`.
- P3, the drop not conditional on the refused token: caught by `keepsNewerToken` and `replacesTokenSent`.
- **P4, a token never expires: survived** (Finding 2).
- P5, the token pool keeps a download's answer timeout: caught by `tokenRequestEndsDuringDownload`.
- P6, relay not counted as a kind: caught by `serviceAndCallerTokenAreTwoKinds` and `tokenAuthFromBothFormats`.
- **P7, 0004 status changed: survived** (Finding 3).

Every file was restored afterwards: `git status` is clean.

## Notes

- **The Codex P2 renewal race, answered as theoretical under ST-AG-04: accepted.**
  - **How it would happen:** X must read token B in the window between another fetch storing B and that fetch
    completing and removing its future (`SingleFetchTokenManager:177-181`). That window is CPU-only, with no I/O.
    X must then finish a full HTTP round trip and call `renew(B)` while the future is still registered. That needs
    the fetching thread to be descheduled for longer than a network round trip between two adjacent statements.
  - **Why the outcome is the same:** a token issued moments ago is refused only when the service refuses the client
    outright. Then a third token is refused too. A JWKS-based verifier usually refetches its keys on an unknown
    `kid`, so a key rotation is unlikely to refuse B and accept C. I did not verify this for every verifier.
  - **Result:** the same as the author says, one resend and then a 401.
- **Sonar S1118: accepted.** I added `private RestClientTokenAutoConfiguration() {}` and ran
  `./mvnw -o -pl starters/stratum-starter-http-client compile pmd:check`. PMD's
  `MissingStaticMethodInNonInstantiatableClass` fails the build (BUILD FAILURE). I restored the file afterwards.
  One part of the answer is wrong, though it does not change the conclusion: `StratumCacheAutoConfiguration` is an
  empty class, not a holder of nested configurations.
- **Short tokens:** a token that lives 60 s or less is fetched on every attempt. Spring Security's own provider uses
  the same 60 s skew.
- **WebFlux:** a WebFlux application that uses this blocking starter has no `ClientRegistrationRepository`, so
  `client-credentials` stops startup with 1005. No realistic setup for this is known.

Judge: independent code judge. The configured model is claude-opus-5-5; the model that actually served this
session may differ. I read AGENTS.md, all six topic files, the A1 design note and every changed file.
