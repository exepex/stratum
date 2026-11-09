# Judge verdict: PR #242, blocking HTTP client starter (H1 of ADR 0011), Feature track, code review, round 2

Branch `claude/project-thread-l2n6gt`, head `f8f7da7`, base `b509a9f` (main). Round 1 verdict: `PR-242-http-client-h1.md`
(Opus 5.5, FAIL at `4765c6b`). This round: Claude Fable 5.1, a different model, as the rotation asks. Sources: ADR 0011 on
main, the accepted note `design/http-client-libraries/h1-design-note.md` (round 3), `gap-review-merged.md`, the owner
decisions in the brief (Apache only for Stratum's clients; `spring.http.clients.imperative.factory=jdk` as a Stratum
default, team value wins; blocking only; `SilentPort` in `stratum-test` with a by-name SpotBugs exclusion; no WireMock).

**FAIL**, on three should-fix findings and two minor ones. All four round-1 findings are fixed: I replanted each
fault and its new test caught it. The production code does what the ADR, the note and the owner decided; every probe
on the unchanged code passed. What is open: two behaviours still have no test (a planted fault in each survived the
whole suite), one startup path ends in a raw `NullPointerException` instead of a clear stop, one branch is untested, and
three documents still call the starter planned.

I read AGENTS.md and all six topic files. Nothing pushed, nothing posted on GitHub, not merged.

## What I ran
- JDK 25.0.5. `./mvnw -B clean install -pl '!starters/stratum-starter-data-jpa,!starters/stratum-starter-cache'` at
  f8f7da7: 20 modules SUCCESS (core, test, HTTP client lib, web, logging, observability, cli, HTTP client starter,
  Kafka, resilience, security, web MVC, OAuth2 client, WebFlux). The run stopped at the batch bundle only because it
  depends on the excluded data-jpa module (no Docker here). Then `./mvnw -o verify` on the three remaining bundles
  (microservice, reactive-microservice, web-ui): SUCCESS. HTTP client lib: 12 unit tests; starter: 13 unit + 23 IT.
- GitHub at f8f7da7: Verify and SBOM, CodeQL, Analyze (java, actions), SonarCloud (0 new issues) all success. Codex: no
  findings; all 10 earlier threads resolved. Round 1 already checked the one declined part (ST-ENG-56 thread); I agree.
- Boot 4.1.2 facts checked with `javap`: `PropertiesRestClientHttpServiceGroupConfigurer.getOrder()` = MIN+10,
  `RestClientCustomizerHttpServiceGroupConfigurer.getOrder()` = 0 (Stratum's 1 runs after both);
  `HttpServiceClientAutoConfiguration` is `@ConditionalOnBean(HttpServiceProxyRegistry)`, so the page's "How it is
  wired" is right. Apache HC5 5.6.5 `useSystemProperties()` reads only `http.keepAlive` and `http.agent`; the pool
  builder reads no system property, so `max-connections` is never overridden by `http.maxConnections`.
- Probes (throwaway test class and a profile file in the starter, deleted; `git status` clean):
  - A: an un-annotated `interface InventoryAdmin extends InventoryClient` in the clients package → startup fails with
    `NullPointerException: Cannot invoke "StratumClient.value()" because ... getAnnotation(...) is null` (F3).
  - B: `spring.http.clients.read-timeout=100ms` set globally; a held call on a Stratum client waits past 800 ms and
    answers after release → Boot's global timeout keys do not apply to Stratum's clients (Note 1).
  - C: `stratum.http-client.enabled=false` in `application-<profile>.properties` → Boot detects Apache HC5 for the
    team's builder, no client bean. Passes on clean code; catches fault 6 (F1).
  - D: a team `RestClientCustomizer` bean that sets `SimpleClientHttpRequestFactory` on every builder → it runs
    (counter > 0) and Stratum's pool still bounds the inventory client (`max-connections=1` refuses the 2nd call
    with `ConnectionRequestTimeoutException`). Passes on clean code; catches fault 8 (F2).

## Gate
MET. Ready for review, CI green at the head, every bot thread answered and resolved, Claims complete with the note.

## Round-1 findings
| Finding | Result | Evidence |
|---|---|---|
| F1 team group + module off untested | FIXED | `ForeignClientIntegrationTest.ServiceOwnGroup` (2 tests), `.moduleOffKeepsBootDetection`; my faults 1 and 2 caught by exactly these |
| F2 per-host limit untested | FIXED | `ClientPoolIntegrationTest.AboveApacheHostLimit.wholePoolServesOneHost`; fault 3 caught |
| F3 shared pool sized from first client untested | FIXED | `SharedPool` gives both clients `max-connections=5`; fault 4 caught by `sharedPoolIsOne` |
| M1 idle-close wait too long | FIXED | `idleConnectionClosed` waits `FAILS_WITHIN` (1.5 s); fault 5 (sweep 40× slower) caught |

The only changes since 4765c6b are those four test files and `CallChecks.holdOneConnection` on a virtual thread
(`git diff 4765c6b..f8f7da7`: 4 files, tests only). No production code changed.

## Gap list
| Requirement (note, ADR 0011, owner) | Result | Evidence |
|---|---|---|
| `@StratumClient` scan, one group per name, same name shares group | MET | `StratumClientRegistrar:79-98`; `sameNameSharesGroup` |
| 8 keys with defaults, client wins, Java wins, bad value stops startup naming the rule | MET | `ClientSettings`, `HttpClientProperties`; `HttpClientPropertiesTest` (12) |
| Pool per client, `max-wait`, `shared-pool` from `defaults`, per-host = total | MET | `ClientConnectionPools:86-116`; faults 3, 4 caught |
| Apache retry off, redirects off, gzip, timeouts | MET | `:72-78`; implementer faults, CI; build green |
| Stale 2 s, idle close, lifetime, Keep-Alive | MET | `:108-120`; `ConnectionLifecycleIntegrationTest` (5); fault 5 caught |
| No network at startup; pools close | MET | `StartupIntegrationTest`, `poolsClosedOnShutdown` |
| SX-HTTPC-1001/1002/1003/1004, naming key or interface, never a value | MET | `StratumClientChecks`; `StartupChecksTest` (6); error-codes page rows match the enum |
| Owner 07:11Z: Stratum clients on HC5, team's `RestClient` on JDK, team value wins, module off = Boot detection | MET in code, PARTLY tested | `RestClientEnvironmentPostProcessor`; probes B, C; F1 (order untested) |
| ADR 9: a client Stratum did not build is never changed; team group keeps Boot keys | MET | `ServiceOwnGroup`; fault 1 caught |
| Boot `RestClientCustomizer` runs first, its factory replaced (page:240-241) | MET in code, NOT tested | probe D; F2 |
| Only `@StratumClient` interfaces become clients (note "Scanning") | PARTLY | probe A: a sub-interface is picked up and crashes startup; F3 |
| Reactive bundle drops the blocking starter | MET | bundle POM; bundles built |
| `SilentPort` in `stratum-test`, one-class SpotBugs exclusion; no WireMock | MET | `libs/stratum-test/spotbugs-exclude.xml`; no new test dependency |
| Docs: module page, error codes, bundles, README, guides (ST-DOC-01/04/05/08) | PARTLY | module page right; three other pages still say planned; M2 |
| Line counts (ST-AG-10), every file ends in a newline | MET | 75 files checked; largest `README.md` 459/500, `ScriptedDownstream.java` 306/400, module page 306/500 |

Deviations from the note (Claims 2): accepted in round 1; nothing changed.

## Findings

**F1. Should fix (ST-TEST-01, ST-TEST-20 "off when false", ST-TEST-40).** The post-processor's order is what makes
"module off changes no Boot setting" true for a value in `application.properties`, and nothing tests it. Fault 6
(`ConfigDataEnvironmentPostProcessor.ORDER - 1`): whole suite green, because `moduleOffKeepsBootDetection` passes
`enabled=false` as a default property, which exists before the config files are read. Probe C (the same key in
`application-<profile>.properties`, the way the page tells a team to set it): clean code gives Boot's detection,
the faulted code switches the team's clients to `jdk` although the module is off. Fix: the cache module's own test,
`CacheEnvironmentPostProcessorTest.runsAfterConfigurationFiles` (`getOrder()` equals `ConfigDataEnvironmentPostProcessor.ORDER + 1`),
in a `RestClientEnvironmentPostProcessorTest`, or probe C as an IT. No production change.

**F2. Should fix (ST-TEST-01; documentation "each promise ... checked by a probe or a named test").** Module page:240-241
promises that a Boot `RestClientCustomizer` runs before Stratum's set-up and that a request factory it sets is
replaced. Fault 8 (`StratumClientGroups.ORDER = -1`, between Boot's two configurers): whole suite green. Probe D:
clean code keeps Stratum's bounded pool; the faulted code leaves the team's `SimpleClientHttpRequestFactory` on the
Stratum client, so it silently loses its pool, `max-wait` and lifetime. A team `RestClientCustomizer` that sets a
request factory is a common way to give Boot's `RestClient.Builder` timeouts, so this is a real setup. Fix: probe D
as an IT (a `RestClientCustomizer` test bean setting a factory; assert the client is still refused after `max-wait`
with `max-connections=1`). No production change.

**F3. Should fix (ST-ENG-25 "stops startup with a message naming ...", ST-ENG-27, note "Only interfaces with
`@StratumClient` are registered").** `StratumClientRegistrar:106` builds
`new AnnotationTypeFilter(StratumClient.class, false, true)`; the third argument, `considerInterfaces`, makes the
scanner match an interface whose *parent* interface carries the annotation. `checkInterface:40` then calls
`getAnnotation(StratumClient.class).value()` on the child, which has none, and startup dies with a raw NPE (probe A).
A service with `interface InventoryAdmin extends InventoryClient` in its packages cannot start, and the failure
names no code, key or class. Fix, the simplest: pass `false` for `considerInterfaces`, so only interfaces that carry
`@StratumClient` themselves are clients, as the note and page say, plus one test with an un-annotated
sub-interface in the clients fixture package that asserts it is not a bean and startup succeeds. One line of code.

**M1. Minor (ST-TEST-40).** `StratumClientChecks.requireUrl:103` treats a blank `url` as missing on purpose, but
fault 7 (the `isBlank()` clause removed) survived: `missingUrlStopsStartup` only covers the unset key. A blank
value is what `url: ${INVENTORY_URL:}` yields when the variable is absent. Fix: a blank-url case next to
`missingUrlStopsStartup`, expecting `SX-HTTPC-1004`.

**M2. Minor (ST-DOC-01, ST-DOC-08).** Three unchanged pages still describe the starter as planned:
- `docs/configuration/overriding-defaults.md:172-174`: `RestClientCustomizer` row "The http-client starter is planned";
  `StratumClientCustomizer` row status "Planned". Both exist now (`StratumClientCustomizer` is API in this PR).
- `docs/configuration/reference.md:138-139` "The planned HTTP client starters are the one exception", and `:200`
  "# Planned HTTP client keys (ADR 0011)" above `url` and `timeout`, which are built (`auth` and `retries` in the
  same block are still planned, so split or reword the comment).
- `docs/configuration/basics.md:248` "The one exception is the planned HTTP client starters".
Fix: say the blocking starter is built and name what is still planned, as `docs/README.md:112` now does.

## Planted faults
8 planted, 5 caught, 3 survived. Full starter suite (unit + IT) per fault; `git checkout` after each; worktree clean.
1. Group filter removed (`groups.forEachClient`): caught (`serviceGroupKeepsBootSettings`, `stratumClientsStillAnswer`).
2. Module switch bypassed in the post-processor: caught (`moduleOffKeepsBootDetection`; also probe C).
3. `setMaxConnPerRoute` removed: caught (`wholePoolServesOneHost`).
4. Shared pool built from the client's settings: caught (`sharedPoolIsOne`).
5. Idle sweep every 20 × `idle-timeout`: caught (`idleConnectionClosed`).
6. Post-processor ordered before the config files: **survived** (only probe C caught it; F1).
7. Blank `url` accepted: **survived** (M1).
8. Group set-up ordered between Boot's two configurers: **survived** (only probe D caught it; F2).

## Rule checks
| Rule | Result |
|---|---|
| ST-ENG-42/43 Lombok, builders | met: `@Builder(toBuilder)` records, `@RequiredArgsConstructor`, `@UtilityClass`, `@Getter` on the enum; no `new` of a builder type |
| ST-ENG-46/47 lambdas, statics | met: every lambda one expression or method reference; statics only in `@UtilityClass` types, the `resolve` factory, `start*` factories and `@DynamicPropertySource` |
| ST-ENG-39/40/41 | met: streams, records, `var`; every new package `@NullMarked` (checked all 9 `package-info.java`) |
| ST-ENG-14/15 Javadoc | met (build-checked); Javadoc functional, no internals |
| ST-ENG-21/22 conditions, order | met: `@Fallback @ConditionalOnMissingBean`, module condition, `before = HttpServiceClientAutoConfiguration` stated on the page and needed (`@ConditionalOnBean(HttpServiceProxyRegistry)`) |
| ST-ENG-23 autoconfigure wires only | met (constructor calls `rejectBeans`, as other modules) |
| ST-ENG-30/31/32/34 | met: one record, metadata test, both formats on the page, `spring.factories` customizer, bean rejected |
| ST-ENG-56/57 API labels, cycles | met: API = annotation, properties, settings, builders, both customizers; ArchTests green |
| ST-ENG-60/62 versions, new dependency | met: `httpclient5` managed by Boot, reason in the POM comment; BOM lists the new library |
| ST-TEST-15/17/30/31 | met: `@Nested` groups, `@FieldSource` not needed (no parameterized test), ITs on `@SpringBootTest` or `SpringApplicationBuilder` |
| ST-SEC-01 to -06 | met: no secret or personal data in code, tests, docs; startup messages name keys and interfaces only; TLS verification untouched (`useSystemProperties`) |
| ST-AG-10 line counts | met (table above) |

## Notes (not findings)
1. Boot's global `spring.http.clients.connect-timeout` / `read-timeout` / `redirects` do not apply to a Stratum
   client either (probe B), since Stratum builds the request factory. The page says this only for
   `spring.http.serviceclient.<name>.*`. One sentence under "Properties" would spare a team that already sets the
   global key a surprise; the ADR and ST-ENG-34's exception already imply it.
2. `ClientConnectionPools` starts its one daemon sweeper thread at construction, even when no client exists. Bounded,
   one thread, not a leak.
3. With `considerInterfaces` left `true`, a sub-interface that *also* carries `@StratumClient` with the same name
   works (both join the group). F3 is only about the un-annotated child.

## Not verified
- Trace propagation on Stratum's clients (Claims mark it not tested; page marks it planned).
- The batch bundle and the two Docker-backed starters locally; CI's full build at f8f7da7 is green.
- An HTTPS target through a proxy (ADR "Not verified").

Not merged.
