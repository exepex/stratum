# Judge verdict: C2b design note, injectable client beans (round 2, final)

**Note:** `design/http-client-libraries/c2-own-clients/c2b-design-note.md` (round 2 of the new design). **Base:** `main`
at `da23adc`. **Track:** Feature, design-note review (judge steps 1, 2, 3 and 5). **Judge model:** Fable 5.1 (round 1
was Opus 5.5). **Round 1:** `reviews/judge/C2b-client-beans-note.md`.

**FAIL**, on three should-fix findings, each one or two lines in the note: the `RestTemplate` must take the wrapped
factory through `requestFactoryBuilder`, not `requestFactory`, or a team's `spring.http.clients.*` key changes or
breaks a Stratum client; one span per call, not per attempt, must be stated; the own-engine client that ADR 0011 9.3
promised `.resilience` must be declared out of scope. All five round-1 fixes are accepted. Two rounds are done, so the
owner decides (judge.md, Rounds 3).

I read AGENTS.md and all six topic files. The owner's 18:10Z message makes the `RestTemplate` rows in scope.

## What I ran
- JDK 25 (apt), `./mvnw -pl starters/stratum-starter-http-client -am install -DskipTests` at `da23adc`: exit 0.
- `javap` on spring-boot-restclient 4.1.2, spring-boot-http-client 4.1.2, spring-web 7.0.10 and spring-beans 7.0.10.
- **Builder probe** (plain Java against those jars, deleted afterwards): Boot's `RestTemplateBuilder` with a wrapped
  request factory (`AbstractClientHttpRequestFactoryWrapper` around `HttpComponentsClientHttpRequestFactory`, read
  timeout 5 s) and Boot's `HttpClientSettings` as a team sets them:
  - `requestFactory(() -> wrapped)` with default settings: builds, factory kept.
  - `requestFactory(() -> wrapped)` with `read-timeout=10s`: builds, **and the inner HC5 factory's read timeout
    went from 5000 to 10000**.
  - `requestFactory(() -> wrapped)` with `connect-timeout=1s`: `IllegalStateException: ... HttpComponentsClientHttpRequestFactory
    does not have a suitable setConnectTimeout method`; with `redirects=dont-follow`: `IllegalStateException: Unable to
    set redirect follow using reflection`; a wrapper that does not extend Spring's wrapper class: `does not have a
    suitable setReadTimeout method`.
  - `requestFactoryBuilder(settings -> wrapped)` with `read-timeout=10s`: builds, inner timeout stays 5000.
  - `additionalCustomizers(rt -> rt.getClientHttpRequestInitializers().add(...))`: one initializer on the built
    `RestTemplate`.
- `javap -c`: `DefaultRestClient$DefaultRequestBodyUriSpec.exchangeInternal` and `RestTemplate.doExecute` both start
  the client observation before `ClientHttpRequest.execute()`.

## Round 1 fixes
| Finding | Result | Evidence |
|---|---|---|
| F1 `RestTemplate` gets checks, root URI, wrapped factory, initializers; customizer reach stated; test row | ACCEPTED | note s2 "The RestTemplate bean"; rows "carries the client's credential and base URL"; `StratumClientBuilders.java:45,48` is the shared set-up it extends. The builder call is wrong, finding 1; the initializer hook is unnamed, finding 4 |
| F2 every 4xx and 5xx carried as `RestClientResponseException`; last response given back | ACCEPTED | `RetryRules.java:96` retries 502/503/504/429 on it, `:153-155` counts 5xx and 429; other 4xx neither. The team's handlers see the unread last response, so `RestClient` and `RestTemplate` default status handling is unchanged (note s2 "After the last attempt") |
| F3 internal `StratumClientBeansRegistrar`, imported by the auto-configuration | ACCEPTED | `RestClientAutoConfiguration.java:41-42` shows the module switch and the existing `@Import` pattern; Spring calls `EnvironmentAware` on an `ImportBeanDefinitionRegistrar` |
| F4 docs split, changed lines listed | ACCEPTED | `docs/modules/http-client.md` is 491 lines; `:440-442` and `:477` are the lines; new page named. Two stale spots missed, finding 5 |
| F5 example comment, `PUT` and cut-off-body differences | ACCEPTED | note s1 comment and s2 bullets |

## Gap list (owner 18:06Z, 18:08Z, 18:10Z)
| Requirement | Result | Evidence |
|---|---|---|
| Inject a client's `RestClient` by constructor, setter or context lookup | MET | round-1 bean probe, unchanged design |
| A `RestTemplate` bean per client too, with the client's set-up and resilience | PARTLY | finding 1 |
| Retry and breaker from yml/properties and from Java | MET | `ClientResilience.newGuards` reads `retries`, `retry-delay`, `total-timeout`, `circuit-breaker`; `StratumClientCustomizer.customizeRetry/customizeCircuitBreaker` |
| Spring-native, no new pattern, no explicit wrapper | MET | standard `@Qualifier`, non-default candidates, no new API type |
| Breaker shared with the interfaces, counting the same failures | MET | F2 |
| Bodies streamed and sent once | MET | round-1 wrapper probe, design unchanged |
| Team's own beans unchanged | MET | round-1 bean probe |
| Documents and ADR | PARTLY | findings 3 and 5 |

## Findings
1. **Should fix, ST-AG-01, ST-ENG-34 and ADR 0011 (Boot's keys do not apply to a Stratum client).** The note builds the
   `RestTemplate` from Boot's `RestTemplateBuilder` bean and names `requestFactory(...)` ("Still open"). That bean
   carries `HttpClientSettings` from `spring.http.clients.*` (`RestTemplateBuilderConfigurer.setClientSettings`,
   `HttpClientAutoConfiguration.httpClientSettings`), and `requestFactory(Supplier)` applies them to the supplied
   factory by reflection. Builder probe: a team's `spring.http.clients.read-timeout=10s` silently sets the Stratum
   client's HC5 read timeout from 5 s to 10 s; `connect-timeout` or `redirects` fails startup with
   `IllegalStateException`. A hybrid service that sets these keys for its own clients is a real setup.
   **Fix:** `requestFactoryBuilder(settings -> wrappedFactory)` (probe: timeout stays, builds), and a test row:
   "a team's `spring.http.clients.read-timeout` leaves the client's `timeout` and startup unchanged".
2. **Should fix, ST-ENG-55, ST-DOC-01.** Through the beans, the retries run inside `ClientHttpRequest.execute()`, and
   both `DefaultRestClient.exchangeInternal` and `RestTemplate.doExecute` start the client observation before that
   call (`javap -c`). So a retried `GET` is one span and one `http.client.requests` observation whose status is the
   last answer, where an interface's call gives one per attempt (`ResilientExchangeAdapter` sits outside the
   `RestClient`). Not stated anywhere in the note. **Fix:** add it to the differences in s2 and the docs plan; no
   code change.
3. **Should fix, ST-AG-02, ST-DOC-08.** The design removes `resilience(name)`, which ADR 0011 9.3 (`:161`) promises to
   "a client with its own engine" and the module page lists as planned (`:477`). The note's ADR 0013 text replaces
   only ADR 0012 decision 3, and the options memo said the own-engine case "goes in an issue", but the note names no
   issue and no replacement line. **Fix:** ADR 0013 says an own-engine client gets `settings(name)` only, with the
   issue; the module page's Planned entry becomes that issue link.
4. **Minor, ST-AG-01.** `RestTemplateBuilder` has no request-initializer method (`javap`, 4.1.2), so "the client's
   request initializers" need a named hook: a `RestTemplateCustomizer` on the builder adding to
   `getClientHttpRequestInitializers()` (probe: works). Also s2 says the wrap happens "only" in
   `DefaultStratumHttpClients.build` and then "the RestTemplate through its builder": say where the `RestTemplate`'s
   factory is wrapped, or wrap in one method both call.
5. **Minor, ST-DOC-01, ST-AG-17.** Two more spots state the replaced decision: the pom comment
   `starters/stratum-starter-http-client/pom.xml:38` ("planned stratumHttpClients.resilience(name)") and the ADR index
   in `docs/README.md`, which needs the ADR 0013 row. `OwnClientsIntegrationTest` is 205 lines; 17 new rows will pass
   400 (ST-AG-10), so plan the nested group in its own class.

## Planted faults
None: a design-note review. The planted-fault column is consistent with its rows; findings 1 and 2 each need a row or a
line.

## Notes (not findings)
- The carrier `RestClientResponseException` takes a `byte[]` body: build it with no body and keep the response aside,
  so the team's error handler is the first to read the body.
- A `POST` with a body whose connection failed is retried through an interface (nothing was sent) but not through the
  bean (`streamedBody=true`); the "never resent" line covers it.
- The two beans are singletons made at startup, so a client under `clients` with no interface and no injection now
  opens its pool at startup instead of first use. Cheap; no finding.
- Security: nothing new read or logged; ST-SEC-01 to 06 unaffected. ST-AG-14: all eight covered.

Not merged; nothing in the repository changed. Probe files deleted; `git status` clean.
