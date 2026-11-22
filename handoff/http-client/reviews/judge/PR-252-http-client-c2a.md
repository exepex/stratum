# Judge: PR #252, clients Stratum did not build (C2a), branch `claude/project-thread-79g1e3`, head `e3e902e`, Feature track, code review (round 1)

**Verdict: PASS** at `e3e902e0139ce79b3065162d93cf2a23d6f1909d`. **Judge model:** Claude Fable 5.1 (the note's round-1 judge was
Opus, its round-2 judge Fable; the coordinator chose this judge). Nothing was pushed, changed or merged.

I read AGENTS.md and all six topic files (engineering, testing, security, documentation, review, judge), the C2a
design note (round 3), the two note verdicts, ADR 0011 and ADR 0012. Steps 1 to 5 of `judge.md`.

## Gate
- Claims complete: request, track, requirements, note, broken-code proof, self-review, not-done, files read, line counts.
- Open, not a draft. CI green at `e3e902e`: Verify and SBOM success (14:28Z), SonarCloud quality gate passed (0 new
  issues, 0 hotspots), CodeQL success. Codex code review completed with no findings; no review threads.

## What I ran
- `./mvnw -pl libs/stratum-http-client,starters/stratum-starter-http-client verify` on JDK 25 at the head: BUILD
  SUCCESS, every static check on, starter 34 unit + 26 integration tests, 0 failures.
- A probe (context runner): two clients naming the same generated interface (see Notes).
- Eight planted faults (below).

## Round-2 fixes the owner asked this judge to check in the code
| Fix | In the code | Evidence |
|---|---|---|
| API interface, internal implementation | yes | `StratumHttpClients.java` (interface, API list in `RestClientArchTest:17`); `DefaultStratumHttpClients.java:19` `@StratumInternal`; bean `@ConditionalOnMissingBean`, `RestClientAutoConfigurationTest.serviceHttpClientsWin` |
| Boot's `RestClientCustomizer` beans first | yes | `DefaultStratumHttpClients.build`: `RestClient.builder()`, `orderedStream()` of the customizers, then `builders.configure`; fault F4 caught by `appliesBootCustomizers` |
| Shared `isStratumClient` predicate | yes | `StratumClientGroups.isStratumClient` (public, annotation or the client's `interfaces` list, never the name alone); `StratumClientGroupsTest` 4 cases; fault F1 caught |

## Gap list
| Requirement / owner decision | Result | Evidence |
|---|---|---|
| ADR 9.1: a generated interface joins a client by configuration and gets what a `@StratumClient` gets | MET | `StratumClientRegistrar.namedInterfaces`, same `StratumClientBuilders.configure` path; `OwnClientsIntegrationTest$NamedInterface` (address, pool refusal after `max-wait`) |
| Key `interfaces`, a list, clients only, no `@Name` (owner 13:49Z) | MET | `ClientSettings.interfaces`; `git grep "@Name"` in the diff: 0; `HttpClientPropertiesTest` YAML list, `interfaces[0]/[1]`, under `defaults` rejected (`defaults-without-interfaces`) |
| Java sets `interfaces` too, read through `resolve` (ST-ENG-34) | MET | registrar calls `HttpClientProperties.resolve`; `javaSetInterfaceIsRegistered` through `spring.factories` |
| `SX-HTTPC-1007` (missing or a class), `1008` (marked for another client), `1002` on a named interface | MET | `StratumClientChecks.namedInterface`; `StartupChecksTest$NamedInterfaces` 5 tests; faults F2, F5, F8 caught; error-codes guide rows start with `getFix()` text (`ErrorCodeChecks` passed) |
| ADR 9.2: `restClient(name)` with address, timeouts, the client's pool, customizers; built once | MET | `OwnClientsIntegrationTest$RestClientOfClient` 5 tests; `ClientCustomizerIntegrationTest.restClientOfClientCustomized`; faults F3, F7 caught |
| `restClient(name)` sends once: no retry, breaker or 401 resend (owner 13:38Z) | MET | `StratumClientBuilders.configure` and `DefaultStratumHttpClients.build` add no interceptor or retry; Javadoc, module page and ADR 0012 say so |
| ADR 9.3 first half: `settings(name)` merged with defaults | MET | `DefaultStratumHttpClients.settings` → `clientSettings`; `SettingsOfClient.mergedSettings` |
| `resilience(name)` | Out of scope (C2b) | stated in Claims and note; module page lists it under Planned |
| ADR 0011 unedited; ADR 0012 records the three changes; codes 1007/1008 | MET | `git diff aabdae8..e3e902e -- docs/adr/0011*`: empty; ADR 0012 (39 lines) linked from `docs/README.md`; cites decisions 2.4, 9.1, 9.2, 10 correctly |
| `MockClient` in the starter (owner 13:39Z) | MET in ADR 0012 | decision 2; matches the C3 note's owner line |
| Never wrap, replace or check a foreign client; unused blocks allowed (owner 11-08) | MET | `ForeignClientIntegrationTest` unchanged; `restClient("billing")` with no block fails only at first use (`unknownClientFailsAtFirstUse`) |
| Boot-check boundary (11-06): configuration-only checks | MET | 1007/1008 resolve only the class names the configuration gives |
| Nothing built beyond the request | MET | every new type and method has a use in the diff |
| Docs: property table row, both file formats, Java section, planned labelled, index link, error codes | MET | `docs/modules/http-client.md:139`, `:277-293`, `:193` (generic Java section covers every setting), `:348-350`; `docs/README.md:113` |
| Line counts | MET | largest changed files: module page 361, error codes 327, `OwnClientsIntegrationTest` 205; all under the limits |

## ST-ENG code rules
| Rule | Result |
|---|---|
| ST-ENG-42/43 Lombok, builders | `@RequiredArgsConstructor` on `DefaultStratumHttpClients`, `StratumClientBuilders`; `@UtilityClass` checks; tests build settings with `ClientSettings.builder()` |
| ST-ENG-14/15 Javadoc | every new class, method and record component documented, brief |
| ST-ENG-46/47 | every lambda one expression or a method reference; no static method outside the utility class |
| ST-ENG-56/57 | `StratumHttpClients` exposes only `ClientSettings` (API) and `RestClient`; ArchUnit passed locally |
| ST-ENG-13 | names functional (`markedInterfaces`, `namedInterfacesOf`, `builtOnce`) |
| ST-SEC-01 to 06 | no secret, no logging added, errors name the key and class only |

## Planted faults: 8 planted, 8 caught
| Fault | Caught by |
|---|---|
| F1 `isStratumClient` checks the annotation only | `StratumClientGroupsTest.namedInterface` |
| F2 registrar ignores `interfaces` | 5 tests of `StartupChecksTest$NamedInterfaces` |
| F3 `restClient` built on every call | `OwnClientsIntegrationTest.builtOnce` |
| F4 Boot's customizers not applied | `OwnClientsIntegrationTest.appliesBootCustomizers` |
| F5 1008 check removed | `StartupChecksTest.interfaceOfTwoClientsStopsStartup` |
| F6 `defaults-without-interfaces` always true | `HttpClientPropertiesTest.interfacesInDefaults` |
| F7 `restClient` on its own request factory (JDK) | `OwnClientsIntegrationTest.sharesClientPool` |
| F8 method check skipped for a named interface | `StartupChecksTest.namedReactiveInterfaceStopsStartup` |

## Notes (not findings)
- Probe: two clients naming the same generated interface start, with beans `partner#…PartnerOrdersApi` and
  `pricing#…PartnerOrdersApi`; an injection by type then fails with Spring's own "no unique bean" error. A
  configuration mistake with a clear message, as the round-2 verdict noted; nothing to build.
- `ClientCustomizerIntegrationTest.onlyNamedClientChanged` now uses `endsWith` because the stub server's path list is
  shared across the class; it still proves the pricing client was not moved. Disclosed in the Claims.
- `HttpClientProperties.resolve` now runs once more at startup, in the registrar; cheap, and the failure analyzer
  already covers a `StratumException` from there (1002, 1003 in H1).
- ADR 0012's "Uploads through it keep streaming" rests on the C1 probe (no interceptor, chunked upload); the check
  through `restClient(name)` arrives with the C1 pull request, as the Claims say.
- Boot's `ObservationRestClientCustomizer` is not on the module's test classpath; the test proves the customizer
  path with a counting `RestClientCustomizer`, as the Claims state.
