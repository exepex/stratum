# ADR 0033: HTTP client starters on Spring HTTP interfaces, with one settings block per client

**Status:** Accepted, 2026-11-22, by the owner. Its decisions were taken from 2026-11-07 to 2026-11-20; each
gives its date.

This is the first of four records on the HTTP client starters. It owns the starters, every key with its default and
range, and clients Stratum did not build. [ADR 0034](0034-http-client-auth-resilience-and-connections.md) owns auth,
retries, the circuit breaker and connections; [ADR 0031](0031-http-client-errors-and-body-limits.md) owns errors,
body limits and streaming; [ADR 0032](0032-http-client-call-log-and-meters.md) owns the call log and meters.

[← Documentation home](../README.md) · How to use it: [HTTP client](../modules/http-client.md) ·
[Clients Stratum did not build](../modules/http-client-own-clients.md) · [Mocked clients](../modules/http-client-mocks.md)

## Context

- **Calls to other services are the most common cause of incidents.** A missing timeout, a retry storm, a leaked
  token or a full pool is copied into every service that runs on Stratum (AGENTS.md, "The project").
- **Spring already has the parts.** Spring Framework 7 has HTTP interface clients and service groups. Spring
  Security 7 has OAuth2 client interceptors. Resilience4j 2.4 supports Boot 4. What Spring lacks is a safe default
  for each: Boot sets no timeouts and no pool limits, and Apache HttpClient 5 retries on its own.
- **The survey (2026-11-07)** compared libraries in Java, .NET, Go, Rust, Python and Node. The owner chose to build
  on Spring and to copy features, not other libraries' way of working. Endpoints are never written in YAML.
  Spring Cloud OpenFeign is feature-complete, and Spring Cloud 2025.1 lists Boot 4.0 only.
- **Teams also use clients Stratum did not build:** OpenAPI-generated ones, partners' SDK jars, older classes on
  `RestTemplate` and their own engines. The owner's requirement (2026-11-08): Stratum integrates with them and never
  conflicts with them.
- **Two gap reviews and a spike (2026-11-09)** found 21 gaps. The owner decided each one, and a spike on Boot 4.1.2
  checked 9 assumptions. All 9 held. It also showed that Spring Boot's per-group timeout is ignored once Stratum
  builds the request factory.
- **`interface` cannot be a settings field:** Java reserves the word, and binding it would need Spring Boot's
  `@Name`, which ST-ENG-30 forbids. A generated API is also often several interfaces, one per tag of the OpenAPI file.
- **Names a team already knows read best:** Spring Framework 7's `@Retryable` names its retry settings `maxRetries`,
  `delay`, `multiplier` and `maxDelay`, and Resilience4j names its breaker settings. The owner rejected invented
  names (2026-11-09).

## Decision

### 1. Two starters, one model (owner, 2026-11-09)

1. `stratum-starter-http-client` (blocking, `RestClient` on Apache HttpClient 5) and
   `stratum-starter-reactive-http-client` (`WebClient` on Reactor Netty; planned,
   [#269](https://github.com/exepex/stratum/issues/269)). They share `libs/stratum-http-client` and the same keys and
   rules. A `Mono` or `Flux` method without the reactive starter stops startup.
2. A client is a Java interface with `@StratumClient("<name>")`, using Spring's own `@GetExchange`, `@PostExchange`,
   `@PathVariable`, `@RequestBody`, `@RequestParam` and `@RequestHeader`. Interfaces are found by scanning, like
   Spring Data repositories. Each client becomes its own Spring HTTP service group.
3. Interceptors are plain beans: `ClientInterceptor` for the clients named in `@StratumClient(interceptors = ...)`,
   and `GlobalClientInterceptor` for all clients. They run on every attempt, after auth.
4. Settings in Java follow ST-ENG-34. `HttpClientPropertiesCustomizer`, listed in `META-INF/spring.factories`, sets
   every key of `defaults` and of each client, and wins over YAML; the `retry` and `circuit-breaker` groups are the
   records `ClientRetry` and `ClientCircuitBreaker` and the enum `BackoffPolicy`. What has no key goes on a
   `StratumClientCustomizer` bean, which adjusts one client's builder like Spring's `RestClientCustomizer` (ST-ENG-24)
   and runs after the keys: a custom back-off, custom retry and breaker rules, and `mapError`, which throws the
   service's own exception. A code for an error status needs no Java: it is the key `errors` (owner, 2026-11-20).

### 2. One Stratum block per client (exception to ST-ENG-34)

1. **Where settings live** (owner, 2026-11-09). Every setting of a client, base URL and timeouts included, lives under
   `stratum.http-client.clients.<name>.*`. Shared values live under `stratum.http-client.defaults.*`. A key falls back
   on its own: from the client to `defaults`, then to Stratum's default. Two maps and a list merge instead: a client's
   `headers` are added to those under `defaults`, the client wins per name, and an empty value drops a default;
   `copy-headers` lists are joined, and a name with nothing to copy sends nothing; and a client's `errors` are added
   to those under `defaults`, the client winning per status (owner, 2026-11-20). An operation's settings win over its
   client's (owner, 2026-11-20), and a timeout an operation leaves unset is its client's (owner, 2026-11-17 and
   2026-11-20).
2. **Spring Boot's `spring.http.serviceclient.<name>.*` keys do not apply to a Stratum client** (owner, 2026-11-09).
   Startup stops with `SX-HTTPC-1001` when such a key is set for a Stratum client's name, naming the Stratum key to
   use instead. The check reads configuration only.
3. **Spring's own blocks stay Spring's** (owner, 2026-11-09). Stratum only names them:
   `spring.security.oauth2.client.registration.*` for client credentials, and `spring.ssl.bundle.*` for mTLS.
4. **The keys, with Stratum's defaults:**
   - `url` (required; not under `defaults`). `urls`, several addresses for one client, is planned
     ([#308](https://github.com/exepex/stratum/issues/308));
   - `auth` (none; not under `defaults`) and `ssl-bundle` (none; not under `defaults`, nor with `shared-pool`);
   - `connect-timeout` 2s, `read-timeout` 5s (owner, 2026-11-19), `total-timeout` 8s;
   - `retry` (owner, 2026-11-09): `max-retries` 3 (0 to 9), `backoff-policy` `exponential` or `fixed`
     (`exponential`), `delay` 200ms, `multiplier` 2.0 (1.0 to 10.0), `max-delay` unset, which means 2s or `delay`
     when that is longer;
   - `circuit-breaker` (owner, 2026-11-09): `enabled` true, `failure-rate-threshold` 50 (1 to 100),
     `sliding-window-size` 100 (1 to 10000), `minimum-number-of-calls` 100 (1 to 10000),
     `wait-duration-in-open-state` 60s, `permitted-number-of-calls-in-half-open-state` 10 (1 to 1000), and
     `slow-call-duration-threshold` and `slow-call-rate-threshold` (1 to 100) unset, so the slow-call rule stays
     Resilience4j's own;
   - `max-connections` 50 (1 to 10000), `max-wait` 2s, `idle-timeout` 30s, `connection-lifetime` 5m, `shared-pool`
     false;
   - `headers`, `copy-headers` (none), `trace-headers` true;
   - `mock` false;
   - `interfaces` (none), a list of full interface names, under a client only (owner, 2026-11-09);
   - `interceptor-body-limit` 1 MiB, from 1 byte to 1 GiB (2026-11-10, under the owner's autonomy terms; issue #264);
   - `call-log` `errors`, `all` or `none` (`errors`) (owner, 2026-11-10);
   - `response-body-limit` 32 MB, `-1` for none, otherwise from 1 byte to 1 GiB (owner, 2026-11-10);
   - `errors` (none): an error status from 400 to 599 mapped to the service's own code (owner, 2026-11-20);
   - `operations.<operation>.errors` (none), under a client only (owner, 2026-11-20);
   - `operations.<operation>.read-timeout` and `operations.<operation>.total-timeout` (unset: the client's), under a
     client only (owner, 2026-11-17 and 2026-11-20). The connect timeout, the pool, `max-wait`, the retries and the
     circuit breaker stay per client.

   That is 25 keys, each group counted as one and the kinds inside `auth` not counted.
5. **What stops startup.**
   - A value out of range, a duration of 0 or less, a `max-delay` below `delay` on an exponential back-off, a
     slow-call rate without its duration, a status outside 400 to 599, and a code that is empty, holds whitespace or
     starts with `SX-` stop startup with `SX-CORE-1003`, naming the rule, never the value (owner, 2026-11-09 and
     2026-11-20). No rule rejects `multiplier` or `max-delay` with `fixed`: both are ignored then, since Stratum's own
     default `multiplier` reaches every client.
   - A client's name that is not lowercase letters, digits and hyphens, from a letter, stops startup with
     `SX-HTTPC-1003`; a client without `url`, with `SX-HTTPC-1004`.
   - An operation is the methods marked `@StratumOperation("<operation>")`. A name that is not lowercase letters,
     digits and hyphens, or that two methods of one client carry, stops startup with `SX-HTTPC-1009`. A name under
     `operations` that no method carries, or `operations` under `defaults`, stops it with `SX-HTTPC-1010` (owner,
     2026-11-20).
6. **A removed or renamed key gets no hint and no code that still reads it,** because no service runs on Stratum
   yet. An old key is ignored, as any unknown key is, and the release notes name it (owner, 2026-11-18 and
   2026-11-19).

### 3. Clients Stratum did not build

1. A generated interface joins a client through its `interfaces` (owner, 2026-11-09), with all the client's settings.
   An interface marked for one client and named in another's stops startup with `SX-HTTPC-1008`; a name that is no
   interface on the classpath, with `SX-HTTPC-1007`.
2. Each configured client is a `RestClient` bean and a `RestTemplate` bean, named
   `stratum.http-client.<name>.rest-client` and `.rest-template`, injected with `@Qualifier("<name>")` (owner,
   2026-11-10). They are not default candidates, so a service's own unqualified bean stays the one injected without
   a qualifier, and a service's own bean with the same qualifier wins. `stratumHttpClients.restClient("<name>")`
   returns the same `RestClient`. How they retry is in ADR 0034, and what they throw in ADR 0031.
3. `StratumClientCustomizer` gets `RestTemplateBuilder customizeRestTemplate(String client, RestTemplateBuilder
   builder)`, whose default returns the builder unchanged; Stratum hands each customizer's result to the next, in
   their order (owner, 2026-11-10).
4. A client with its own engine gets `stratumHttpClients.settings("<name>")` only. Its retry and circuit breaker are
   planned in [#262](https://github.com/exepex/stratum/issues/262); there is no `resilience("<name>")` (owner,
   2026-11-10).
5. Stratum never wraps, replaces or checks a foreign client, and unused blocks are allowed.

### 4. Mocks (owner, 2026-11-09)

`mock: true` answers from `classpath:stratum-mocks/<client>/<method>.json` and logs a WARN at every startup.
`MockClient` scripts answers in tests. It lives in `stratum-starter-http-client`, since `stratum-test` cannot depend
on the HTTP client modules that use it, and works only for a client with `mock: true`.

## Consequences

- **A team writes an interface and 1 to 4 lines of YAML per client,** or `@Qualifier("partner") RestClient partner`,
  and gets timeouts, a bounded pool, one retry layer, a breaker, tokens, trace headers, metrics and a call log, with
  no Stratum class in its code.
- **Every default is safe without configuration (ST-ENG-25),** and a service that sets nothing behaves as the
  defaults above say; `ClientResilienceTest.defaultsAreTodays` holds the retry and breaker defaults.
- A client without `url` stops startup, as its beans are made at startup.
- A startup check that rejects old keys was weighed and not chosen: it would scan the property sources for keys no
  running service has set. Resilience4j's own Spring Boot keys were not chosen: they need a new dependency and would
  give one client two places for its settings.
- Tests that use `MockClient` need no extra dependency: the starter they already have holds it.
- Without Spring Boot's `RestTemplateBuilder` bean, as in a WebFlux service, a client's `RestTemplate` has Spring's
  default converters and no span.
- Rule ST-ENG-34 carries decision 2 as its exception (owner, 2026-11-09).
