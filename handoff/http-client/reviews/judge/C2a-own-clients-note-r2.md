# Judge: C2a design note, clients Stratum did not build (Feature track, note review, round 2)

**Note:** `design/http-client-libraries/c2-own-clients/c2-design-note.md` (round 2; round 1 kept as
`c2-design-note-r1.md`). **Base:** `main` at `aabdae8`. **Judge model:** Claude Fable 5.1 (round 1 was Opus).

**Verdict: FAIL**, on two should-fix findings and two minors. Every round-1 finding (F1 to F5) is fixed properly, not
patched. The two new findings are a written rule the note's key design collides with (F1) and a build check the
note's API type would fail (F2). Neither changes the shape of the design. Nothing was merged or changed.

I read AGENTS.md and all six topic files (engineering, testing, security, documentation, review, judge). Steps 1, 2,
3 and 5 of `judge.md`, applied to the note. No build: a note review. One binding probe was run (below).

## What I checked

- `main` at `aabdae8`: `StratumClientGroups`, `StratumClientRegistrar`, `StratumClientChecks`,
  `ClientConnectionPools`, `RestClientAutoConfiguration`, `HttpClientProperties`, `ClientSettings`,
  `HttpClientErrorCode`, `HttpClientConstants`, `RestClientArchTest`, `HttpClientArchTest`,
  `ForeignClientIntegrationTest`, `ClientCustomizerIntegrationTest`; `stratum-test`'s `PublicApiRule` and
  `ErrorCodeChecks`; `docs/modules/http-client.md` (306 lines), `docs/guides/error-codes.md`, ADR 0011.
- Sibling notes A1 (round 2) and B1 (round 2): codes, `ServiceTokenResend.call`, `ClientResilience.call`,
  `ClientExchangeDecorator`, the predicate contract ("lane C widens that one predicate").
- Spring Boot 4.1.2 and spring-web 7.0.10 sources from Maven Central:
  - `RestClientCustomizerHttpServiceGroupConfigurer`: order 0, `customizers.orderedStream()` on every group;
  - `RestClientObservationAutoConfiguration`: `@ConditionalOnBean({RestClient.Builder.class,
    ObservationRegistry.class})`, contributes the `ObservationRestClientCustomizer`;
  - `RestClientAutoConfiguration`: `@ConditionalOnClass(RestClient.class)` only; `NotReactiveWebApplicationCondition`
    is used by `RestTemplateAutoConfiguration` alone (line 35);
  - `@Name`: `@Target({FIELD, PARAMETER})`; `ValueObjectBinder` reads it from constructor parameters (line 343);
    the configuration processor honours it (`PropertyDescriptorResolver`, line 97);
  - `AbstractHttpServiceRegistrar` registers one proxy bean per group and type, named `group#type` (line 162).
- **Probe (Boot 4.1.2 `Binder`, JDK 21):** a record `ClientSettings(String url, @Name("interface") List<String>
  interfaces)` with a compact constructor binds `clients.partner.interface[0]`/`[1]` to a two-element list;
  `clients.wrong.interfaces[0]` binds nothing (no alias); `clients.other.interface=a.B,c.D` binds two elements.
  This settles the note's first "Not verified" item: the binding works.

## Round-1 findings

| Finding | Result | Evidence |
|---|---|---|
| F1 codes clash with A1 | Fixed | 1007 and 1008; A1 note holds 1005 and 1006; 1009 reserved for C3 |
| F2 Boot's customizers first | Fixed | `RestClient.builder()`, then `ObjectProvider<RestClientCustomizer>` in order, then `configure`; matches Boot's group path (order 0 then Stratum's 1); test row with `TestObservationRegistry` |
| F3 which groups are Stratum's | Fixed | one predicate `isStratumClient(group)`: annotated type or a type the client's `interface` list names, never the name alone; B1 note uses it; decorator row planned once B1 is on `main` |
| F4 `resolve(environment)` | Fixed | stated; Java row is an integration case through `spring.factories` |
| F5 per-client interceptors | Fixed | stated for the note and the module page |

## Gap list

| Requirement | Result | Evidence |
|---|---|---|
| 9.1 `clients.<name>.interface` joins a generated interface | MET in design (F1 below) | registrar registers named types in the client's group; 1007, 1002, 1008 checks |
| 9.2 `restClient(name)` with the client's set-up | MET in design (F2 below) | same `StratumClientBuilders.configure`, cached per name, no interceptor |
| 9.3 `settings(name)` | MET | `HttpClientProperties.clientSettings` |
| 9.3 `resilience(name)` | Out of scope, C2b | needs B1's `ClientResilience.call`; owner's option A (13:38Z) stated |
| No request interceptor (owner) | MET | no interceptor row; C1 chunked-upload check through `restClient` |
| `restClient(name)` never retries, breaks or resends a 401 by itself (owner, option A) | MET | stated in the API section; A1 note agrees (`ServiceTokenResend.call` under `resilience(name)`) |
| `isStratumClient` owned by lane C, never by name alone (lanes B and C) | MET | section 2 and the F3 row |
| Never wrap, replace or check a foreign client; unused blocks allowed (owner 11-08) | MET | `ForeignClientIntegrationTest` unchanged; `restClient("x")` with no block fails at first use only |
| Boot-check boundary (11-06) | MET | 1007/1008 read configuration and resolve only the classes it names |
| Every key in Java (ST-ENG-34) | MET | `HttpClientPropertiesCustomizer`, integration case |
| Codes unique across lanes | MET | 1007/1008 vs A1 1005/1006, C3 1009 |
| Rules the key design touches (ST-ENG-30) | NOT MET | F1 |
| Public API exposes only API (ST-ENG-56, build check) | NOT MET | F2 |

## Findings

**F1, should fix: ST-ENG-30 forbids the `@Name` the note relies on.** `docs/agents/engineering.md:178-180`: "A
component's name, in kebab-case, is its property key, so components are not renamed with Spring Boot's `@Name`".
The note's Keys section renames `interfaces` to `interface` with `@Name`, and names no rule and no owner decision
for it. The rule's reason (ST-ENG-31's error names the component) does not bite here, since the only check on the
key is the method-named `defaults-without-interface`, and the probe shows the binding works; but a written rule
holds until the owner decides otherwise (ST-AG-04). Fix: one line in the note citing ST-ENG-30, and the owner's
choice before code: (a) a one-key exception written into ST-ENG-30 (ST-AG-13), because `interface` is a Java
keyword and ADR 0011 fixed the key's name, or (b) the key `interfaces`. I recommend (a).

**F2, should fix: `StratumHttpClients` as an API class cannot take its collaborators.** The note makes
`StratumHttpClients` API and `StratumClientBuilders` internal, with the bean made in the `autoconfigure` package, so
its constructor is public and takes `StratumClientBuilders` (and `HttpClientProperties`). `PublicApiRule.apiExposesOnlyApi`
checks "the public and protected constructors, methods and fields of each API type" (`PublicApiRule.java:48-50`,
`memberExposures` at 137-150, `VISIBLE` = public, protected), so `RestClientArchTest` fails at the first build. Fix,
stated in the note: either `StratumHttpClients` is an API interface with the two methods and an `@StratumInternal`
class implements it (which also makes `@ConditionalOnMissingBean` replacement natural), or its constructor takes
only API and JDK types, such as `HttpClientProperties` and a `Function<String, RestClient>` the auto-configuration
builds from `StratumClientBuilders`. Either is small; the note picks one and adds the type to the API list.

**M1, minor: a wrong library fact, carried over from the round-1 verdict.** The note says Boot's `RestClient.Builder`
bean "is absent in a reactive web application (`NotReactiveWebApplicationCondition`, Boot's
`RestClientAutoConfiguration`)". In 4.1.2 that condition is on `RestTemplateAutoConfiguration` only; `RestClient.Builder`
exists in reactive applications too. The design holds for a better reason: applying the customizer beans directly is
exactly Boot's group path, and a team's own `RestClient.Builder` bean (which makes Boot's back off) must not shape
Stratum's clients. Fix: correct the sentence (ST-AG-01). The round-1 judge gave the wrong source; I checked it.

**M2, minor: Documents omit `docs/guides/error-codes.md`.** 1007 and 1008 need rows there, and
`ErrorCodeChecks.checkErrorCodes` fails the build when the page's codes differ from the enum's or a Fix cell does not
start with `getFix()` (`ErrorCodeChecks.java:40-57`). Add the page, and `ConfigurationMetadataTest` for the key, to
the Documents line.

## Planted faults
None: a note review.

## Notes (not findings)

- Boot binds `interface=a.B,c.D` as two entries by default (probe). "No comma syntax" is therefore what the docs
  show, not what the code rejects; nothing needs to reject it, since each entry is still checked by 1007.
- A type named in two clients' `interface` lists registers as `a#Type` and `b#Type` (spring-web line 162), and
  injection by type fails with Spring's own "no unique bean" error. Configuration-only; 1008 could cover it if the
  registrar already sees the type twice, but Spring's message is clear enough that this is not required.
- `RestClientObservationAutoConfiguration` needs an `ObservationRegistry` bean, so the F2 test row holds only with
  one; the planned `TestObservationRegistry` provides it.
- The registrar now runs `HttpClientProperties.resolve` (validation included) a third time at startup; cheap, and a
  `StratumException` thrown from the registrar already reaches the failure analyzer for 1002 and 1003 in H1.

## Not verified
- An OpenAPI-generator `spring-http-interface` output compiling against Spring 7 (the note says the same).
- The `TestObservationRegistry` path for `restClient(name)`: inferred from Boot's sources, settled by the planned test.
