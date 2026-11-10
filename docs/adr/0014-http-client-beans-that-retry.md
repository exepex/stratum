# ADR 0014: A client's RestClient and RestTemplate are beans that retry and guard their calls

**Status:** Accepted, 2026-11-10, by the owner, who took its decisions on 2026-11-09 and 2026-11-10. Replaces two
parts of earlier records: decision 3 of [ADR 0012](0012-http-client-changes-found-while-building.md), the
`stratumHttpClients.restClient("<name>")` that sends each call once with retry from a `resilience("<name>")` wrapper;
and the `.resilience("<name>")` of decision 9.3 of [ADR 0011](0011-http-client-starters.md). Every other part of both
stands, ADR 0011 decision 4.4 included.

[← Documentation home](../README.md)

## Context

- **Generated and third-party clients take a `RestClient` or a `RestTemplate`.** An OpenAPI generator's client, a
  partner's SDK jar and older classes built on `RestTemplate` cannot be rewritten as `@StratumClient` interfaces.
- **ADR 0012 left them without retry.** Retry inside a `RestClient` was possible only with a request interceptor,
  which makes Spring hold the whole request body in memory, so ADR 0012 planned a `resilience("<name>")` wrapper that
  a team puts around each call.
- **A request factory can retry without an interceptor.** Spring calls the request factory once per call; a factory
  whose request runs each attempt as a fresh request of the client's own factory, through the client's
  `ClientResilience`, retries and guards the call while the body streams once (the C2b design note and its four
  judge rounds, on issue [#261](https://github.com/exepex/stratum/issues/261)).
- **The owner's decisions (2026-11-09):** a team injects the client the normal Spring way, by constructor, setter or
  lookup, with retry and breaker from its settings or from Java and no wrapper to write; it stays simple and
  Spring-native; each client is a `RestClient` and a `RestTemplate` bean, and the documentation steers new code to the
  `RestClient`; every Stratum client feature reaches both, retry correct, with customizers on both.

## Decision

1. Each configured client is a `RestClient` bean and a `RestTemplate` bean, named
   `stratum.http-client.<name>.rest-client` and `.rest-template`, injected with `@Qualifier("<name>")`. They are not
   default candidates, so a service's own unqualified bean stays the one injected without a qualifier, and a
   service's own bean with the same qualifier wins. `stratumHttpClients.restClient("<name>")` returns the same
   `RestClient`.
2. Both retry and guard their calls by the client's settings and `StratumClientCustomizer` beans, with the client's
   one circuit breaker, shared with its interfaces: a call without a body is retried under ADR 0011 decision 4.2; a
   call with a body is sent once; an open breaker refuses a call with `SX-HTTPC-0003`. Each attempt waits for the
   answer at most `timeout` or the time left under `total-timeout`, as ADR 0011 decision 4.4 says, so the whole call
   has its answer by `total-timeout` (owner, 2026-11-10).
3. The credential runs on each attempt's request, so a service token is fetched within the attempt and renewed after
   a 401 for a call without a body.
4. A failed call throws Spring's own exceptions, as ADR 0012 decided; only the refusal of an open breaker changes.
5. `StratumClientCustomizer` gets `RestTemplateBuilder customizeRestTemplate(String client, RestTemplateBuilder
   builder)`, whose default returns the builder unchanged; Stratum hands each customizer's result to the next, in
   their order (owner, 2026-11-10).
6. A client with its own engine gets `stratumHttpClients.settings("<name>")` only. Its retry and circuit breaker are
   planned in [#262](https://github.com/exepex/stratum/issues/262); there is no `resilience("<name>")`.

## Consequences

- A team writes `@Qualifier("partner") RestClient partner` and gets the client's address, timeouts, pool, credential,
  retries, breaker and `total-timeout`, with no Stratum class in its code.
- A client without `url` stops startup with `SX-HTTPC-1004`, as its beans are made at startup.
- A retried call through a bean is one span and one `http.client.requests` metric, with the last answer; an
  interface records one per attempt.
- A body cut off after the answer's head is neither retried nor counted. An answer read on a late attempt has only
  that attempt's time left between bytes; a team that downloads large files through a bean raises `total-timeout`.
- Without Spring Boot's `RestTemplateBuilder` bean, as in a WebFlux service, the `RestTemplate` has Spring's default
  converters and no span.
- How the decision weighs (ST-AG-14): one extra request object per attempt and no body copy (speed); no thread hop
  or lock (throughput); no log line added (log volume); two beans per client, and every dropped answer closed (memory
  and leaks); nothing new read or logged (data leaks); the credential only as configured (security); the retry rules
  in one place (maintainability); state per client only (scalability).
- The documentation is [clients Stratum did not build](../modules/http-client-own-clients.md).
