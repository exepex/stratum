# Judge verdict: PR #242, blocking HTTP client starter (H1 of ADR 0011), Feature track, code review, round 3

Branch `claude/project-thread-l2n6gt`, head `6a30794`, base `b509a9f` (main, unchanged; no rebase needed). Earlier
verdicts: `PR-242-http-client-h1.md` (r1, Opus 5.5, FAIL at `4765c6b`) and `PR-242-http-client-h1-r2.md` (r2, Claude
Fable 5.1, FAIL at `f8f7da7`). This round: Claude Opus 5.5, a fresh agent with no shared conversation. The platform
offers two models to this pool, so r1 and r3 share a model; rounds still alternate. Sources: ADR 0011 on main,
`design/http-client-libraries/h1-design-note.md`, `gap-review-merged.md`, and the owner decisions in the brief (Apache
only for Stratum's clients; `spring.http.clients.imperative.factory=jdk` as a Stratum default, team value wins;
blocking only; `SilentPort` in `stratum-test` with a one-class SpotBugs exclusion; no WireMock).

**PASS** at `6a30794`.

All five round-2 findings are fixed, and I replanted each round-2 fault: every one is now caught by the new test
written for it. The only production change since round 2 is one argument in the scanner, which is the fix round 2
asked for. CI, CodeQL, Sonar and Codex are all clean at the head, and all 10 review threads are answered and resolved.
I found no new realistic, material defect in a fresh pass over the whole PR.

I read AGENTS.md and all six topic files. Nothing pushed, nothing posted on GitHub, not merged.

## What I ran
- JDK 25 (`openjdk-25-jdk-headless`). `./mvnw -B clean install -pl starters/stratum-starter-http-client -am` at
  6a30794: exit 0 (formatting, Checkstyle, PMD, SpotBugs, Enforcer, JaCoCo all on). Starter: 16 unit tests, 24 ITs,
  0 failures (round 2 had 13 + 23; the 4 new tests are the fixes below).
- `git diff f8f7da7..6a30794`: 9 files. Production: `StratumClientRegistrar` `considerInterfaces` `true` → `false`
  and its Javadoc. Tests: 1 new unit test class, 3 new tests, 1 fixture interface. Docs: 3 pages.
- GitHub at 6a30794: Verify and SBOM, CodeQL, Analyze (java-kotlin, actions), SonarCloud (quality gate passed, 0 new
  issues), wiki sync check all success. Codex code review completed at 6a30794 with no findings; 10 of 10 threads
  resolved.
- Worktree clean after every plant (`git status` empty).

## Round-2 findings
| Finding | Result | Evidence |
|---|---|---|
| F1 post-processor order untested | FIXED | `RestClientEnvironmentPostProcessorTest.runsAfterConfigurationFiles`, the cache module's pattern r2 named. Fault (`ORDER - 1`) caught by it |
| F2 Boot `RestClientCustomizer` factory replaced, untested | FIXED | `ClientPoolIntegrationTest.WithServiceRequestFactory.stratumPoolWins` (team customizer sets `SimpleClientHttpRequestFactory`; full pool still refused after `max-wait`). Fault (`StratumClientGroups.ORDER = -1`) caught by exactly this test |
| F3 un-annotated sub-interface → raw NPE | FIXED | `AnnotationTypeFilter(StratumClient.class, false, false)`; fixture `InventoryAdmin extends InventoryClient` in the shared clients package; `subInterfaceWithoutAnnotationSkipped`. Fault (back to `true`) caught by it, and the fixture makes every IT that scans that package fail too. `isStratumClient` uses `isAnnotationPresent` (not inherited), so scanner and group filter now agree |
| M1 blank url untested | FIXED | `StartupChecksTest.blankUrlStopsStartup` (`" "` → `SX-HTTPC-1004`, names the key). Fault (`isBlank()` removed) caught by it |
| M2 three pages still say "planned" | FIXED | `basics.md:248`, `reference.md:139,175,200`, `overriding-defaults.md:173-174` now say the blocking starter is built and name what is still planned (`auth`, `retries`). The new `#customize` link resolves (`http-client.md:221`); its wording matches `http-client.md:240-241` |

## Planted faults (this round)
4 planted, 4 caught. Full starter suite (unit + IT) per fault, test failures not stopping Failsafe.
1. `RestClientEnvironmentPostProcessor.getOrder()` = `ORDER - 1`: caught (`runsAfterConfigurationFiles`).
2. `StratumClientGroups.ORDER = -1`: caught (`stratumPoolWins`, only that test).
3. `considerInterfaces = true`: caught (`subInterfaceWithoutAnnotationSkipped` and 33 other tests).
4. Blank `url` accepted: caught (`blankUrlStopsStartup`, only that test).

## Fresh review of the whole PR
I read every production class in the starter (`ClientConnectionPools`, `RestClientEnvironmentPostProcessor`,
`StratumClientGroups`, `StratumClientRegistrar`, `StratumClientChecks`, `RestClientAutoConfiguration`) against ADR
0011, the note and the owner's decisions. Round 2's gap list and rule table still hold; the only production change
since then is the one argument above, which narrows what is scanned and touches no other path. Checked again:
- Pool: per client or shared from `defaults`; per-host = total; `max-wait` as connection-request timeout; stale check
  2 s; lifetime; idle sweep at half `idle-timeout` on one daemon thread; Apache retries and redirects off; pools
  shared with the client so clients close before pools on shutdown. Matches note and ADR.
- Owner 07:11Z: Stratum's clients on Apache HC5; Boot's `imperative.factory` defaulted to `jdk` below the service's
  values, only while the module is on; team value wins. Matches, and now tested at the order that makes it true.
- ADR rule 9: groups without a `@StratumClient` interface are left as Boot set them up (`groups.filter`), tested.
- Startup checks read config and interfaces only, name a key or interface, never a value (ST-SEC-03).
- Line counts of the 9 changed files: largest `docs/configuration/basics.md` 278/500, `ClientPoolIntegrationTest.java`
  200/400; every file ends in a newline.
- ST-ENG-46/47 in the new code: the test customizer is a one-expression lambda; no new static helper.

## Findings
None.

## Notes (not findings)
1. Carried from r2 Note 1, still optional: Boot's global `spring.http.clients.connect-timeout` / `read-timeout` /
   `redirects` do not reach a Stratum client either, since Stratum sets the request factory. The page says this for
   `spring.http.serviceclient.<name>.*` only. One sentence under "Properties" would help a team that already sets
   the global keys. Not a defect: ADR 0011 and the ST-ENG-34 exception already imply it.
2. `RestClientEnvironmentPostProcessorTest` checks the order value, not the profile-file behaviour end to end. It is
   the same check the cache module uses and it catches the fault, so it is enough.

## Not verified
- The full multi-module `mvn verify` locally (Docker-backed modules cannot run here). CI's full build at 6a30794 is
  green, and only the HTTP client starter changed since round 2's full local build.
- Trace propagation on Stratum's clients (Claims and page mark it planned).
- An HTTPS target through a proxy (ADR "Not verified").

Not merged.
