# ADR 0003: Bundle starters and module switches

**Status:** Accepted, 2026-10-28, by the owner. Changes how ADR 0001 keeps an overlapping setup off:
Stratum's modules are now off by default. Every other part of ADR 0001 stands.

[← Documentation home](../README.md) · How to use it: [Bundle starters](../modules/bundles.md)

## Context

A service needs several starters, and most services of the same kind need the same ones. Listing
them in every service repeats work, and a missed one goes unnoticed.

A parent POM per kind of application was considered and rejected, for two reasons:

- A Maven project has exactly one parent. A service under a company parent could not use a Stratum
  parent per kind of application, which breaks the setup ADR 0001 recommends.
- A dependency that a parent declares cannot be excluded by the child, so every service would get
  all of them whether it needs them or not.

## Decision

1. **Bundle starters, one per kind of application.** `stratum-starter-microservice`,
   `stratum-starter-web-ui`, `stratum-starter-batch` and `stratum-starter-cli` contain no code and
   only list single starters. A service adds one bundle, adds more single starters if it needs them,
   and excludes any it does not want. The single starters stay available on their own.
2. **No unrelated dependencies.** A bundle lists only what its kind of application needs, and a
   single starter never depends on another unrelated starter.
3. **Parents stay build-only.** `stratum-parent` sets plugins and build checks, and no dependencies.
4. **One switch per module.** Every auto-configuration of a module carries
   `@ConditionalOnStratumModule("<module>")` from `stratum-core`, which reads
   `stratum.<module>.enabled`. Every module is **off until the service sets it to `true`**, even
   when its starter is on the classpath through a bundle. A team switches on each module it uses.

## Consequences

- Adding a starter or a bundle sets nothing up by itself. Each service lists the modules it uses
  as `stratum.<module>.enabled=true`, and the module pages' examples show that line.
- Next to a company framework, Stratum's version of an overlapping concern stays off unless the
  team enables it. This replaces the `stratum.<module>.enabled=false` step of ADR 0001.
- A service that does not want a module's libraries leaves its starter out, or excludes it from
  the bundle.
- The switch is read from properties only. A `StratumPropertiesCustomizer` cannot change it,
  because Spring decides which beans to create before any customizer runs.
- Adding a module to a bundle is a change for every service that uses the bundle, and goes in the
  release notes as one.
- Rule ST-ENG-21 uses `@ConditionalOnStratumModule` instead of `@ConditionalOnProperty`.
