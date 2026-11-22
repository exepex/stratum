# Design note: C2b, injectable client beans that retry and guard themselves (Feature track), round 3 of the new design

**Item:** ADR 0011 section 9.3, second half: retry and circuit breaker for clients Stratum did not build. Lane C.

**Owner decisions:**
- 18:06Z: teams autowire the client the normal Spring way, by constructor, setter or context lookup. Retry and breaker
  come from yml/properties or a Java customizer. A team's generated client gets them with no explicit wrapper.
- 18:08Z: keep it simple and Spring-native; no new integration patterns.

This replaces the round 1 wrapper design (`resilience(name).call`, kept as `c2b-design-note-r1-wrapper.md`). It also
reverses the 13:38Z decision "restClient never retries" (ADR 0012 decision 3).

**Owner, 18:10Z:** both a `RestClient` and a `RestTemplate` bean per client; docs steer new code to `RestClient`.
**Owner, 18:26Z:** every feature Stratum gives its clients reaches both beans, retry fully correct, no drop in quality
for the Spring-native design (parity table below).

**Base:** `main` at da23adc (B1, #256).

**Track:** Feature (new beans, a behaviour change of a public API, an ADR).

## 1. Who uses it, and does anything change for them?

**Who:** teams whose client Stratum did not build: a generated client that takes a `RestClient` (or a `RestTemplate`),
in new, existing and hybrid services. They write plain Spring:

```java
@Bean
PartnerOrdersClient partnerOrders(@Qualifier("partner") RestClient partner) {
  return new PartnerOrdersClient(partner);   // client partner's set-up; calls without a body are retried
}
```

The other Spring ways work the same:
- setter injection: `@Autowired void setPartner(@Qualifier("partner") RestClient c)`;
- a context lookup: `BeanFactoryAnnotationUtils.qualifiedBeanOfType(context, RestClient.class, "partner")`;
- `stratumHttpClients.restClient("partner")` returns the same object, for code that has only a name at run time.

**What changes:**
- A call through `restClient(name)` (#252) is now retried and guarded by the client's `retries`, `retry-delay`,
  `total-timeout` and `circuit-breaker`. A team that wants one attempt sets `retries: 0`. No service runs on Stratum
  yet (ST-AG-04).
- A client's interfaces are unchanged.
- A team's own unqualified `RestClient` or `RestTemplate` bean is unchanged (section 2).

**API:**
- No new public type.
- New beans: one `RestClient` and one `RestTemplate` per client under `stratum.http-client.clients`.
- Behaviour change: the `StratumHttpClients.restClient` Javadoc.
- One public API addition: `StratumClientCustomizer.customizeRestTemplate(String client, RestTemplateBuilder
  builder)`, a default no-op, so a team's per-client change reaches the `RestTemplate` as `customize` reaches the
  `RestClient` (owner 18:26Z, customizers on both).
- Internal, `@StratumInternal`: `ResilientRequestFactory` and `StratumClientBeansRegistrar` (new).

## 2. How is it built, and why this way?

### Beans: standard Spring qualifiers, nothing new to learn
`StratumClientRegistrar` cannot add them: `AbstractHttpServiceRegistrar.registerBeanDefinitions` is `final` and
`registerHttpServices` gets only the group registry (spring-web 7.0.10, judge r1 F3). A new internal
`StratumClientBeansRegistrar` (an `ImportBeanDefinitionRegistrar` and `EnvironmentAware`) does it, imported by
`RestClientAutoConfiguration`, so `stratum.http-client.enabled=false` turns it off with the rest. It reads every client
at bean-definition time from properties, YAML and Java customizers (`HttpClientProperties.resolve`), as the other
registrar does for `interfaces`, and registers two beans per client name.

**The `RestClient` bean:**
- named `stratum.http-client.<name>.rest-client`;
- with an `AutowireCandidateQualifier(Qualifier, name)` and `setDefaultCandidate(false)`. spring-beans 7.0.10 has
  both; I checked with `javap`;
- its supplier returns `stratumHttpClients.restClient(name)`, the same cached object.

**The `RestTemplate` bean (judge r1 F1, r2 F1 and F4):**
- named `stratum.http-client.<name>.rest-template`, with the same qualifier;
- built once, from Boot's `RestTemplateBuilder` bean (spring-boot-restclient 4.1.2, already a dependency), so Boot's
  message converters and `RestTemplateCustomizer` beans apply, the observation one included;
- its factory is set with `requestFactoryBuilder(settings -> wrappedFactory)`, never `requestFactory(...)`: the
  latter applies a team's `spring.http.clients.*` settings to Stratum's factory by reflection (judge r2 builder probe:
  `read-timeout=10s` changed the client's 5 s, `connect-timeout` and `redirects` stopped startup). With
  `requestFactoryBuilder` Boot's settings are handed to a builder that ignores them, so only `stratum.http-client`
  decides (ADR 0011);
- base URL: `rootUri(url)`; the checks `SX-HTTPC-1001` and `SX-HTTPC-1004` as for the `RestClient`;
- then `StratumClientCustomizer.customizeRestTemplate(client, builder)` for each customizer, in order.

**One set-up for both (judge r2 F4).** `StratumClientBuilders` gains `beanRequestFactory(client)`, the one method
both beans call: the client's factory (its pool, or C3's mock factory) wrapped in `ResilientRequestFactory`, which
carries the client's Stratum initializers (the credential now; lane A's headers and token later). The initializers run
inside the wrapper, on each attempt's fresh request, not on the builder: so each attempt has the current token, and a
401 resend (below) sends a new one. `RestTemplateBuilder` has no initializer method (judge r2 `javap`), so this also
removes the need for a `RestTemplateCustomizer` to carry them. Interfaces keep `configure(client, RestClient.Builder)`
as today, unwrapped (their retry is the exchange decorator).

A bean that is not a default candidate is injected only where a qualifier names it. So an unqualified `RestClient` in
a team's code still gets the team's own bean, or Boot's error when none exists, never ours. A dotted bean name cannot
clash with a `@Bean` method's name.

### Retry and breaker inside, without buffering
`DefaultStratumHttpClients.build` wraps the client's request factory in `ResilientRequestFactory`. It does this only
there, not in `StratumClientBuilders.configure`, so an interface is never retried twice.

Both beans get it from `StratumClientBuilders.beanRequestFactory(client)`: the `RestClient` in
`DefaultStratumHttpClients.build`, the `RestTemplate` in its builder. The wrapper's request runs `execute()` through B1's `ClientResilience.call(client, method, streamedBody,
streamedDownload=true, attempt)`:
- **Each attempt** makes a fresh request from the client's own factory, copies the outer request's headers and
  attributes (the team's own headers), runs the client's Stratum initializers on it (the credential), and executes it.
- **A request with no body** (`GET`, `HEAD`, `DELETE`, `OPTIONS` without one) is retried under B1's rules.
  A `PUT` with a body is sent once here, though an interface retries it (judge r1 F5); the docs list this.
- **A request with a body is sent once** (`streamedBody=true`, so B1 never retries it). The body goes to the one real
  request as it is written, through `StreamingHttpOutputMessage`, so uploads keep streaming (C1's
  `restClientOfClientStreams` stays green). The breaker still counts and refuses such a call.
- **Failures are translated for B1**, which decides on `RestClientException`s. Inside the attempt:
  - an `IOException` becomes a `ResourceAccessException`;
  - **every error answer, 4xx and 5xx,** becomes a `RestClientResponseException` that carries the response, its
    status and its `Retry-After` (judge r1 F2). So B1's `RetryRules` decide on it exactly as for an interface: 503
    is retried, every 5xx and 429 counts against the shared breaker, other 4xx neither. A 2xx or 3xx answer is a
    success.
- **After the last attempt** the wrapper gives Spring back what it would have seen with no wrapper: the `IOException`,
  or the last response. The team's status handlers and converters then run as before.
- **Each response it drops is closed**, which frees its connection.
- **`streamedDownload=true`:** the answer is read after `execute()` returns, outside the attempt. So the answer
  timeout stays `timeout`, the gap between bytes, as today (C1). `total-timeout` bounds the pool waits, connects,
  retries and the wait for headers. So a body cut off after the headers is neither retried nor counted by the
  breaker, unlike an interface's (B1's cut-off-body rule); the docs list this (judge r1 F5).
- **One span and one metric per call (judge r2 F2).** `RestClient` and `RestTemplate` start their observation before
  `ClientHttpRequest.execute()` (judge r2 `javap -c`), and the retries run inside it. So a retried call through a bean
  is one span and one `http.client.requests` observation, tagged with the last answer, where an interface gives one per
  attempt. The docs list this; the retry count shows in B1's retry metrics.
- **The carrier exception** is built with no body; the response is kept aside, so the team's error handler is the first
  to read the body (judge r2 note).

### Rejected
- (a) An interceptor: it buffers every body (ADR 0012, C1 probe).
- (b) Spring 7 `@Retryable`: no breaker and no "POST already sent" rule (c2b-options.md).
- (c) A Stratum annotation with AOP: a new dependency, and a verb the advice cannot see (c2b-options.md).
- (d) Registering the beans as default candidates: two `RestClient` beans would break a team's unqualified injection.

### Package
`ResilientRequestFactory` goes in `com.exepex.stratum.restclient`, next to `ClientResilience`. It imports Spring web
and B1's types only, so there is no new package edge and no cycle.

### 401 resend (ADR 0012 decision 3)
Lane A's `ServiceTokenResend` is not in `main`. Its A1 note gives it a call form that `ClientResilience.call`'s callers
use per attempt. The wrapper runs each attempt through it, inside the breaker: on a 401 the token is renewed and the
attempt sent again, with the new token, since the initializers run on each fresh request. A call with a body is not
resent (its body is sent once), so a 401 on it reaches the team; the docs list this. Whichever of C2b and A1b lands
second wires it, with the test row "a GET answered 401 then 200 through the bean sends a new token". Until then the docs
list it under Planned.

### Errors
Spring's exceptions stay (ADR 0012). Only a call refused by an open breaker changes: B2's `ClientResilience.rethrow`
throws `SX-HTTPC-0003` (503), as agreed with lane B at 17:52Z. Whichever merges second adjusts the breaker test's
expected exception.

### Parity: what each Stratum feature gives the beans (owner 18:26Z)
"Wrapper" is the round 1 design (`resilience(name).call` around a `restClient(name)` call). "Beans" is this design.
| Feature | Interface | Wrapper (old) | Beans (`RestClient` and `RestTemplate`) | How the beans get it |
|---|---|---|---|---|
| Base URL, `timeout`, `connect-timeout`, pool, `max-wait`, lifetime | yes | yes | yes | the client's factory; `rootUri` for `RestTemplate`; Boot's `spring.http.clients.*` cannot change it (r2 F1) |
| `ssl-bundle` (mutual TLS) | yes | yes | yes | the client's pool |
| Credential: basic, bearer, api-key; lane A's tokens | yes | yes | yes | initializers inside the wrapper, per attempt |
| New token after a 401 (lane A) | yes | planned | yes, bodiless calls | `ServiceTokenResend` per attempt in the wrapper; a call with a body is not resent |
| Retry: idempotent methods, 429/502/503/504, `Retry-After`, back-off, `retries`, `retry-delay` | yes | yes (team passes the verb) | yes, bodiless calls | B1's `ClientResilience.call` and `RetryRules`; the verb comes from the request itself, so no team can pass a wrong one |
| A `POST` or `PUT` never resent after it was sent; uploads streamed | yes | yes | yes, stricter: a body is never resent | `streamedBody=true` for any call with a body |
| `total-timeout` over attempts and waits | yes | yes | yes | `AttemptTimeLeft` per attempt; the answer timeout stays `timeout` |
| Circuit breaker, one per downstream, 5xx and 429 counted | yes | yes | yes | B1's guards by client name, shared; every error answer carried (r1 F2) |
| Breaker open: `SX-HTTPC-0003` (503) | yes | yes | yes | B2's `ClientResilience.rethrow`, which the wrapper calls |
| Other errors | B2's codes | Spring's (ADR 0012) | Spring's (ADR 0012) | a generated client's own error handling expects Spring's exceptions; the team's status handlers see the last answer |
| Error body cap, 64 KB (lane B, #258) | yes | yes | yes | lane B's `ErrorBodyLimit` is an Apache exec handler in the client's pool (`ClientConnectionPools`), which each attempt's request uses; confirmed with lane B 18:30Z |
| Tracing span, `http.client.requests` metric, span query mask (#240) | per attempt | per call | per call (r2 F2) | Boot's observation customizers: `RestClientCustomizer` on the `RestClient`, `RestTemplateCustomizer` through `RestTemplateBuilder`; the mask is the shared convention bean |
| Pool metrics | yes | yes | yes | the same pool |
| Call log line and meters (B3, planned) | when built | when built | when built | B3 logs in `ClientResilience.call` or the client's factory, not in an exchange decorator (agreed with lane B 18:30Z) |
| Headers and `ClientInterceptor` (A2, planned) | when built | when built | when built | A2's initializers join the wrapper's list; its interceptor runs in the wrapper's `execute()` (A2 note :120, :178) |
| `StratumClientCustomizer` | yes | yes | yes | `customize` on the `RestClient` builder, new `customizeRestTemplate` on the `RestTemplate` builder |
| `mock: true` (C3) | yes | 501 | 501, as `restClient(name)` today | the wrapper wraps C3's mock factory; mocks answer interface methods only |
| Settings from yml, properties and Java | yes | yes | yes | `HttpClientProperties.resolve`, no new key |
No feature is lost against the wrapper. The beans keep two differences from an interface, both safer or by an earlier
decision: a body is never resent, and errors stay Spring's. The retry count per call shows in B1's retry metrics rather
than as several spans.

## 3. Is it safe and fast?
- **Speed:** one extra request object per attempt; no body copy.
- **Throughput:** no thread hop and no lock; the client's own pool.
- **Log volume:** none added.
- **Memory and leaks:** two beans per configured client; dropped responses are closed; B1's guards are reused, so
  there is one breaker per downstream, shared with the interfaces.
- **Data leaks and security:** nothing new is read or logged; no secret in a bean name.
- **Maintainability:** B1's rules stay in one place; the wrapper only translates failures.
- **Scalability:** per-client state only, bounded by the configured clients.

## 4. Which rules and owner decisions does it follow?
- Owner 18:06Z (autowiring, yml/Java, no wrapper) and 18:08Z (Spring-native, simple).
- ADR 0011 9.3.
- ADR 0012: decision 3 (errors, 401) and its interceptor finding.
- ST-ENG-51: bodies sent once; idempotent retries.
- ST-ENG-34: settings from files and Java; no new key.
- ST-ENG-56/57: no new API type; the internal class is labelled.
- ST-ENG-42/46/47.

**New ADR 0013** (ADRs are never edited): "A client's `RestClient` and `RestTemplate` are beans, injected by
`@Qualifier("<name>")`, and retry and guard their calls by the client's settings; a call with a body is sent once.
This replaces ADR 0012 decision 3's 'sends each call once' and its `resilience(name)` wrapper (owner, 2026-11-09).
A client with its own engine, such as a vendor SDK, gets `stratumHttpClients.settings(name)` only; its retry and
breaker are issue #<n> (judge r2 F3; replaces ADR 0011 9.3's `.resilience`)." The issue is opened before the code PR,
with the own-engine case of the round 1 judge (F1: exception types the rules do not know). `docs/README.md` gets the
ADR 0013 row; the module page's Planned entry for `resilience(name)` becomes that issue's link (r2 F5).
The pom comment `starters/stratum-starter-http-client/pom.xml:38` ("planned stratumHttpClients.resilience(name)")
changes to name the beans (r2 F5).

**Docs (judge r1 F4):** `docs/modules/http-client.md` is 491 lines, so the section "Clients Stratum did not build"
moves to its own page, `docs/modules/http-client-own-clients.md`, linked from the module page and from
`docs/README.md`. The new page has:
- the two beans, the qualifier, constructor, setter and context lookup, and new code steered to `RestClient`;
- a team's own `@Bean @Qualifier("<name>")` replaces Stratum's; a lookup by type alone
  (`context.getBean(RestClient.class)`) finds Stratum's bean when it is the only one;
- what is retried, `retries: 0`, and the two differences from an interface: a body is never resent (a `PUT` with a
  body included), and a body cut off after the headers is neither retried nor counted;
- `StratumClientCustomizer` reaches the `RestClient` only.
- an interface method returning `Optional` gets `Optional.empty()` on a 404 (lane B's B2b) only through the interfaces;
  the beans keep Spring's `HttpClientErrorException.NotFound` (one line, whichever of C2b and B2b lands second).
Lines that change on the module page: :440-442 ("sends each call once"; "fails at the first `restClient` call", which
becomes a startup failure, as the beans are made at startup) and :477 (Planned `resilience(name)`).

## Decisions and their tests
`OwnClientsIntegrationTest` is 205 lines, so the rows go in a new class, `ClientBeansIntegrationTest`, with `@Nested`
groups `Injection`, `Retry`, `Breaker`, `RestTemplateBean` (r2 F5).

| Decision | Test (`ClientBeansIntegrationTest`) | Planted fault |
|---|---|---|
| `@Qualifier("partner") RestClient` is injected, the same object as `restClient("partner")` | constructor-injected test bean | qualifier not added |
| Setter injection and `qualifiedBeanOfType` work | same bean, setter; context lookup | - |
| A team's unqualified `RestClient` bean still injects alone | app with its own `RestClient` bean | `defaultCandidate` left true |
| A `GET` answered 503 then 200 returns the 200 | `ScriptedDownstream` | wrapper not added |
| A `POST` with a body answered 503 is sent once | path count 1 | body calls retried |
| An upload through the bean streams | C1's `restClientOfClientStreams` | body buffered in the wrapper |
| The team's status handler sees the last 503 | customizer mapping 503 | carrier exception leaks |
| Breaker opened via the interface refuses the bean's call | B1 breaker settings | a breaker per caller |
| An interface's call is not retried twice | attempt count on an interface | wrapper added in `configure` |
| Dropped responses free their connection | `max-connections: 1`, 503, 503, 200 | response not closed |
| `retries: 0` sends once | path count 1 | - |
| `RestTemplate` bean: 503 then 200 retried, injected by qualifier | same, `RestTemplate` | not wrapped |
| `RestTemplate` call carries the client's credential and base URL (r1 F1) | client with `auth: bearer`, relative path | initializers not passed |
| 500s through the bean open the breaker (r1 F2) | B1 breaker settings, 500 answers | only retried statuses carried |
| A 404 through the bean neither retries nor counts | path count 1, breaker closed | every 4xx counted |
| A team's own `@Qualifier("partner") RestClient` bean wins | app with that bean | - |
| A client without `url` stops startup | `SX-HTTPC-1004` at startup | - |
| A team's `spring.http.clients.read-timeout` and `connect-timeout` leave the `RestTemplate`'s `timeout` and startup unchanged (r2 F1) | the timeout row's slow answer still fails at `timeout` | `requestFactory(...)` used |
| A retried `GET` is one `http.client.requests` observation (r2 F2) | `TestObservationRegistry`, 503 then 200 | - |
| `customizeRestTemplate` reaches the `RestTemplate` | a customizer adding a header | hook not called |
| A `RestTemplate` call is traced like a `RestClient` call | `TestObservationRegistry` | Boot's builder not used |

## Not verified
Round 1's three items were verified by the judge's probes (qualifier, carrier `Retry-After`, `RestTemplateBuilder`),
and round 2's builder probe settled `requestFactoryBuilder`. Still open: the span query mask (#240) applying to the
`RestTemplate`'s observation through the shared convention bean; the first test checks it.

## Round 2 findings (reviews/judge/C2b-client-beans-note-r2.md)
F1 `requestFactoryBuilder`, with a test row. F2 one span and one metric per call, stated, with a row. F3 ADR 0013 text
for own-engine clients, with the issue. F4 one method `beanRequestFactory` for both beans; initializers inside the
wrapper, so no `RestTemplateCustomizer` hook is needed for them. F5 pom comment, ADR index, new test class. Owner 18:26Z:
parity table, `customizeRestTemplate`, 401 resend per attempt in the wrapper.

## Round 1 findings (reviews/judge/C2b-client-beans-note.md)
F1 the `RestTemplate` gets the checks, base URL, wrapped factory and initializers from `StratumClientBuilders`; the
customizer's reach stated; test row added. F2 every error answer is carried, so B1's rules count 5xx and 429; two
rows added. F3 a new internal `StratumClientBeansRegistrar`, imported by the auto-configuration. F4 own-clients page
split out, changed lines listed. F5 the example comment, the `PUT` and cut-off-body differences in the docs plan.
Notes: type-only lookup and the team's own qualified bean go in the docs, with a test.
