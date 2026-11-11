# Judge verdict: C2b design note, injectable client beans (round 1)

**Note:** `design/http-client-libraries/c2-own-clients/c2b-design-note.md` (new design, round 1). **Base:** `main` at
`da23adc`. **Track:** Feature, design-note review (judge steps 1, 2, 3 and 5). **Judge model:** Opus 5.5.

**FAIL**: the `RestTemplate` bean as written would not carry the client's credential, and the bean `RestClient`'s
breaker would not count a 500.

I read AGENTS.md and all six topic files. RestTemplate rows are in scope (owner, 18:10Z: support both).

## What I ran
- JDK 25, `./mvnw -pl starters/stratum-starter-http-client -am install -DskipTests`: success. Probes in the scratchpad,
  deleted afterwards, against spring-beans/spring-web 7.0.10, Boot 4.1.2, httpclient5 5.6.5.
- **Bean probe** (registrar adds a `RestClient` and a `RestTemplate` definition with
  `AutowireCandidateQualifier(Qualifier, "partner")` and `setDefaultCandidate(false)`):
  constructor `@Qualifier` → ours; setter → ours; `qualifiedBeanOfType` → ours; `@Qualifier RestTemplate` → ours;
  team's own unqualified `RestClient` → team's; no team bean + unqualified → `NoSuchBeanDefinitionException` (also for
  `RestTemplate`); `List<RestClient>` empty, `ObjectProvider` null; a later auto-configuration's
  `@ConditionalOnMissingBean RestClient`/`RestTemplate` still creates its bean; a team's own
  `@Bean @Qualifier("partner") RestClient` wins over ours.
- **Wrapper probe** (prototype of the note's factory: outer `AbstractStreamingClientHttpRequest`, fresh inner HC5
  request per attempt, headers copied, body handed through `setBody`, bodied calls once; pool of 1 connection;
  `-Xmx64m`): GET 503→200 returns 200; 400 MB upload as `InputStreamResource` and as a `Body` lambda arrive whole and
  `chunked` (no buffering); JSON POST answered 503 hit once; GET always-503 hit 3 times on a 1-connection pool, so
  dropped responses were closed; the team sees the last 503 as `HttpServerErrorException`. Same for `RestTemplate`.
- **Rules probe** (main's `RetryRules`): a `RestClientResponseException` carrier with `Retry-After: 3` gives a 3 s
  wait and `shouldRetry` true for GET; `countsAsBreakerFailure` is true for 500 and 501, false for 404.

## Gap list
| Requirement (owner 18:06Z, 18:08Z, 18:10Z) | Result | Evidence |
|---|---|---|
| Inject a client by constructor, setter or context lookup | MET | bean probe |
| Team's unqualified `RestClient`/`RestTemplate` unchanged; no clash with company beans | MET | bean probe, cases 4 to 7 |
| Retry/breaker from yml/properties, same client keys | MET | note s2; `ClientResilience.newGuards` reads `retries`, `retry-delay`, `total-timeout`, `circuit-breaker` |
| Retry/breaker from a Java customizer | MET | `StratumClientCustomizer.customizeRetry/customizeCircuitBreaker` keyed by name |
| Generated `RestClient` client gets resilience with no wrapper | MET | wrapper probe |
| Generated `RestTemplate` client gets the client's set-up and resilience | PARTLY | F1 |
| Breaker shared with the interfaces, counting the same failures | NOT MET | F2 |
| Uploads stream, a body is sent once | MET | wrapper probe, 400 MB at 64 MB heap |
| Spring-native, nothing new to learn | MET | standard `@Qualifier`, non-default candidates |
| Note's "not verified" items | MET | qualifier: bean probe; carrier `Retry-After`: rules probe; `RestTemplateBuilder` in spring-boot-restclient 4.1.2, starter depends on `spring-boot-starter-restclient` (pom.xml:28) |

## Findings
1. **Blocking, requirement (owner 18:10Z), ST-ENG-34.** The note gives the `RestTemplate` only "the same request
   factory". The client's credential is a request initializer on the `RestClient` builder
   (`StratumClientBuilders.java:47-49`), and `StratumClientCustomizer.customize` takes a `RestClient.Builder` only.
   So a client with `auth:` sends no token through its `RestTemplate` (wrapper probe: `token=null` for RestTemplate,
   `token=t` for RestClient), and the base URL and the team's per-client customizers do not reach it either.
   **Fix:** say how the `RestTemplate` gets the client's initializers (auth now, A2's headers later) and base URL
   (root URI), state that `StratumClientCustomizer.customize` does not reach it (or how a team adjusts it), and add a
   test row: `RestTemplate` call carries the client's credential.
2. **Blocking, requirement "guarded like an interface", B1 rules.** The note turns only "a retried status (429, 502,
   503, 504)" into a failure inside the attempt. Every other answer returns normally, so the breaker records it as a
   success. `RetryRules.countsAsBreakerFailure` counts every 5xx and 429 (rules probe: 500 true, 501 true). A
   downstream answering 500 opens the breaker through its interface but never through the bean, and the bean's 500s
   lower the shared breaker's failure rate. **Fix:** carry every error answer (4xx and 5xx) as the
   `RestClientResponseException` inside the attempt, so B1's rules decide on it exactly as for an interface, and give
   back the last response afterwards as planned. Test row: 500s through the bean open the breaker.
3. **Should fix, accuracy (ST-AG-01).** "`StratumClientRegistrar` ... also registers two beans" cannot work as
   written: `AbstractHttpServiceRegistrar.registerBeanDefinitions` is `final` and `registerHttpServices` gets only the
   `GroupRegistry` (javap, spring-web 7.0.10). So "No new type" is also wrong for internal types. **Fix:** name the
   real hook (for example a second `ImportBeanDefinitionRegistrar` imported by `RestClientAutoConfiguration`, so the
   module switch applies), labelled `@StratumInternal`.
4. **Should fix, ST-AG-10/ST-DOC-09, ST-DOC-01.** `docs/modules/http-client.md` is 491 lines. The new beans, the
   `RestTemplate`, the retry rules and the differences from interfaces will push it over 500. **Fix:** plan the split
   in the note (for example "Clients Stratum did not build" into its own page, linked). Also list the lines that
   change: :440-442 ("sends each call once"; "fails at the first `restClient` call", which becomes a startup failure
   now that the beans are created at startup) and :477 (Planned `resilience(name)`).
5. **Minor, accuracy.** The example comment "retried and guarded like an interface of client partner" overstates it.
   Through the bean a `PUT` with a JSON body is never retried (rules probe: `streamedBody=true` → false; the interface
   retries it), and a body cut off after the headers is read outside the attempt, so it is neither retried nor
   counted, unlike B1's cut-off-body rule (`RetryRules.isRetryable`). **Fix:** say "retries calls without a body" and
   list these two differences in the docs plan.

## Planted faults
None: a design-note review (judge.md, steps 1, 2, 3 and 5). The planted faults in the note's test table are
consistent with its rows; F1 and F2 each need one new row.

## Notes (not findings)
- `context.getBean(RestClient.class)` returns Stratum's bean when it is the only `RestClient` (bean probe): lookup by
  type alone ignores `defaultCandidate`. Only code that does a type-only lookup sees it; worth one doc line.
- A team's own `@Bean @Qualifier("partner") RestClient` replaces Stratum's (bean probe). Worth a doc line and a test
  in place of `@ConditionalOnMissingBean`, which a per-name bean cannot use.
- Security: nothing new is read or logged; ST-SEC-01 to 06 unaffected. ST-AG-14: all eight covered.

Not merged; nothing in the repository changed.
