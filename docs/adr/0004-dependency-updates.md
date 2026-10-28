# ADR 0004: Weekly dependency updates with Dependabot

**Status:** Accepted, 2026-10-28, by the owner.

[← Documentation home](../README.md)

## Context

Every service built on Stratum gets its libraries from Stratum, so an upgrade Stratum skips is
missed in every service, and an upgrade that breaks Stratum breaks every service. Upgrades must
arrive regularly, and each one must pass the same checks as any other change before it merges.

## Decision

[`.github/dependabot.yml`](../../.github/dependabot.yml) asks GitHub's Dependabot to open pull
requests that upgrade Stratum's dependencies:

- **What:** the Maven dependencies and plugins in every `pom.xml`, and the GitHub Actions in
  `.github/workflows/`, which stay pinned by commit SHA.
- **When:** weekly, on Monday at 06:00 UTC. A release is offered only once it is 7 days old, because
  a broken release is usually withdrawn or fixed within days. Security updates, opened from a
  Dependabot alert, wait for neither.
- **Groups:** one pull request for every `org.springframework*` dependency, so the Spring Boot
  parent and any other Spring library are upgraded together; one for all other Maven upgrades; one
  for all GitHub Actions. At most five pull requests are open per ecosystem.
- **Excluded:** the eight Maven plugins whose versions the root `pom.xml` copies from
  `spring-boot-starter-parent`. They change only with a Spring Boot upgrade.
- **Checks:** a Dependabot pull request runs the full build in
  [`build.yml`](../../.github/workflows/build.yml), like every pull request
  ([Checks on every pull request](../contributing.md#checks-on-every-pull-request)).
- **Merge:** never automatic. The owner merges each one.

## Reviewing a Dependabot pull request

1. The build is green.
2. Read the release notes of every upgraded library for breaking changes and changed defaults.
3. For a Spring Boot upgrade, also copy the new plugin versions from `spring-boot-dependencies` into
   the root `pom.xml`, and update every document that names the Spring Boot version: the badge and
   version table in `README.md`, the [compatibility table](../guides/upgrading.md#compatibility) and
   the [FAQ](../guides/faq.md).
4. The owner merges it.

## Consequences

- Upgrades arrive in at most three small pull requests a week, each one tested.
- A Spring Boot upgrade may need a person to finish it (step 3) before it merges.
- The build blocks a merge only once `main` requires the **Verify and SBOM** check, a repository
  setting the owner switches on.
