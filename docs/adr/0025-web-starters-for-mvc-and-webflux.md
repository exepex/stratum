# ADR 0025: Separate web starters for Spring MVC and WebFlux

**Status:** Accepted, 2026-11-22, by the owner. Its decisions were taken on 2026-10-30 and 2026-11-03; each
gives its date.

[← Documentation home](../README.md) · How to use it: [Web](../modules/web.md) ·
[Bundle starters](../modules/bundles.md)

## Context

Spring has two web stacks, each in its own Spring Boot starter:

- **Spring MVC** (`spring-boot-starter-webmvc`), on Tomcat, with one thread per request. Most services use it.
- **Spring WebFlux** (`spring-boot-starter-webflux`), on Netty, for reactive services written with `Mono` and
  `Flux`.

When both are on the classpath, Spring Boot starts Spring MVC and quietly ignores WebFlux. A single Stratum web
starter that brought Spring MVC would make every reactive service exclude it, and a missed exclusion would start the
wrong stack. A web starter that brought neither would make every service add its stack by hand.

## Decision

1. **Two web starters** (owner, 2026-10-30). `stratum-starter-webmvc` brings Spring MVC and
   `stratum-starter-webflux` brings WebFlux. Each holds only the code that plugs into its own stack.
2. **One shared library** (owner, 2026-10-30). The behaviour that does not depend on the stack, such as the error
   format and the settings, is written once in a library that both starters use, so nothing is written twice.
3. **One module and one set of keys.** Both starters are the module `web` and read the same `stratum.web.*`
   settings, so a service that moves from one stack to the other keeps its configuration (owner, 2026-10-30). The
   module is on when either starter is present, and off with `stratum.web.enabled=false`
   ([ADR 0024](0024-bundles-parents-and-module-switches.md), decision 5; owner, 2026-11-03). The compatibility
   switch is not a web key: it is `stratum.backward-compatible`, in `stratum-core`
   ([ADR 0026](0026-error-format-and-backward-compatible-switch.md); owner, 2026-11-03).
4. **A bundle per stack** (owner, 2026-10-30). `stratum-starter-microservice` and `stratum-starter-web-ui` bring
   `stratum-starter-webmvc`. `stratum-starter-reactive-microservice` brings `stratum-starter-webflux` in its place,
   with the logging and observability starters. It brings no HTTP client starter: the blocking one would give a
   WebFlux service a blocking client, so the reactive HTTP client starter joins it once built (planned,
   [#269](https://github.com/exepex/stratum/issues/269)).
5. **Logging and observability stay shared** (owner, 2026-10-30). One logging starter and one observability starter
   serve both stacks; there are no reactive copies of them.

## Consequences

- A service adds the starter, or the bundle, of the stack it uses, and never excludes a stack.
- The two starters must behave the same: every feature is tested on both stacks.
