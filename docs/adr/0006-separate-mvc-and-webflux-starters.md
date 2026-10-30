# ADR 0006: Separate starters for Spring MVC and WebFlux

**Status:** Accepted, 2026-10-30, by the owner. Adds one bundle to the four of
[ADR 0003](0003-bundle-starters-and-module-switches.md); every other part of ADR 0003 stands.

[← Documentation home](../README.md) · How to use it: [Web](../modules/web.md) ·
[Bundle starters](../modules/bundles.md)

## Context

Spring has two web stacks, each in its own Spring Boot starter:

- **Spring MVC** (`spring-boot-starter-webmvc`), on Tomcat, with one thread per request. Most
  services use it.
- **Spring WebFlux** (`spring-boot-starter-webflux`), on Netty, for reactive services written with
  `Mono` and `Flux`.

When both are on the classpath, Spring Boot starts Spring MVC and quietly ignores WebFlux. A single
Stratum web starter that brought Spring MVC would make every reactive service exclude it, and a
missed exclusion would start the wrong stack. A web starter that brought neither would make every
service add its stack by hand.

## Decision

1. **Two web starters.** `stratum-starter-webmvc` brings Spring MVC and `stratum-starter-webflux`
   brings WebFlux. Each holds only the code that plugs into its own stack.
2. **One shared library.** The behaviour that does not depend on the stack, such as the error
   format and the settings, is written once in a library that both starters use, so nothing is
   written twice.
3. **One switch and one set of keys.** Both starters are the module `web`: they are off until
   `stratum.web.enabled=true` and read the same `stratum.web.*` settings, so a service that moves
   from one stack to the other keeps its configuration.
4. **A bundle per stack.** `stratum-starter-microservice` and `stratum-starter-web-ui` bring
   `stratum-starter-webmvc`. The new `stratum-starter-reactive-microservice` is the microservice
   bundle with `stratum-starter-webflux` in its place.
5. **Logging and observability stay shared.** One logging starter and one observability starter
   serve both stacks; there are no reactive copies of them.

## Consequences

- A service adds the starter, or the bundle, of the stack it uses, and never excludes a stack.
- The old name `stratum-starter-web` is gone. It was never released, so no service depends on it.
- The two starters must behave the same: every feature is tested on both stacks.
