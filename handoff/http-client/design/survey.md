# HTTP client starter: what already exists, and what to build on

Date: 2026-11-07. Research only, nothing decided. Facts marked **(verified)** come from the source linked next to
them; **(not verified)** means I did not check it.

## 1. Short answer

Most of what you asked for already exists, but no single library has all of it. The best fit for Stratum is a thin
Stratum layer over four pieces:

| Need | Build on | Why |
|---|---|---|
| Named clients, declarative methods | Spring's **HTTP service clients** (`@HttpExchange` interfaces + groups) | It is Spring's replacement for Feign; Boot 4.1 already gives per-group YAML keys. PR #79 is already built on it. |
| Retry, circuit breaker, bulkhead, time limit | **Resilience4j 2.4** (`resilience4j-spring-boot4`) | Only maintained Java library with all four, Apache 2.0, has a Boot 4 module. Spring 7's own `@Retryable` has retry but no circuit breaker. |
| OAuth2 client credentials, token exchange | **Spring Security 7** (`@ClientRegistrationId` on HTTP interfaces) | Already does token fetch, cache and refresh per registration. |
| Basic, static bearer, API key, mTLS | Small Stratum interceptors + Boot **SSL bundles** | A few lines each; no library needed. |
| Client-side load balancing | Stratum's own small round-robin over a static list, behind an interface; **Spring Cloud LoadBalancer** as an optional later adapter | Spring Cloud's current train targets Boot 4.0, not 4.1 (see 3.3). |
| Codecs (JSON, XML, text, form) | Spring's message converters (Jackson 3, Jackson XML, String, form) | Already there; Stratum only picks them per client. |
| Generated methods | **OpenAPI Generator** (`spring-http-interface` output) | Generates the `@HttpExchange` interface from the other service's OpenAPI file. |

The idea to copy from outside Java is **.NET's `AddStandardResilienceHandler`**: one line gives every client a fixed,
ordered pipeline with sensible defaults, and each client can override any step. That is the "global default with
per-client override" shape you described.

## 2. Your requirements against each candidate

Legend: ✅ has it, ◐ partly, ❌ no.

| Library | Named clients from config | Global + per-client override | Retry | Circuit breaker | Client-side LB | Auth types | Codecs | Declarative methods | Blocking + reactive | License | Maintained | Verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Spring HTTP service clients (Framework 7, Boot 4.1) | ✅ groups, `spring.http.serviceclient.<name>.*` | ✅ timeouts, base URL, headers, SSL | ❌ | ❌ | ❌ alone | ◐ OAuth2 via Security | ✅ | ✅ | ✅ RestClient / WebClient | Apache 2.0 | ✅ core Spring | **Adopt** (base) |
| Spring Cloud OpenFeign | ✅ | ✅ | ◐ | ◐ via Spring Cloud CB | ✅ via LoadBalancer | ◐ | ✅ | ✅ | ❌ blocking only | Apache 2.0 | ❌ feature-complete, bug fixes only | **Skip** |
| OpenFeign core (`io.github.openfeign`) | ❌ | ❌ | ◐ | ❌ | ❌ | ◐ | ✅ | ✅ | ◐ | Apache 2.0 | ✅ | Skip (Spring already has the same model) |
| Spring Cloud LoadBalancer | — | ◐ | ◐ | — | ✅ round robin, random, zone, health | — | — | ✅ plugs into HTTP service groups | ✅ | Apache 2.0 | ✅ | **Later adapter** (Boot 4.1 fit unconfirmed) |
| Spring Cloud Circuit Breaker | — | ✅ | — | ✅ plugs into groups, `@HttpServiceFallback` | — | — | — | ✅ | ✅ | Apache 2.0 | ✅ | Port the fallback idea; same Boot 4.1 issue |
| Resilience4j 2.4 | ✅ named instances | ✅ `configs.default` + per instance | ✅ | ✅ | ❌ | — | — | ✅ annotations | ✅ Reactor operators | Apache 2.0 | ✅ Boot 4 module | **Adopt** (engine) |
| Spring Framework 7 `@Retryable`, `@ConcurrencyLimit` | ◐ | ◐ placeholders | ✅ | ❌ | ❌ | — | — | ✅ | ✅ | Apache 2.0 | ✅ | Alternative if no circuit breaker wanted |
| Spring Security 7 OAuth2 client | ✅ registrations | ✅ | — | — | — | ✅ client credentials, token exchange, JWT bearer, refresh | — | ✅ `@ClientRegistrationId` | ✅ | Apache 2.0 | ✅ | **Adopt** (OAuth2) |
| Apache HttpClient 5 | ❌ | ❌ | ◐ I/O retry only | ❌ | ❌ | ◐ basic, NTLM | ❌ | ❌ | blocking | Apache 2.0 | ✅ | Keep as transport (PR #79) |
| Reactor Netty | ❌ | ❌ | ◐ | ❌ | ❌ | ❌ | ❌ | ❌ | reactive | Apache 2.0 | ✅ | Keep as transport (PR #79) |
| Retrofit + OkHttp (Square) | ❌ | ❌ | ◐ OkHttp interceptors | ❌ | ❌ | ◐ `Authenticator` | ✅ converters | ✅ | ◐ | Apache 2.0 | ✅ | Port ideas: per-call `Authenticator` on 401, converter factories |
| Armeria (LINE) | ◐ | ✅ decorators | ✅ | ✅ | ✅ with health checks | ◐ | ✅ | ✅ | ✅ | Apache 2.0 | ✅ | Skip: a full second HTTP stack beside Spring |
| Failsafe (Java) | ❌ | ❌ | ✅ | ✅ | ❌ | — | — | ❌ | ✅ | Apache 2.0 | ◐ slow releases (not verified) | Skip (Resilience4j covers it with Spring support) |
| MicroProfile Rest Client (Quarkus, Helidon) | ✅ `<name>/mp-rest/url` | ✅ | via MP Fault Tolerance | via MP FT | ❌ | ◐ | ✅ | ✅ | ✅ | Apache 2.0 | ✅ | Port the config shape: one key block per client name |
| .NET `IHttpClientFactory` + Polly 8 | ✅ named and typed clients | ✅ | ✅ | ✅ | ◐ service discovery package | ✅ handlers | ✅ | ◐ (Refit) | async only | MIT / BSD-3 | ✅ | **Port ideas** (standard pipeline, see 4) |
| Netflix Ribbon, Hystrix | ✅ | ✅ | ✅ | ✅ | ✅ | — | — | — | — | Apache 2.0 | ❌ dead since ~2018 | Skip |

## 3. Facts that shape the choice

### 3.1 Spring says "stop using Feign"
Spring Cloud OpenFeign's own page: "we're now treating the Spring Cloud OpenFeign project as feature-complete … We
suggest migrating over to Spring HTTP Service Clients instead." **(verified)**
[OpenFeign reference](https://docs.spring.io/spring-cloud-openfeign/reference/)

### 3.2 Spring HTTP service clients already give named clients
`@ImportHttpServices(group = "inventory", types = InventoryClient.class)`, one client per group, configured by
`spring.http.serviceclient.<group>.*` in Boot 4. Spring Security 7 reads `@ClientRegistrationId` on these interfaces
for OAuth2 tokens; other auth types are "still under consideration" (spring-security#17940). **(verified)**
[HTTP Service Client Enhancements](https://spring.io/blog/2025/09/23/http-service-client-enhancements/),
[Spring Security HTTP service clients](https://docs.spring.io/spring-security/reference/features/integrations/rest/http-service-client.html)

### 3.3 Spring Cloud plugs into groups, but targets Boot 4.0
Spring Cloud 2025.1 adds `LoadBalancerRestClientHttpServiceGroupConfigurer` (a group with no base URL, or an `lb://`
URL, is load balanced by service ID = group name) and `CircuitBreakerRestClientHttpServiceGroupConfigurer` with
`@HttpServiceFallback`. **(verified)**
[LoadBalancer docs PR](https://github.com/spring-cloud/spring-cloud-commons/pull/1615/files),
[Spring Cloud Circuit Breaker](https://docs.spring.io/spring-cloud-commons/reference/5.1/spring-cloud-circuitbreaker.html)
But 2025.1 (Oakwood) lists Spring Boot 4.0.x, and 2026.0-M1 is built on Boot 4.2.0-M2. I found no train that states
Boot 4.1 support. **(verified that it is not listed; whether 2025.1 runs on 4.1 is not verified)**
[endoflife.date](https://endoflife.date/spring-cloud),
[Spring Cloud 2026.0.0-M1](https://spring.io/blog/2026/09/24/spring-cloud-2026-0-0-M1-has-been-released/)

### 3.4 Resilience4j supports Boot 4
2.4.0 ships `resilience4j-spring-boot4` (it reuses the Spring 6 module); baseline JDK 17. One report says the 2.4.0
BOM missed that artifact. **(verified)**
[resilience4j#2371](https://github.com/resilience4j/resilience4j/issues/2371)

### 3.5 Spring 7 core has retry, not a circuit breaker
`@Retryable` (maxRetries 3, 1s delay by default), `@ConcurrencyLimit`, `RetryTemplate`. No circuit breaker.
**(verified)** [Spring resilience features](https://docs.spring.io/spring-framework/reference/core/resilience.html)

### 3.6 .NET's standard pipeline
`AddStandardResilienceHandler`, outer to inner: rate limiter (1000 permits) → total timeout 30s → retry 3,
exponential with jitter → circuit breaker (10% failures, min 100 calls, 30s window, 5s open) → per-attempt timeout
10s. **(verified)** [.NET HTTP resilience](https://learn.microsoft.com/dotnet/core/resilience/http-resilience)

## 4. What a Stratum client could look like (sketch, not a design)

```yaml
stratum:
  http-client:
    defaults:                    # applies to every client
      connect-timeout: 2s
      read-timeout: 5s
      retry: { max-attempts: 3, backoff: 200ms, only-idempotent: true }
      circuit-breaker: { failure-rate: 50%, min-calls: 20, open-for: 30s }
    clients:
      inventory:                 # a named client
        base-url: https://inventory.internal
        codec: json              # json | xml | text | form
        auth: { type: client-credentials, registration: inventory }
        circuit-breaker: { enabled: false }   # overrides the default
      legacy-billing:
        base-url: https://billing.internal
        auth: { type: basic, username: ${BILLING_USER}, password: ${BILLING_PASSWORD} }
        read-timeout: 20s
```

The team writes one Java interface per remote API (or generates it from OpenAPI) and injects it. A
`HttpClientCustomizer` bean does the same from Java. Order of each call, like .NET: total timeout → retry → circuit
breaker → per-attempt timeout → auth → send.

## 5. Raw-thoughts review: gaps and pushback

1. **"Endpoints and methods generated from YAML" — I would push back.** Methods written in YAML have no types: a
   typo in a path or a field shows up only at runtime, the IDE cannot help, and refactoring breaks silently. The
   type-safe way already exists twice: a Java interface with `@GetExchange` (a few lines per method), or
   OpenAPI Generator creating that interface from the other team's OpenAPI file. Recommend: YAML holds *settings*
   (URL, timeouts, auth, resilience); Java or OpenAPI holds *methods*.
2. **Do you need service discovery? Probably not in the first release.** On Kubernetes, a Service name
   (`http://inventory`) already balances across pods on the server side, and DNS is the discovery. Client-side LB
   helps when: services are outside Kubernetes (VMs), long-lived HTTP/2 connections stick to one pod, or you want
   zone-aware routing. Recommend: a static instance list with round robin behind an interface first; Eureka, Consul
   or Kubernetes API discovery as later adapters (planned issues). Not verified for your platform: whether teams run
   on Kubernetes.
3. **Retry is dangerous by default.** Retrying a POST can charge a card twice. Retry must default to idempotent
   methods only (GET, PUT, DELETE, HEAD) and transient failures (connect errors, 503, 429 with `Retry-After`). PR #79
   already stops Apache from re-sending a POST on 429/503. Also a retry budget: three layers that each retry 3 times
   make 27 calls.
4. **Circuit breaker per client or per host?** With load balancing, one bad instance can open the breaker for the
   whole client. .NET's hedging handler keeps one breaker per endpoint. Worth a decision later.
5. **Fail-fast already exists in pieces.** A bounded pool with a short wait (PR #79), a connect timeout and an open
   breaker are what "fail fast" means. A per-client `fail-fast: true|false` switch could just pick stricter values.
6. **Auth list to support** (your "support all options" rule): none, basic, static bearer (from a secret), API key
   header, OAuth2 client credentials, token relay (pass the caller's token on), token exchange (RFC 8693), mTLS (SSL
   bundle). First release could ship a subset; the rest become planned issues. Note: the agreed H2 rule already says
   `Authorization` is never forwarded through `forward-headers`, so token relay must stay its own explicit type.
7. **Blocking vs reactive is already decided** (2026-10-31, Q6): two starters sharing one library and the same keys.
   Nothing here changes that.
8. **Overlap with the planned resilience starter.** `docs/modules/resilience.md` already plans named Resilience4j
   policies, and the HTTP client page plans `resilience-policy: <name>` per client. The cache starter chose its own
   small retry gate instead. We should decide once whether Resilience4j is Stratum's resilience engine.
9. **Spring Boot keys first.** Boot already owns `spring.http.serviceclient.<name>.base-url`, timeouts and SSL; the
   agreed rule (Q1) is to use them, so `stratum.http-client.clients.<name>.*` would carry only extras (auth type,
   codec, resilience). The YAML sketch above mixes them for readability only.
10. **Things you did not list that teams usually need:** per-client `User-Agent` and default headers, gzip,
    redirects on/off, proxy settings, response size cap (memory safety), hedging for slow reads, a rate limiter on
    outgoing calls, and a test helper (stubbed downstream; `DownstreamStub` already exists in `stratum-test`).

## 6. Where things stand in the repo

- `starters/stratum-starter-http-client` has only a `pom.xml` on `main`; `docs/modules/http-client.md` is marked
  Planned. (read on `main`, 8b111f3)
- PR #79 (H1: timeouts, bounded pools, two starters) is open and on hold since 2026-11-01. (read via GitHub)
- `stratum-starter-resilience` and `stratum-starter-oauth2-client` also have only a `pom.xml`; their docs plan
  Resilience4j policies and client-credentials tokens.

## 7. Firm recommendation (added 2026-11-07, after Aashish asked for one)

Build on **Spring HTTP service clients + Resilience4j + Spring Security OAuth2**, with Stratum's own layer for
everything else (option A). Copy the best features of the other libraries into that layer, each one a switch that is
set once for every client and can be overridden per client. Teams use only YAML/properties or a Java customizer, and
inject the client by its interface.

## 8. Feature catalogue: what Stratum offers, where the idea comes from, default

Each row is a switch under `defaults` (every client) and under `clients.<name>` (one client wins over defaults).

| # | Feature | Idea taken from | Built with | Default |
|---|---|---|---|---|
| 1 | Named clients, created from config, injected by interface | Spring HTTP service groups, .NET named clients, MicroProfile | Spring | on |
| 2 | Connect / read timeout | Boot `spring.http.clients.*` | Boot (PR #79) | 2s / 5s |
| 3 | Total call deadline (all retries together) | .NET total timeout, Finagle deadlines | Resilience4j TimeLimiter | 10s (to agree) |
| 4 | Bounded connection pool, fail fast when full | Apache HC5, Reactor Netty | PR #79 | 50, 2s wait |
| 5 | Retry, idempotent methods and transient errors only, backoff + jitter | Resilience4j, Polly | Resilience4j | on, 3 attempts |
| 6 | Retry budget (cap retries to a share of calls, stops retry storms) | Finagle `RetryBudget` | Stratum (small) | on, 20% (to agree) |
| 7 | Circuit breaker | Resilience4j, Polly, Finagle failure accrual | Resilience4j | on |
| 8 | Bulkhead (max calls in flight per client) | Resilience4j, .NET rate limiter | Resilience4j | off |
| 9 | Outgoing rate limit | Resilience4j, .NET | Resilience4j | off |
| 10 | Fallback method when a call fails | Spring Cloud `@HttpServiceFallback` | Stratum | off |
| 11 | Hedging (send a second request if the first is slow, GET only) | .NET hedging, Finagle backup requests | Stratum | off |
| 12 | Auth type per client: none, basic, bearer (secret), API key, OAuth2 client credentials, token relay, token exchange, mTLS | Spring Security, OkHttp `Authenticator` (refresh on 401) | Spring Security + small Stratum interceptors | none |
| 13 | Codec per client: JSON, XML, text, form, byte stream | Retrofit converters, Spring converters | Spring | JSON |
| 14 | Default headers per client, `User-Agent` | everyone | Spring | service name |
| 15 | Trace and correlation ID forwarding | Stratum observability | done in H2 plan | on |
| 16 | Load balancing over several addresses (round robin, least busy) | Spring Cloud LoadBalancer, Finagle aperture, Armeria | Stratum (static list) | off |
| 17 | Service discovery source for 16: static list, Kubernetes DNS, Spring Cloud DiscoveryClient (Eureka, Consul, K8s API) | Spring Cloud | adapter when on classpath | static |
| 18 | Health-aware balancing (skip an address whose breaker is open) | Armeria, Finagle | Stratum | on when 16 is on |
| 19 | Response size cap (memory safety) | Armeria | Stratum | 10 MB (to agree) |
| 20 | Gzip, redirects, proxy | Boot / HC5 | Boot | Boot's |
| 21 | Call log (none, errors, all; never headers or bodies) | agreed H4 | Stratum | errors |
| 22 | Metrics per client: latency, errors, retries, breaker state | Resilience4j + Micrometer | Resilience4j | on |
| 23 | Typed errors (timeout 504, unavailable 503) | agreed H3 | Stratum | on |
| 24 | Before-call check hook (for example an outbound allowlist from a token) | bank use case | Stratum interface only | none |
| 25 | Test helper: stub downstream, assert calls | WireMock style | `stratum-test` `DownstreamStub` | — |

Load balancing (16) and discovery (17) work together through the base URL:
`https://orders.internal` calls one address directly; `lb://orders` picks one of `orders`'s addresses from the
configured source. A team that needs neither sets nothing.

## 9. How methods are defined: four ways

Aashish's idea: the team lists a service's endpoints in YAML with placeholders, and Stratum gives them a Java
interface to inject. No open-source Java library does exactly this (closest: OpenAPI Generator from an OpenAPI file,
openapi-processor, MicroProfile REST client generators). **(search, not exhaustive)**

| Way | Team writes | Path lives in | Type-safe | Stratum builds |
|---|---|---|---|---|
| M1 Interface | `@PostExchange("/orders") Order createOrder(@RequestBody NewOrder o)` | Java | yes | nothing (Spring) |
| M2 From OpenAPI | nothing; points the Maven plugin at the other team's OpenAPI file | OpenAPI file | yes | nothing (OpenAPI Generator, Apache 2.0) |
| M3 From YAML at build time | YAML: name, method, path, request/response class | YAML | yes, after build | a code generator (Maven plugin) |
| M4 Interface + YAML paths | `@Endpoint("create-order") Order createOrder(NewOrder o)`; YAML: `create-order: POST /orders` | YAML | yes | a small proxy hook (feasibility on Spring's proxy not verified) |

Bank use case (allowed endpoints inside a signed token): this is outbound access control. Per the 2026-11-07
access-control decision it belongs to the future access-control starter; the HTTP client only needs the before-call
hook (row 24).

Finagle: a Scala RPC stack (Apache 2.0); Stratum cannot wrap it inside Spring, but its retry budget, failure accrual,
aperture balancing and deadlines are worth copying (rows 3, 6, 7, 16). Current maintenance level not verified.
[Finagle clients guide](https://twitter.github.io/finagle/guide/Clients.html)

## 10. Re-think after Aashish's 07:35Z message: "Stratum client" proposal (draft, not agreed)

Split: **settings in YAML, endpoints in Java, everything else inferred.** Endpoints and payload types never go in
YAML, because only Java can check that a class exists.

```java
@StratumClient("orders")                        // name = the YAML block below
public interface OrderClient {
  @Post("/orders")             Order createOrder(NewOrder order);        // one object param -> JSON body
  @Get("/orders/{id}")         Optional<Order> findOrder(String id);     // {id} filled from param name; 404 -> empty
  @Get("/orders")              List<Order> listOrders(String status);    // other simple params -> ?status=
  @Patch("/orders/{id}")       Order changeAddress(String id, Address a);
  @Get("/orders/{id}")         Mono<Order> findOrderAsync(String id);    // Mono/Flux return -> reactive call
}
```

```yaml
stratum.http-client.clients.orders:
  base-url: lb://order-service        # or https://orders.internal
  auth: { type: client-credentials, registration: orders }
  retry: { enabled: true }
```

- No config class, no `@ImportHttpServices`: Stratum finds `@StratumClient` interfaces in the application's packages
  (like Spring Data repositories) and makes each a bean. The team injects `OrderClient`.
- Inferred: path placeholders from parameter names; leftover simple parameters become query parameters; one object
  parameter is the body; the return type picks the response mapping and blocking vs reactive.
- A client "type" (basic, OAuth2, bearer...) is not a separate class: it is the `auth.type` setting. Load balancing,
  retry, circuit breaker are switches on the same client.
- Startup checks only Stratum's own config: a `@StratumClient("orders")` with no `orders` block, or an unknown
  `auth.type`, stops startup with a clear message.
- Contract jar: the order team can publish `OrderClient` + its records as a small jar (written by hand or generated
  from OpenAPI); consumers add the jar and three YAML lines.
- Escape hatch: inject `StratumHttpClients` and call `clients.restClient("orders")` for an ad-hoc call with all the
  same settings.
- Java config: an `HttpClientCustomizer` bean for one client by name (custom header logic, own JSON mapper).
- Not verified: whether Spring's `HttpServiceProxyFactory` can be fed these annotations and inference, or whether
  Stratum needs its own small proxy over `RestClient` / `WebClient`. Either way the transport stays Spring's.
- HTTP verb: **decided 2026-11-07 08:54Z (Aashish, A)**: a short annotation per method (`@Get`, `@Post`, `@Put`,
  `@Patch`, `@Delete`, with the path). Not inferred from the method name.
- Blocking vs reactive: **decided 2026-11-07 08:55Z (Aashish, A)**: keep the two starters (2026-10-31 decision).
  The blocking starter handles plain return types; the reactive starter adds `Mono`/`Flux`. A `Mono`/`Flux` method
  without the reactive starter stops startup with a message naming the starter to add.
- Load balancing / discovery: **decided 2026-11-07 08:58Z (Aashish, A)**: first release = direct URL plus `lb://name`
  over a configured address list, round robin, skipping addresses whose breaker is open, behind a small
  address-source interface. Eureka, Consul and Kubernetes API sources become planned issues.
- Auth types: **decided 2026-11-07 08:59Z (Aashish, A)**: first release = none, basic, bearer token from a secret,
  API key header, OAuth2 client credentials, token relay, mTLS via a Boot SSL bundle. Token exchange (RFC 8693) and
  JWT-bearer grant become planned issues.
- Resilience: **decided 2026-11-07 09:00Z (Aashish, A)**: Resilience4j inside the HTTP client starters, retry (safe
  methods, transient failures) and circuit breaker on by default for every client; the later resilience starter
  reuses the same library and keys for non-HTTP code.
- Key home: **decided 2026-11-07 09:03Z (Aashish, B)**: all of one client's settings live in one
  `stratum.http-client.clients.<name>.*` block, base URL and timeouts included. Stratum applies them to Spring Boot's
  `spring.http.serviceclient.<name>.*` itself. This needs a written exception to ST-ENG-34 (see 11.2).

## 11. Agreed design (2026-11-07), for Aashish's review

### 11.1 Summary
1. Base: Spring `RestClient` / `WebClient` (Apache HC5 / Reactor Netty, PR #79's pools), Resilience4j, Spring Security
   OAuth2. Stratum adds its own layer; Spring Cloud only as a later adapter.
2. Team writes a `@StratumClient("orders")` interface with business-named methods, each with a short verb annotation
   and path (`@Post("/orders") Order createOrder(NewOrder order)`). Stratum finds the interfaces and registers each
   as a bean. Path placeholders come from parameter names, other simple parameters become query parameters, one
   object parameter is the body, the return type sets the response mapping (`Optional` = 404 empty).
3. Two starters, same keys: blocking (plain return types) and reactive (`Mono`/`Flux`). A `Mono`/`Flux` method without
   the reactive starter stops startup naming the starter to add.
4. One block per client under `stratum.http-client.clients.<name>`, plus `stratum.http-client.defaults` for every
   client; a client's value wins over `defaults`; Java customizer wins over files (ST-ENG-34).
5. Load balancing: direct URL, or `lb://name` over `addresses: [...]`, round robin, skipping addresses whose breaker
   is open.
6. Auth (`auth.type`): none, basic, bearer from a secret, API key header, OAuth2 client credentials, token relay, mTLS.
7. Resilience4j in the client starters: retry (safe methods, transient failures) and circuit breaker on by default.
8. Startup checks only Stratum's own config: a `@StratumClient` name with no block, an unknown `auth.type`, an
   `lb://` client with no addresses.

### 11.2 Proposed rule text (needs Aashish's go, ST-AG-13; Feature track because it changes docs/agents/)
Add to ST-ENG-34 in `docs/agents/engineering.md`: "One exception: the HTTP client starters keep every setting of a
client, base URL and timeouts included, under `stratum.http-client.clients.<name>.*` and every client's defaults
under `stratum.http-client.defaults.*`, and apply them to Spring Boot's `spring.http.serviceclient.<name>.*` and
`spring.http.clients.*` themselves. A service that sets the Boot key and the Stratum key for the same setting of the
same client stops at startup with a message naming both keys."

### 11.3 Effect on PR #79 (H1)
H1's pools, Apache retry fix, Boot bug workaround and two starters stay. Its docs, `DocumentedSpringKeysTest` and
the 2s/5s defaults (set today on Boot's keys) move to the `stratum.http-client` block. H1 needs rework before review.

### 11.4 Planned issues (to file after Aashish's go)
1. Address sources for `lb://`: Spring Cloud DiscoveryClient (Eureka, Consul), Kubernetes API.
2. Auth: OAuth2 token exchange (RFC 8693).
3. Auth: JWT-bearer grant (RFC 7523).
4. Generate a `@StratumClient` interface from YAML at build time (M3).
5. Generate a `@StratumClient` interface from an OpenAPI file (M2 adapted to Stratum's annotations).
6. Hedging for slow GET calls.
7. Outgoing rate limit and bulkhead per client.
8. Fallback methods per client.
9. Before-call check hook (outbound allowlist, bank case), with the access-control starter.
10. Spring Cloud LoadBalancer / Circuit Breaker adapter once a train supports the Boot version Stratum uses.

## 12. Production-readiness gaps (2026-11-07 10:39Z review, proposals)

Already agreed and counted: timeouts, bounded pools, retry (safe methods), circuit breaker, auth set, load balancing,
trace + correlation headers (H2), typed timeout/unavailable errors (H3), call log without headers or bodies (H4),
metrics, startup config checks.

Must have before calling it production ready (proposed):
1. Interceptors per client: a team bean for one client by name (`@StratumClientInterceptor("orders")`) or for all.
2. Connection lifetime: max connection age and idle eviction, so calls follow DNS changes and pod churn.
3. Total deadline across retries (catalogue row 3) and `Retry-After` honoured on 429/503.
4. Response size cap (row 19) and streaming for large downloads/uploads (`InputStream`, multipart).
5. Redirects off by default; base URL only from config (no request-supplied hosts, SSRF).
6. Error mapping: a 4xx/5xx becomes a typed exception with status, client name and endpoint; a per-client hook
   maps it to the team's own exception.
7. Per-method and per-client headers (`@Header` parameter, `default-headers`), API version header.
8. Test support: replace a `@StratumClient` with a stub by name in tests (`stratum-test`).
9. Pool gauges on the blocking stack (missing since H1).

Planned issues (not first release): secret rotation for basic/bearer/API key, idempotency-key header for retried
POST, proxy settings per client, compression per client.

Open: request/response body tracing. H4 (2026-10-31) says never headers or bodies.
- Body logging: **decided 2026-11-07 10:42Z (Aashish, B)**: opt-in per client, off by default, debugging only;
  bodies masked by the logging starter's redaction, size-capped, startup WARN while on. Headers stay out (H4), and
  `Authorization`, cookies and API keys are never logged. On the trigger list (logged data): Feature track, security
  review.
- Debug per client or all (Aashish 10:44Z): proposal = logger per client `stratum.http-client.<name>` (parent for all),
  DEBUG logs masked, size-capped bodies + header names only, switchable live via actuator loggers, capped at 100
  lines/s with a dropped-lines counter, WARN when turned on. **Decided 2026-11-07 10:46Z (Aashish, A): log level, runtime switch**; can reuse logging PR 4 timed DEBUG switch (auto-off).
