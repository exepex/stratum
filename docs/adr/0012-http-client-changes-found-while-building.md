# ADR 0012: Changes to the HTTP client starters found while building them

**Status:** Accepted, 2026-11-09, by the owner. Replaces three parts of
[ADR 0011](0011-http-client-starters.md): the `interface` key in decisions 2.4 and 9.1, the place of `MockClient` in
decision 10, and adds what `stratumHttpClients.restClient("<name>")` does not do to decision 9.2. Every other part of
ADR 0011 stands.

[← Documentation home](../README.md)

## Context

Building ADR 0011 showed three things it did not settle:

- **The key `interface`** cannot be a settings field: `interface` is a word Java reserves. Binding it would need
  Spring Boot's `@Name`, which ST-ENG-30 forbids. A generated API is also often several interfaces, one per tag of
  the OpenAPI file, so one value is not enough.
- **`MockClient` in `stratum-test`** would make Maven reject the build: both HTTP client modules use `stratum-test`
  for their own tests, so `stratum-test` cannot depend on them.
- **Retry inside a `RestClient`** is possible only with a request interceptor, and any interceptor makes Spring hold
  the whole request body in memory (spring-web 7.0.10, `InterceptingClientHttpRequest` extends
  `AbstractBufferingClientHttpRequest`). A probe showed a 5 MB upload sent in full with `Content-Length` instead of
  streamed.

## Decision

1. The key is `interfaces`, a list of full interface names, under `stratum.http-client.clients.<name>` only (owner,
   2026-11-09). ST-ENG-30 is unchanged.
2. `MockClient` lives in `stratum-starter-http-client`, and works only for a client with `mock: true` (owner,
   2026-11-09).
3. `stratumHttpClients.restClient("<name>")` sends each call once: no retry, no circuit breaker, and no new token
   after a 401. A team gets these by wrapping the call in `stratumHttpClients.resilience("<name>")` (owner,
   2026-11-09). Uploads through it keep streaming.

## Consequences

- A team writes `interfaces: [com.partner.OrdersApi]`, a YAML list or `interfaces[0]=` in properties.
- Tests that use `MockClient` need no extra dependency: the starter they already have holds it.
- A generated client that takes a `RestClient` gets the client's address, timeouts and pool with no code, and retry
  with one wrapper per call. Without the wrapper, a failed call fails once, as the team's own `RestClient` would.
