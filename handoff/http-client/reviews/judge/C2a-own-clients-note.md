# Judge: C2a design note, clients Stratum did not build (Feature track, note review, round 1)

**Note:** `design/http-client-libraries/c2-own-clients/c2-design-note.md` (95 lines). **Base:** `main` at `aabdae8`.

**Verdict: FAIL**, on three should-fix findings. The design is right in shape: the `interface` key, a cached
`restClient(name)` on the group's pool, `settings(name)`, no interceptor, and C2b split out. The note needs one
code change (F1), one gap in the client's set-up (F2) and one rule it leaves unstated (F3). Nothing was merged or
changed.

I read AGENTS.md and all six topic files (engineering, testing, security, documentation, review, judge), and
`handoff/stratum-judge-process.md`. Steps 1, 2, 3 and 5 of `judge.md`, applied to the note. No build: a note review.

## What I checked

- `main` at `aabdae8`: `StratumClientGroups`, `StratumClientRegistrar`, `StratumClientChecks`, `ClientConnectionPools`,
  `RestClientAutoConfiguration`, `HttpClientProperties`, `ClientSettings`, `HttpClientErrorCode`,
  `ForeignClientIntegrationTest`.
- ADR 0011 sections 1.3, 1.4, 2.4 and 9; the A1 note (startup codes); the B1 note (decorators, `ClientResilience`).
- Spring Boot 4.1.2 sources (`spring-boot-restclient`, `spring-boot`) and spring-web 7.0.10 sources, downloaded from
  Maven Central:
  - `HttpServiceClientAutoConfiguration` registers `RestClientCustomizerHttpServiceGroupConfigurer` (order 0), which
    applies every `RestClientCustomizer` bean to every group's builder;
  - `RestClientObservationAutoConfiguration` contributes `ObservationRestClientCustomizer` as a `RestClientCustomizer`;
  - `InterceptingClientHttpRequest extends AbstractBufferingClientHttpRequest` (line 36): confirms the note's
    reason for no interceptor;
  - `@Name` has `@Target({FIELD, PARAMETER})`, so on a record component it reaches the canonical constructor
    parameter. This supports the note's plan; the binding itself stays unverified, as the note says.
- `ClientConnectionPools.requestFactory` adds a `CloseableHttpClient` to `clients` on every call
  (`ClientConnectionPools.java`, `clients.add(httpClient)`): confirms why `restClient(name)` must be cached.

## Gap list

| Requirement (ADR 0011, owner decisions) | Result | Evidence |
|---|---|---|
| 9.1 `clients.<name>.interface` joins a generated interface | MET in design, F3 | Keys table; registrar change |
| 9.2 `restClient(name)` with the client's settings | PARTLY (F2) | misses Boot's customizers |
| 9.3 `settings(name)` | MET | `HttpClientProperties.clientSettings` |
| 9.3 `resilience(name)` | Out of scope, to C2b | Needs B1's `ClientResilience`; sound split |
| Never wrap, replace or check a foreign client; unused blocks allowed (owner 11-08) | MET | `ForeignClientIntegrationTest` unchanged; no check on unused blocks |
| No interceptor, uploads stream | MET | Test row "has no interceptor" |
| No comma syntax (owner 11-07) | MET | List key |
| Every key in Java too (ST-ENG-34) | MET in design, F4 | `HttpClientPropertiesCustomizer` |
| Boot-check boundary (11-06): config-only checks | MET | 1005 resolves only class names the config gives |
| Existing-contract rule | MET | Nothing changes without the key or the bean call |
| Owner card (retry in `restClient`) | Handled | Note says what changes for "yes" |
| Startup codes unique | NOT MET (F1) | Collides with A1 |

## Findings

**F1, should fix: `SX-HTTPC-1005` and `SX-HTTPC-1006` are already taken by lane A.** The A1 note
(`a1-auth-design-note.md`, "Startup checks") defines `SX-HTTPC-1005` (unknown registration or wrong grant) and
`SX-HTTPC-1006` (OAuth2 client or resource server missing). C2a gives the same codes other meanings: a missing
interface class, and one interface on two clients. Both lanes add the same enum constants to
`HttpClientErrorCode`; whichever merges second either conflicts or ships a code with two meanings in the docs.
Fix: agree the numbers with the coordinator (for example, C2a takes the next two free after A1, `1007` and `1008`)
and say so in the note.

**F2, should fix: `restClient(name)` misses Spring Boot's own `RestClientCustomizer` beans, so it is not "the same
set-up as the group's".** A group's builder gets Boot's customizers first (order 0, quoted above), then Stratum's
`StratumClientGroups` (order 1). `configureClient`, which the note moves into `StratumClientBuilders.configure`, holds
only Stratum's part. A `RestClient.builder()` plus that part loses `ObservationRestClientCustomizer`: no
`http.client.requests` metric and no trace headers on any call through `restClient(name)`, although `trace-headers`
defaults to true (ADR 2.4). It also loses Boot's message-converter customizer. A team calling a vendor SDK through
`restClient("partner")` would see those calls vanish from its traces. Fix: build it as the group's builder is built,
by applying the `ObjectProvider<RestClientCustomizer>` beans in order before `StratumClientBuilders.configure`. Do not
use Boot's `RestClient.Builder` bean, which is absent in a reactive web application (`NotReactiveWebApplicationCondition`
in Boot's `RestClientAutoConfiguration`). Add a test row: a call through `restClient(name)` records an observation, or
carries the trace header.

**F3, should fix: the note does not say how a group counts as Stratum's once an interface can join without the
annotation.** `StratumClientGroups.isStratumClient` (line 73 to 75) accepts a group only when one of its types
carries `@StratumClient`. B1 sets the decorators "on Stratum groups only" through the same filter. A client whose only
interfaces come from `interface`, which is the main case (an OpenAPI generator's output), would be skipped. It would
get Boot's JDK factory, no base URL and no retry or breaker. The planned `namedInterfaceUsesClientPool` test would
catch the pool half, but the note promises "everything a `@StratumClient` gets" with no rule and no decorator test.
Fix: state the rule in section 2. For example: a group is Stratum's when its name is a configured client with
`interface`, or a type carries the annotation. Or the registrar records the groups it registers. Add a decorator
row (a named-only interface is retried) for when B1 is on `main`.

**F4, minor: say that the registrar reads `interface` through `HttpClientProperties.resolve(environment)`.** The
registrar runs before any settings bean exists. Only `resolve` applies the `HttpClientPropertiesCustomizer` classes.
A direct `Binder` read would ignore a Java-set `interface`. The planned Java test is a unit test of the
properties, so it would not catch that. Fix: one line in the note, and have the Java-customizer row prove the
interface is registered (an integration case), not only bound.

**F5, minor: say what a named interface cannot have.** ADR 1.3 attaches per-client `ClientInterceptor`s through
`@StratumClient(interceptors = ...)`, which a generated interface cannot carry. It gets only `GlobalClientInterceptor`s
(and `StratumClientCustomizer`s by name). One sentence in the note and the module page section stops a team from
looking for a key that does not exist.

## Notes (not findings)

- `restClient("typo")` or a client without `url` fails at its first call with `SX-HTTPC-1004`, a startup-series code
  at run time. This is acceptable, as unused blocks are allowed and nothing can be checked at startup without
  auditing the team's code (11-06 boundary). The docs should say it fails on first use.
- An interface that is both named in `interface` and still listed in the team's own `@ImportHttpServices` group
  becomes two proxies of one type, and Spring's own startup fails on the ambiguity. This is not Stratum's check to
  add (boundary rule); the module page could mention it in one line.
- The ST-AG-14 block, the hostile-input answer and the "no thread hop" answer hold. The cache is bounded by the
  configured clients, and `ClientResilience` is reused rather than copied in C2b.

## Not verified

- Boot 4.1.2 binding a list to a record component with `@Name("interface")`: the annotation target supports it, but
  it was not run. The note already plans it as the first test.
- That a missing `ObservationRestClientCustomizer` drops the trace header on a real call: inferred from Boot's
  sources, not probed. The fix in F2 adds the test that settles it.
