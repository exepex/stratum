# Design note: C2, clients Stratum did not build, on the blocking HTTP client starter (Feature track), round 3

**Item:** "foreign clients" (ADR 0011 section 9, Follow-up items). Lane C, on the owner's message of 2026-11-09 13:26Z.
Base: `main` at aabdae8 (H1). Track: Feature (new key, new public API).

**In C2a (this note):** ADR 9.1, the `interface` key (built as `interfaces`, owner 13:49Z); ADR 9.2, `stratumHttpClients.restClient("<name>")`; ADR 9.3's
first half, `stratumHttpClients.settings("<name>")`.
**C2b, after lane B's B1 merges:** ADR 9.3's second half, `stratumHttpClients.resilience("<name>").call(...)`, on
B1's `ClientResilience.call(client, attempt)` (coordinator, 13:35Z), which is not tied to the proxy. It needs
B1's code, so it is its own note.
The wrapper runs the same steps a `@StratumClient` call gets around its exchange: retry, breaker and lane A's
`ServiceTokenResend` (the 401 resend for `client-credentials`, ADR 3.3; A1 judge F5, coordinator 13:38Z). Each step
re-runs the team's whole call, so the request is built again and lane A's initializer puts the fresh token on it;
nothing is buffered. Without the wrapper, `restClient(name)` sends once and a 401 comes back as it is.

**Owner decision (2026-11-09 13:38Z, option A):** `restClient(name)` does not retry by itself; the team wraps the
call with `resilience(name)` from C2b. Why: Spring can retry inside a `RestClient` only with
a `ClientHttpRequestInterceptor`, and any interceptor makes Spring buffer every request body (C1 probe,
spring-web 7.0.10 `InterceptingClientHttpRequest extends AbstractBufferingClientHttpRequest`).

## 1. Who uses it, and does anything change for them?
Teams with OpenAPI-generated interfaces, generated clients that take a `RestClient`, or vendor SDKs; new, existing or
hybrid, with Stratum as parent or next to a company framework. Nothing changes for anyone who sets no `interfaces`
and calls no `stratumHttpClients` (test: H1's suite unchanged). API: `StratumHttpClients` (interface); internal:
`DefaultStratumHttpClients`, `StratumClientBuilders`. Lines: `interfaces: [com.partner.OrdersApi]`, or `stratumHttpClients.restClient("partner")`.

## 2. How is it built, and why this way?
`interfaces`: H1's `StratumClientRegistrar` also registers each class a client names, in that client's group, so the
interface gets everything a `@StratumClient` gets (lane B's decorators included). `restClient(name)`: one `RestClient`
per name, built once with the same set-up as the group's, moved from `StratumClientGroups.configureClient` into
`StratumClientBuilders.configure`. Rejected: a wrapper around the team's client (ADR 9: Stratum never wraps one).
**Which groups are Stratum's** (shared with lane B, F3): a group whose types include one with `@StratumClient`, or one
that its client's `interfaces` list names. One method, `StratumClientGroups.isStratumClient(group)`, used by the pool
set-up and lane B's decorators; the group's name alone does not count, so a team's own group is never taken.

## 3. Is it safe and fast?
`restClient(name)` caches one client per name: H1's `ClientConnectionPools.requestFactory` adds a `CloseableHttpClient`
per call, so building per request would grow that list without end. Same pool as the group's, so the limit is
shared. Hostile input: class names come from the service's own config; a wrong one stops startup without reflection
beyond `ClassUtils.resolveClassName`. No thread hop.

## 4. Which rules and owner decisions does it follow?
ADR 0011 section 9 and 1.4; owner 11-08 23:37Z (never wrap, replace or check a foreign client; unused blocks
allowed); ST-ENG-24, -34 (Java: `HttpClientPropertiesCustomizer` sets `interfaces` too), -56, -57; ST-SEC-17 untouched.
Documents: `docs/guides/error-codes.md` (rows for 1007 and 1008, checked by `ErrorCodeChecks`); module page sections "Clients Stratum did not build", Properties, Startup errors, Planned; IDE metadata.

## Details

### Keys
| Key | Default | Where | Real case |
|---|---|---|---|
| `clients.<name>.interfaces` | none | clients only; under `defaults` it stops startup (`defaults-without-interfaces`, like `url`) | an OpenAPI generator (`library: spring-http-interface`) writes `OrdersApi` and `CustomersApi`, one per tag, for one partner |

The key's value is a list, since one generated API is often several interfaces (YAML list, or `interfaces[0]=...`
in properties; no comma syntax, per the 11-07 rule). Record component `interfaces`. IDE metadata gets
`stratum.http-client.clients.*.interfaces` (`ConfigurationMetadataTest`).
**Owner decision (2026-11-09 13:49Z, card, round 2 F1):** the key is `interfaces`, a record component of that name,
no `@Name`, so ST-ENG-30 is unchanged. ADR 0011 names it `interface` and is never edited, so the C2a pull request
adds a short ADR 0012, "Changes to ADR 0011 found while building", recording this rename and the owner's 13:39Z and
13:45Z decisions on `MockClient` (in the starter; C3 then builds it with no ADR of its own).

### Startup stops (configuration only, boot-check boundary of 11-06)
- `SX-HTTPC-1007` (new): an `interfaces` entry names no interface on the classpath (class missing, or a class).
- H1's `SX-HTTPC-1002` (a `Mono`/`Flux` method) applies to a named interface too: `checkInterface` is split into the
  name check (annotation only) and the method check (both paths).
- An interface both annotated and named for the same client registers once (`distinct`); named for a different
  client than its annotation stops startup with `SX-HTTPC-1008` (new): it would be two clients with one type.

### API (ST-ENG-56)
`StratumHttpClients` (starter package `com.exepex.stratum.restclient`) is an **API interface** with the two methods
below; `DefaultStratumHttpClients` (`@StratumInternal`) implements it and takes `StratumClientBuilders` and
`HttpClientProperties`. The bean `stratumHttpClients` is `@ConditionalOnMissingBean(StratumHttpClients.class)`, so a
service can replace it (round 2, F2: an API class cannot take internal types, `PublicApiRule.apiExposesOnlyApi`).
- `RestClient restClient(String client)`: the client's base URL, pool and timeouts, then lane A's auth initializers
  and headers once they exist, then the service's `StratumClientCustomizer` beans. Built on first use, cached.
  `SX-HTTPC-1004` when the client has no `url`. No retry, breaker or 401 resend by itself (owner card); those come
  from wrapping the call in `resilience(name).call(...)` (C2b).
- `ClientSettings settings(String client)`: `HttpClientProperties.clientSettings`, for an SDK with its own engine.
Internal: `StratumClientBuilders` (`@StratumInternal`), the one place that sets up a client's `RestClient.Builder`;
`StratumClientGroups` calls it. Lanes A and B add builder work there (coordinator told, 13:36Z).
- **Boot's customizers first (F2).** A group's builder gets every `RestClientCustomizer` bean from Boot's
  `RestClientCustomizerHttpServiceGroupConfigurer` (order 0) before Stratum's set-up (order 1). `restClient(name)`
  does the same: `RestClient.builder()`, then the `ObjectProvider<RestClientCustomizer>` beans in order (Boot's
  `ObservationRestClientCustomizer` among them: the `http.client.requests` observation and trace headers), then
  `StratumClientBuilders.configure`: exactly Boot's own group path. Not Boot's `RestClient.Builder` bean, because a
  team that defines its own `RestClient.Builder` bean makes Boot's back off, and the team's builder must not shape
  Stratum's clients. (Round 2, M1: the earlier reason was wrong; Boot 4.1.2 keeps that bean in reactive apps.)
- **Reading `interfaces` (F4).** The registrar runs before any settings bean exists, so it reads the key through
  `HttpClientProperties.resolve(environment)`, the one path that applies the `HttpClientPropertiesCustomizer` classes.
- **What a named interface cannot have (F5).** A generated interface carries no `@StratumClient(interceptors = ...)`,
  so it gets no per-client `ClientInterceptor` (ADR 1.3); it gets every `GlobalClientInterceptor` and, by client name,
  every `StratumClientCustomizer`. The note and the module page say so. A failure of `restClient("<name>")` without a
  `url` comes at first use (`SX-HTTPC-1004`), which the page says too.

### Decisions and their tests
| Decision | Test | Planted fault |
|---|---|---|
| A named interface is a full client | `OwnClientsIntegrationTest.namedInterfaceUsesClientPool` (pool limit 1 holds, base URL used) | registrar skips named interfaces |
| `interfaces` under `defaults` stops startup | `HttpClientPropertiesTest.defaultsWithoutInterface` | validation removed |
| Missing class, a class, a `Mono` method, two names | `StartupChecksTest` cases for 1007, 1002, 1008 | each check removed |
| `restClient(name)` shares the group's pool and timeouts | IT: an interface call holds the one connection, `restClient` call refused after `max-wait` | own pool per `restClient` |
| `restClient(name)` built once | same instance twice; `ClientConnectionPools` client count stays 1 | cache removed |
| `restClient(name)` has no interceptor (uploads stream) | C1's chunked-upload check through `restClient` | an interceptor added |
| Customizers apply to `restClient(name)` | `ClientCustomizerIntegrationTest` case | customizers skipped |
| `settings(name)` = merged settings | unit test | defaults not merged |
| A team's own `RestClient.Builder` untouched | H1's `ForeignClientIntegrationTest` unchanged | - |
| Java sets `interfaces` (F4) | `OwnClientsIntegrationTest.javaSetInterfaceIsRegistered`: an `HttpClientPropertiesCustomizer` in a test `spring.factories` names the interface, and a call through it reaches the downstream | registrar binds without `resolve` |
| A named-only group is Stratum's (F3) | `OwnClientsIntegrationTest.namedInterfaceUsesClientPool` (pool and base URL); a retry row once B1 is on `main` | predicate checks the annotation only |
| `restClient(name)` gets Boot's customizers first (F2) | IT with a `TestObservationRegistry`: a call through `restClient` records `http.client.requests`; a team `RestClientCustomizer` adding a header is seen downstream | customizers not applied |

### Safe and fast (ST-AG-14)
Speed and throughput: one cached `RestClient` per name, nothing per call. Memory: a fixed map, at most one entry per
configured client. Log volume: none added. Data leaks: none; errors name the client and class, never a value.
Maintainability: one builder set-up for both paths. Scalability: the pool limit covers both paths.

### Not in C2a
`resilience(name)` (C2b); the reactive starter's `webClient(name)`; a client name used by `restClient` but with no
interface (allowed: unused blocks are allowed, ADR 9).

## Not verified
Boot 4.1.2 binding a YAML list to the `interfaces` record component (checked in the first test before code);
an OpenAPI-generator `spring-http-interface` output compiling against Spring 7 (docs show a hand-written example).

## Round 1 findings (reviews/judge/C2a-own-clients-note.md)
- F1: codes now `SX-HTTPC-1007` and `1008`; lane C reserves `1009` for C3 (asked of the coordinator, 13:40Z).
- F2: Boot's `RestClientCustomizer` beans applied first; test row added.
- F3: one shared predicate, agreed with lane B; test row added.
- F4: `resolve(environment)` stated; the Java row is now an integration case.
- F5: per-client interceptors not available on a named interface; stated, docs too.

## Round 2 findings (reviews/judge/C2a-own-clients-note-r2.md)
- F1: owner chose the key `interfaces` (13:49Z); ADR 0012 records it.
- F2: `StratumHttpClients` is an API interface; `DefaultStratumHttpClients` is internal.
- M1: reason for not using Boot's `RestClient.Builder` bean corrected.
- M2: error-codes guide and `ConfigurationMetadataTest` added to Documents.
