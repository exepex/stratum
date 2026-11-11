# Judge confirmation: PR #259, C3 mocked clients and `MockClient`, round 2 fix

**PR #259** `claude/project-thread-79g1e3`, head `af4112d`, base `main` at `e97a97c`. Track: Feature. Review step:
code, round 2, confirming the fix of the round-2 finding at a new head (`reviews/judge/PR-259-http-client-c3-r2.md`,
FAIL at `c16c71c`). The owner chose to have the finding fixed. Judge model: Opus 5.5.

**PASS**

## Gate
- Merge with `main`: `git merge-tree origin/main af4112d` is clean, `e97a97c` is an ancestor of the head, and GitHub
  reports `mergeable_state: clean`.
- CI at `af4112d`: Verify and SBOM, CodeQL, Analyze (java-kotlin, actions), the wiki check and SonarCloud all succeed.
  The wiki sync is skipped, as on every PR.
- Codex: the code review at `af4112d` completed with no unresolved findings. There are still 11 threads, all
  resolved, and none is new.
- Sonar: the quality gate passes. The 2 new issues are the S9398 pair I accepted in round 2.
- Gate passed.

## Round-2 finding
1. Repeated request lines in `MockRequestFactoryTest`: **fixed** in `cba1294`. One private
   `ClientHttpResponse execute() throws IOException` creates the request, marks it and executes it (`:119-124`).
   `call()` and `bodyLength()` each open it with try-with-resources, so the response is still closed. Fault P3 below
   shows that all four tests depend on the helper.

## Merges since round 2
- **94a27a6, `main` after B2b.** `git show --remerge-diff` shows one conflict: the imports of
  `RestClientAutoConfiguration`. It is resolved by keeping both sides (`MockAnswers`, `MockClients`,
  `OptionalNotFound`), with no other change. No regression.
- **af4112d, `main` after A1b.** The remerge diff matches the coordinator's account:
  - **Token set-up.** A1b's `authInitializer` moved from `StratumClientBuilders` into `ClientTransport.Pooled`
    (`ClientTransport.java:45-84`). Its body is unchanged: a fixed credential through `ClientAuthInitializers`;
    otherwise the first `TokenInitializers` kind, or `SX-HTTPC-1006`.
  - **No credential on a mocked client.** `Mocked.setUp` sets only the `MockRequestFactory`, so it sends no
    credential and fetches no token, whether the client uses `client-credentials` or token relay.
  - **Wiring.** `configure` still sets `baseUrl`, then the transport, then the customizers.
    `RestClientAutoConfiguration` passes both `tokenInitializers` and `mockAnswers`.
  - **Docs.** `docs/README.md` keeps A1b's OAuth2 row wording and the mocks link.
- **`HttpClientPropertiesCustomizerTest`.** The "from Java configuration" group moved from `HttpClientPropertiesTest`
  into its own class, with its three tests unchanged. The move was needed: `main`'s `HttpClientPropertiesTest` is
  383 lines, and the PR's tests would take it past 400 (ST-AG-10). The file is now 332 lines and the new class 78.
  The class covers one behaviour, so no `@Nested` group is needed (ST-TEST-17).

## Build
On JDK 25, `./mvnw -pl starters/stratum-starter-http-client -am verify` exits 0. The starter's 42 suites report 0
failures and 0 errors. The 22 IT suites include A1b's `ClientCredentialsIntegrationTest`,
`TokenRelayIntegrationTest`, `StaticCredentialIntegrationTest` and `DownloadTokenIntegrationTest`.

## ST-ENG code rules (changed code)
- ST-ENG-02: the credential choice lives in the `Pooled` strategy, and `configure` does not branch.
- ST-ENG-47: `authInitializer` is a private instance method, and `of` is the only static method.
- ST-ENG-13 and ST-ENG-15: names are functional and Javadoc is present.
- ST-ENG-56: `ClientTransport` stays package-private.
- Line counts: `ClientTransport` 97, `StratumClientBuilders` 55, `RestClientAutoConfiguration` 234,
  `docs/README.md` 130. All are within the limits.

## Planted faults
3 planted, 3 caught, 0 survived:
- P1, `Mocked` adds the auth initializer: caught by `MockClientIntegrationTest.fromFile`.
- P2, `Pooled` ignores the token kinds: caught by `TokenRelayIntegrationTest` (`relaysVerifiedToken`, `noCaller`,
  `otherAuthentication`, `otherThread`).
- P3, the `execute()` helper drops the call mark: all four `MockRequestFactoryTest` tests fail.

I restored the tree after each fault; `git status` is clean.

## Notes
1. No test runs a mocked client set to `client-credentials` or token relay. Claims 6 named this gap before A1b
   landed. `Mocked.setUp` never reaches any credential code, and `fromFile` catches any credential added there
   (P1), so it holds by construction. A test only for that kind would add nothing today.
2. Not run by me: the Docker-bound modules (data JPA, cache). CI's Verify job runs them and is green at `af4112d`.
