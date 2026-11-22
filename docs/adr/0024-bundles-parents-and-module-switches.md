# ADR 0024: Bundle starters, a parent per kind of application, and module switches

**Status:** Accepted, 2026-11-22, by the owner. Its decisions were taken from 2026-10-28 to 2026-11-10; each
gives its date.

[← Documentation home](../README.md) · How to use it: [Bundle starters](../modules/bundles.md) ·
[Stratum as your parent POM](../getting-started/with-stratum-parent.md) · [Parent POM and BOM](../modules/parent-and-bom.md)

## Context

- **A service needs several starters,** and most services of the same kind need the same ones. Listing them in every
  service repeats work, and a missed one goes unnoticed.
- **A Maven project has exactly one parent.** A service under a company parent cannot use a Stratum parent, which
  is the setup [ADR 0023](0023-stratum-next-to-a-company-framework.md) recommends; the BOM serves it instead.
- **A dependency that a parent declares can still be dropped by the child.** A child that declares the parent's
  bundle again, with an `<exclusion>`, drops that starter: Maven uses the child's own declaration of the same
  dependency. This was checked on Maven 3.9.11 with Stratum's Enforcer rules, which accept the second declaration.
- **With only `stratum-parent` and a bundle,** a new microservice writes the bundle, `stratum-test` and Spring Boot's
  `spring-boot-starter-test` into every `pom.xml`. `stratum-test` brings no JUnit, so a service that forgets
  `spring-boot-starter-test` cannot compile a test.
- **A module that is off until a service turns it on** gives a team nothing until it writes
  `stratum.<module>.enabled=true` for each module, and a missed line shows only in one startup line. No service ran
  on Stratum when this was decided, so making modules on by default broke none.

## Decision

1. **Bundle starters, one per kind of application** (owner, 2026-10-28). `stratum-starter-microservice`,
   `stratum-starter-web-ui`, `stratum-starter-batch` and `stratum-starter-cli`, and
   `stratum-starter-reactive-microservice` (owner, 2026-10-30), contain no code and only list single starters. Which
   web starter each brings is in [ADR 0025](0025-web-starters-for-mvc-and-webflux.md). A service adds one bundle,
   adds more single starters if it needs them, and excludes any it does not want. The single starters stay available
   on their own.
2. **No starter depends on another** (owner, 2026-10-28 and 2026-11-03). A bundle lists only what its kind of
   application needs. No single starter depends on another starter, except in test scope, such as
   `stratum-starter-webmvc` and `stratum-starter-webflux` using `stratum-starter-observability` in their tests.
   Starters share code and settings through a library in `libs/`, and shared code moves there only when a second
   starter needs it. At run time, starters meet through Spring, and a starter never changes its behaviour based on
   another starter's settings (ST-ENG-11).
3. **One generic BOM** (owner, 2026-11-10). `stratum-bom` is the single BOM, with the versions of everything Stratum
   publishes. There is no BOM per kind of application: an import brings only versions, never dependencies or plugins;
   a version of a library a service does not use costs it nothing; and several BOMs would repeat the same versions.
4. **One parent per kind of application** (owner, 2026-11-10). It inherits `stratum-parent`, with all its build
   setup, and brings that kind's bundle (compile scope) and `stratum-test` and `spring-boot-starter-test` (test
   scope), nothing else. A service on it writes only `<parent>`, adds a starter by declaring it, and drops one of the
   bundle's starters by declaring the bundle again with an `<exclusion>`. Only `stratum-microservice-parent` is built
   now; the parents of the other kinds are built with their bundles. Until then, `stratum-parent` stays the
   build-only parent for every kind without its own. Services that must keep Spring Boot's or a company parent import
   `stratum-bom` and add the bundle.
5. **One switch per module, on when its starter is present** (owner, 2026-10-28 and 2026-11-03).
   1. Every auto-configuration of a module carries `@ConditionalOnStratumModule("<module>")` from `stratum-core`,
      which reads `stratum.<module>.enabled`. A module is on when its starter is on the classpath, and a service turns
      it off with `stratum.<module>.enabled=false`.
   2. The switch is read from properties only. A `StratumPropertiesCustomizer` cannot change it, because Spring decides
      which beans to create before any customizer runs.
   3. A missing required value stops startup with a message naming the key and `stratum.<module>.enabled=false`. A
      module never switches itself off quietly.

## Consequences

- A new service adds a starter, or writes only `<parent>`, and gets each module on without writing a line. A new
  microservice's `pom.xml` holds only its `<parent>` and its own coordinates.
- Next to a company framework, a team turns off an overlapping module with `stratum.<module>.enabled=false`
  ([ADR 0023](0023-stratum-next-to-a-company-framework.md), decision 3).
- A service that does not want a module's libraries leaves its starter out, or excludes it from the bundle.
- Adding a starter to a bundle turns on a module in every service that uses the bundle or its parent, so it goes in
  the release notes as a breaking change (ST-DOC-10).
- `stratum-parent` keeps its role: the build checks, for every kind of application, and the parent of each parent per
  kind.
- Startup stops on a missing value instead of running half-configured. No request path changes; startup log volume
  changes only through `ModulesLeftOffNotice`.
- Rules ST-ENG-10 and ST-ENG-21 carry the decision into the code.
