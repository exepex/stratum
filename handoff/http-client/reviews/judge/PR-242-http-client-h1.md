# Judge verdict: PR #242, blocking HTTP client starter (H1 of ADR 0011), Feature track, code review, round 1

Branch `claude/project-thread-l2n6gt`, head `4765c6b`, base `b509a9f` (main). Sources: ADR 0011 on main, the
accepted note `design/http-client-libraries/h1-design-note.md` (round 3), its verdicts `H1-design-note.md` and
`-r2.md`, `gap-review-merged.md`, the owner decisions in the brief, Apache HttpClient 5.6.5 sources. Model: Claude
Opus 5.5. The note's round 2 judge was also Opus, so this review does not rotate model (AGENTS.md "AI platforms");
the coordinator chose the judge.

**FAIL**, on four should-fix findings and one minor, all missing tests. The code itself does what the note, the ADR
and the owner's decisions say: every probe I ran on the unchanged code passed. But 5 of my 8 planted faults
survived the suite, and 2 of them break the owner's foreign-client rule. No production code needs to change.

I read AGENTS.md and all six topic files. Nothing pushed, nothing posted on GitHub, not merged.

## What I ran
- JDK 25.0.5, `./mvnw -B clean install -pl '!starters/stratum-starter-data-jpa'` at 4765c6b. Core, test, HTTP client
  lib (12 tests), web, logging, observability, cli and HTTP client starter (13 unit + 19 IT): green. The cache
  starter failed only on its Docker ITs (no Docker here). Its changed `RedisUnreachableIntegrationTest` (`SilentPort`
  from `stratum-test`) passed. Batch, web and bundle modules not rebuilt locally (offline resolution). They are
  unchanged apart from the reactive bundle POM, and CI's full build covers them.
- GitHub at 4765c6b: Verify and SBOM, CodeQL, Analyze (java, actions), SonarCloud all success. All 10 review threads
  resolved.
- Probes (throwaway ITs in a scratch worktree, deleted): a team `@ImportHttpServices` group next to Stratum clients;
  one client holding 6 connections to one host; a shared pool sized from `defaults`; the module switched off.

## Gate
MET. Ready for review, CI green, every bot thread answered and resolved. I checked the declined part of the
ST-ENG-56 thread (keep pools/groups internal and drop them from the page): accepted, it matches the note's API list.

## Gap list
| Requirement (note, ADR 0011, owner) | Result | Evidence |
|---|---|---|
| `@StratumClient` scan, one group per name | MET | `StratumClientRegistrar:79-98`; `ClientPoolIntegrationTest.sameNameSharesGroup` |
| 8 keys, defaults, client wins, Java wins | MET | `ClientSettings`, `HttpClientProperties:64-68`; my fault 5 caught by 7 tests |
| Pool per client, `max-wait`, `shared-pool` | MET in code, PARTLY tested | `ClientConnectionPools:66-122`; F2, F3 |
| Retry off, redirects off, gzip, timeouts | MET | `:72-78`; implementer faults, CI |
| Stale 2s, idle close, lifetime, Keep-Alive | MET | `:108-128`; tests in `ConnectionLifecycleIntegrationTest`; M1 |
| No network at startup, pools close | MET | `StartupIntegrationTest`, `poolsClosedOnShutdown` |
| SX-HTTPC-1001/1002/1003/1004 | MET | `StratumClientChecks`; `StartupChecksTest`; error-codes page matches `HttpClientErrorCode` |
| Owner 07:11Z: Stratum's clients on HC5, teams' on JDK, team value wins | MET, PARTLY tested | `RestClientEnvironmentPostProcessor`; `ForeignClientIntegrationTest`; F1 |
| ADR 9: a client Stratum did not build is never changed | MET, NOT tested | probe A; F1 |
| Module off sets up nothing and changes no Boot setting | MET, PARTLY tested | probe D; F1 |
| Reactive bundle drops the blocking starter | MET | reactive bundle `pom.xml` |
| `SilentPort` in `stratum-test`, SpotBugs exclusion for that class only (owner) | MET | `libs/stratum-test/spotbugs-exclude.xml` (one class, two patterns); cache copy gone |
| WireMock not added (owner) | MET | no new test dependency beyond reactor-core |
| Docs (ST-DOC-01/04/05/08), README PII line (ST-SEC-03) | MET | module page: table incl. Boot key, both formats, "Configure in Java", Planned list, line 104 |
| Line counts (ST-AG-10) | MET | 75 files, largest `README.md` 459/500, `ScriptedDownstream.java` 306/400; all end in a newline |

Deviations from the note (Claims 2): SX-HTTPC-1004 instead of SX-CORE-1003, customizer in the starter, no starter
README (ST-DOC-04: shared prefix), HC5 built directly with `useSystemProperties()` (HC5 5.6.5
`HttpClientBuilder:1022-1024` keeps the JVM proxy selector). I accept all five. Each is simpler or follows a rule.

## Findings

**F1. Should fix (ST-TEST-01, ST-TEST-20, owner 11-08 "never conflict with foreign clients", ADR 9).** Two parts of
the foreign-client rule have no test:
- *A team's own group.* Fault 4 (`groups.filter(this::isStratumClient)` removed in `StratumClientGroups:48`): whole
  suite green. Probe A (team `@ImportHttpServices(group="payments")` with `spring.http.serviceclient.payments.*`):
  clean code starts, and Boot's `read-timeout=300ms` applies (call failed at 305 ms). With the fault, startup stops
  with `SX-HTTPC-1001 ... spring.http.serviceclient.payments.*`. Module page:248 promises this case.
- *Module off.* Fault 1 (switch check in `RestClientEnvironmentPostProcessor:47` bypassed): suite green. Probe D
  (`enabled=false`): clean code leaves Boot's HC5 detection, with the key unset. With the fault, the team's builder
  is switched to JDK (`key=jdk`). Module page:46 and the metadata description promise this case.
Fix: one IT with a team `@ImportHttpServices` group next to the Stratum clients. Add a module-off case to
`ForeignClientIntegrationTest`.

**F2. Should fix (ST-TEST-01, default `max-connections` 50).** Fault 2 (`setMaxConnPerRoute` removed, so HC5's
5-per-host limit applies): suite green. Every pool test sets `max-connections=1`, below HC5's per-host default.
Probe B (`max-connections=6`, 6 held calls to one host): clean 6 reach the server, faulted 5. A client calls one
host, so this line is what makes the default 50 real. Fix: a pool test with `max-connections` above 5.

**F3. Should fix (ST-TEST-01, module page:138 "That pool takes its size ... from defaults").** Fault 3 (shared pool
built from the first client's settings): suite green. `SharedPool` sets `defaults.max-connections=1` for every
client, so it cannot tell the two apart. Probe C (`defaults.max-connections=1`, inventory `max-connections=5`,
both shared): clean refuses the second client's call, faulted lets it through. Fix: give one shared client its own
`max-connections` in `SharedPool`.

**M1. Minor (ST-TEST-01).** Fault 8 (idle sweep every 20 × `idle-timeout` instead of every half): suite green.
`idleConnectionClosed` (idle-timeout 300 ms) waits up to `WAIT` = 10 s, and a 6 s sweep fits in that. With the
default 30s, idle connections would then live up to 10 min. Fix: wait about 2 s (`FAILS_WITHIN`-style) in that test.

## Planted faults
8 planted, 3 caught, 5 survived. Baseline green before each; `git checkout` after each; worktree clean at the end.
1. Module switch ignored in the post-processor: survived (F1).
2. Per-host limit removed: survived (F2).
3. Shared pool from client settings: survived (F3).
4. Group filter removed: survived (F1).
5. Defaults win over a client's value: caught (`HttpClientPropertiesTest` ×3, `ClientTimeoutIntegrationTest` ×2,
   `ConnectionLifecycleIntegrationTest` ×2).
6. `durations-positive` always true: caught (`HttpClientPropertiesTest.zeroDuration`).
7. Customizers run before Stratum's address/pool: caught (`ClientCustomizerIntegrationTest.onlyNamedClientChanged`).
8. Idle sweep 40× slower: survived (M1).

## Rule checks
| Rule | Result |
|---|---|
| ST-ENG-42/43 Lombok, builders | met: `@Builder(toBuilder)` records, `@RequiredArgsConstructor`; builder used for `ClientSettings` |
| ST-ENG-46 lambdas | met: no block lambda in code (only the Javadoc example and a `switch` arm) |
| ST-ENG-47 statics | met: statics only in `@UtilityClass` types, the `resolve` factory, `@DynamicPropertySource`, `ScriptedDownstream.start*` |
| ST-ENG-56 API labels | met: API = `@StratumClient`, properties, settings and builders, both customizers; the rest `@StratumInternal`; ArchTests pass |
| ST-ENG-21/22 conditions, order | met: `@Fallback @ConditionalOnMissingBean`; `before = HttpServiceClientAutoConfiguration`, stated on the page |
| ST-ENG-31 metadata, validation | met: `ConfigurationMetadataTest`; errors name the rule, never a value (`InvalidSettings`) |
| ST-ENG-34 Java config | met: customizer in `spring.factories`, bean rejected (`SX-CORE-1004` test) |
| ST-TEST-15/17/31 | met: `@Nested` groups; ITs on `@SpringBootTest`, startup/shutdown via `SpringApplicationBuilder` |
| ST-SEC-01 to -06 | met: no secret; no URL or value logged; startup messages name keys/interfaces only |

## Notes (not findings)
- Boot's own group set-up builds a JDK request factory for each Stratum group before Stratum replaces it (Claims 6).
  In probe A, 3 `HttpClient-n-SelectorManager` threads were alive after startup: one for each Stratum group plus the
  team's group. Java ends a dropped client's thread once it is garbage-collected. The count is bounded by the
  number of clients, so this is not a leak.
- `connection-lifetime` is also enforced by the periodic `closeExpired()`, which touches idle pooled connections
  only. This is consistent with ADR 5.3.

## Not verified
- Module page:240-241, that Boot's `RestClientCustomizer` beans run before Stratum's set-up on Stratum's groups (not
  probed).
- Trace propagation on Stratum's clients (the Claims mark it not tested, and the page marks it planned).
- Batch, web MVC/WebFlux and the bundles, built locally only up to the starters: CI covers them.

Not merged.
