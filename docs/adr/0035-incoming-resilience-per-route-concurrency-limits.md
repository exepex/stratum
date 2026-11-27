# ADR 0035: Incoming resilience: per-route concurrency limits on Spring MVC

**Status:** Accepted by the owner with the design note of the incoming part, 2026-11-26. Its decisions were taken
on 2026-11-25 and 2026-11-26; each gives its date.

The first record on `stratum-starter-resilience`. It owns what the incoming part limits, how a refusal is answered
and where the limits work. Outgoing policies, such as retries and circuit breakers around a service's own methods,
will have their own record.

[← Documentation home](../README.md) · How to use it: [Resilience](../modules/resilience.md)

## Context

- **Many services will run on Stratum at high volume.** One slow endpoint can hold every server thread and pool
  connection until the whole service fails; a limit per route keeps the rest working.
- **Spring has no limit that refuses at once for every Spring MVC handler.** Resilience4j's `@Bulkhead` needs
  AspectJ, silently does nothing without it, and gives its permit back early for most asynchronous return types;
  Spring's `@ConcurrencyLimit` and Tomcat's valves cover synchronous handlers only, and their blocking mode waits
  (probe of the design note, 2026-11-25).
- **The web starters already answer every failure with a code** with its status from `stratum.web.error.statuses`
  and Stratum's body ([ADR 0026](0026-error-format-and-backward-compatible-switch.md)), and a starter may add a
  default status for its own code below the service's, as the HTTP client starters do
  ([ADR 0031](0031-http-client-errors-and-body-limits.md)).

## Decision

### 1. Scope (owner, 2026-11-25)

1. Resilience has an incoming and an outgoing part, configured and switched separately; the incoming part comes
   first, under `stratum.resilience.incoming.*`.
2. Stratum gives the mechanism and the team chooses the numbers: no priority classes, and no rate limiting, which
   belongs to the gateway or the rate-limit module ([#219](https://github.com/exepex/stratum/issues/219)).
3. Every limit is off by default. A service that adds the starter and sets no route behaves exactly as before.
4. Limits count per instance, and the module page says so.
5. Spring MVC first. WebFlux is parked in [#420](https://github.com/exepex/stratum/issues/420), and pausing message
   listeners while a circuit breaker is open in [#421](https://github.com/exepex/stratum/issues/421). Retries,
   dead letters and poison messages belong to the messaging starters. ADR 0025 is unchanged: the module page states
   the gap with #420.

### 2. The limit (owner, 2026-11-25)

1. A route is a path pattern within the application, as in `stratum.web.timeout.routes`; the most specific matching
   route counts a request, whatever its HTTP method. `max-concurrent` is from 1 to 100000.
2. Each route has one Resilience4j semaphore bulkhead that never waits, held by a servlet filter for the whole
   request: until the chain returns, or, for an asynchronous request, until the server completes it, through an
   `AsyncListener` that listens again when the request goes asynchronous a second time and gives the permit back
   once.
3. The filter runs after Spring Security, so a request it answers is not counted, and inside Stratum's web filters.
4. Actuator and probe paths are never counted, even under a route of `/**`: actuator's base path with all below it
   (each endpoint's own path when the base path is `/`), every endpoint, a service's own `@Endpoint` beans included
   (owner, 2026-11-26), `/livez`, `/readyz` and each health group's `server:` path. Both the observability and the
   resilience starters read these paths through one class in `stratum-core`.

### 3. The refusal (owner, 2026-11-25)

1. A refused request gets `SX-RESILIENCE-0001` with the status `503` and a `Retry-After` header
   (`stratum.resilience.incoming.retry-after`, `1s` by default). The starter adds the `503` to
   `stratum.web.error.statuses` below the service's own values, so a team may choose `429`.
2. The refusal goes through Spring MVC's exception resolvers, where the web starter answers it with Stratum's body.
   Without the web starter, the caller gets `503` with Spring Boot's body.
3. A refused request writes no log line while its code has a status; the request log line and a counter record it.
   With no status, it is logged as any code with no status, so the `500` is explained.

### 4. Kinds of application (owner, 2026-11-25)

Each protection switches on only where its kind of incoming work exists, on Spring Boot's own condition: a servlet
application is limited; an application with no web server ignores the routes, with no warning; a reactive
application that sets routes stops startup with `SX-RESILIENCE-1002`.

### 5. Bundles (owner, 2026-11-25)

The starter joins `stratum-starter-microservice` and `stratum-starter-web-ui`, not the reactive bundle, and is
published from this change. Adding it to the bundles is a breaking-change entry in the release notes
([ADR 0024](0024-bundles-parents-and-module-switches.md)).

## Consequences

- A team protects a slow route with one line, and every other route keeps working when it fills.
- A limit of 10 on 4 instances admits 40: teams size limits per instance.
- A service's own catch-all `@ExceptionHandler(Exception.class)` answers a refusal first, as it does every failure;
  the module page names this.
- WebFlux services and message listeners get no incoming protection until #420 and #421.
