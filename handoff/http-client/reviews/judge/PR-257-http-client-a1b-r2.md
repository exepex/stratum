# Judge verdict: PR #257 (A1b, service token and token relay), round 2

Head reviewed: `62e8f5d`. Main at review time: `c8b6d6a`. Judge: Sonnet 5.5 (fresh; round 1 was Opus).

## Verdict: FAIL (gate only; no code finding)

The gate fails: the branch does not merge cleanly with current `main`. The code and the round-1 fixes look right, so
the next round should be quick once the branch is rebased.

## Gate

| Check | Result |
| --- | --- |
| Merges cleanly with `main` (`c8b6d6a`) | **No.** `git merge-tree origin/main pr257` conflicts in `docs/modules/http-client-errors.md` and `starters/stratum-starter-http-client/.../autoconfigure/RestClientAutoConfiguration.java`. GitHub shows `mergeable_state: dirty`. The branch is based on `abeaa2a`; `main` moved on with PR #265 (B2b, Optional 404), which changed both files. |
| CI at head | Green: Verify and SBOM, CodeQL (both), SonarCloud, wiki check. But it ran on the old base, so it says nothing about the merged result. |

## Findings

1. **Blocking, gate.** Rebase on `main` (`c8b6d6a`), resolve the two conflicts keeping #265's `OptionalNotFound`
   wiring and error-doc rows next to this PR's token wiring and rows (check the 1005/1006 order still holds), and
   re-run `./mvnw -pl libs/stratum-http-client,starters/stratum-starter-oauth2-client,starters/stratum-starter-http-client -am verify`.
   After the merge, recount lines (ST-AG-10): `docs/modules/http-client.md` is 486 now and `main` added 6 lines to it,
   so it can pass the 500 limit; split it if it does.

No other finding.

## Round 1 fixes checked (by reading; I did not rerun the build, which needs JDK 25 and this container has 21)

| Round 1 finding | Status |
| --- | --- |
| F1 servlet app does not start | Fixed by `LoginChainImportFilter`. Judged below. |
| F2 token expiry test | `SingleFetchTokenManagerTest.Expiry` added (per the PR; CI green). |
| F3 0004 maps to 500 | `RestClientEnvironmentPostProcessorTest.defaultStatuses` covers all four codes. |
| F4 docs promised a 401 resend on `restClient(name)` | Docs now say it is for interface calls only. |
| F5 loops uncommented, long `Duration` | Streams or comments used; `IssuingTokenStore.SHORT_LIFETIME` used. |
| F6 1005/1006 row order | In code order. |

## Is `LoginChainImportFilter` the right fix?

Yes. Reasons, with where I looked:

- **Cause matches.** `spring-security-config` (needed for `CommonOAuth2Provider`) satisfies
  `@ConditionalOnClass(EnableWebSecurity)` on Boot's OAuth2 web-security auto-configuration, while its
  `@ConditionalOnDefaultServletWebSecurity` part is lost with `spring-boot-security`. The filter removes exactly
  that one auto-configuration (`LoginChainImportFilter.java`, `match`).
- **Spring-native, not a patch.** `AutoConfigurationImportFilter` is Boot's own hook for dropping auto-configurations
  by a class-presence test, registered in `META-INF/spring.factories`. It adds no `spring.autoconfigure.exclude`
  property that a team could overwrite or that would show up in their environment, and no bean, key or default.
- **Does not hide real config for teams that use Spring Security.** The test is the presence of
  `SecurityAutoConfiguration` from `spring-boot-security`. A team that adds Boot's security starter or the resource-server
  starter (which the docs require for token relay) has that class, so nothing is left out and Boot behaves as usual.
  `LoginChainImportFilterTest.WithBootSecurity` proves it.
- **Only the login chain goes.** Other auto-configurations, including the registrations and the token store, are
  kept (`WithoutBootSecurity` checks `containsExactly(true, false, true)`); `ClientTokenWebIntegrationTest` shows a
  `RANDOM_PORT` servlet app answers 200 with no sign-in and the `OAuth2AuthorizedClientService` bean present.
- **Cheap.** One class-presence lookup per startup.
- **Style.** `@StratumInternal`, Lombok constructor, Javadoc on every member, one job, a test with `@Nested`
  groups. The two-constructor shape is needed because Spring creates the filter with the no-arg one and the test
  passes another class name.

Notes (not findings):

- The reactive counterpart (WebFlux) is not covered; the PR says so and WebFlux is out of focus. Not a real setup for
  this blocking starter.
- I did not rerun the fault plant (remove the `spring.factories` entry and see the IT fail). CI is green at head and
  the PR reports the round-1 message reproduced. ST-AG-01: not verified by me.

## ST-ENG code rules (for the new class and tests)

| Rule | Result |
| --- | --- |
| ST-ENG-42 Lombok | Met (`@RequiredArgsConstructor`). |
| ST-ENG-46, 47 no block-lambda or private static helper | Met in the new files. |
| ST-ENG-56 `@StratumInternal` | Met. |
| ST-ENG-14, 15 Javadoc | Met. |
| ST-TEST-15, 17 `@Nested`, display names | Met. |
| ST-AG-10 line count | Met at head (all files under limits); recheck after the rebase (finding 1). |

## What happens next

Rebase and push; the coordinator then starts a fresh judge on a different model for round 3. Nothing was pushed,
merged or changed by this review.
