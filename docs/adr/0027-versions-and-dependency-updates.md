# ADR 0027: Every version in the root POM, and weekly dependency updates with Dependabot

**Status:** Accepted, 2026-11-22, by the owner. Its decisions were taken on 2026-10-28.

[← Documentation home](../README.md) · Where each version is set:
[Architecture](../guides/architecture.md#where-each-version-is-set)

## Context

- **Every service built on Stratum gets its libraries from Stratum,** so an upgrade Stratum skips is missed in every
  service, and an upgrade that breaks Stratum breaks every service. Upgrades must arrive regularly, and each one must
  pass the same checks as any other change before it merges.
- **Stratum's versions were set in four POMs.** `stratum-build-parent` had the Spring Boot parent and the versions of
  the build tools, `stratum-bom` had the versions of the libraries it manages, and the root `pom.xml` and
  `stratum-build-rules` repeated versions they could not inherit. Twelve versions were written twice, eight of them
  Spring Boot's plugin versions, copied by hand at every Spring Boot upgrade.
- **The copies existed because of a loop.** `stratum-build-parent` loads its rules from `stratum-build-rules` and
  imports `stratum-bom`, so neither of the two can inherit `stratum-build-parent`. Their parent was the root
  `pom.xml`, which had no versions of its own.

## Decision

1. **The root `pom.xml` inherits `spring-boot-starter-parent`,** so the Spring Boot version is written there, once.
   Every Stratum POM inherits the root `pom.xml`: `stratum-bom` and `stratum-build-rules` directly, and the others
   through `stratum-build-parent`.
2. **Every other version Stratum chooses is a property in the root `pom.xml`:** Java, the build tools and plugins
   Spring Boot does not manage, and the libraries `stratum-bom` manages. No other POM writes a version number.
3. **Spring Boot's versions come only from Spring Boot's parent.** Stratum overrides none of them (ST-ENG-61), and
   none is copied.
4. **`stratum-bom` is installed flattened in `bom` mode, without a parent.** It manages only Stratum's modules and its
   extra libraries, so it brings no Spring Boot version to a service under a company parent
   ([ADR 0023](0023-stratum-next-to-a-company-framework.md)).
5. **The Enforcer rule that every plugin has a version runs from the root `pom.xml`,** so it covers every Stratum
   module and every service that uses `stratum-parent`.
6. **Weekly updates with Dependabot.** [`.github/dependabot.yml`](../../.github/dependabot.yml) asks GitHub's
   Dependabot to open pull requests that upgrade Stratum's dependencies:
   - **What:** the Maven dependencies and plugins in every `pom.xml`, and the GitHub Actions in
     `.github/workflows/`, which stay pinned by commit SHA. No plugin is left out.
   - **When:** weekly, on Monday at 06:00 UTC. A release is offered only once it is 7 days old, because a broken
     release is usually withdrawn or fixed within days. Security updates, opened from a Dependabot alert, wait for
     neither.
   - **Groups:** one pull request for every `org.springframework*` dependency, so the Spring Boot parent and any other
     Spring library are upgraded together; one for all other Maven upgrades; one for all GitHub Actions. At most five
     pull requests are open per ecosystem.
   - **Checks:** a Dependabot pull request runs the full build in
     [`build.yml`](../../.github/workflows/build.yml), like every pull request
     ([Checks on every pull request](../contributing.md#checks-on-every-pull-request)).
   - **Merge:** never automatic. The owner merges each one.
7. **Reviewing a Dependabot pull request:**
   1. The build is green.
   2. Read the release notes of every upgraded library for breaking changes and changed defaults.
   3. For a Spring Boot upgrade, update every document that names the Spring Boot version: the badge and version
      table in `README.md`, the [compatibility table](../guides/upgrading.md#compatibility) and the
      [FAQ](../guides/faq.md).
   4. The owner merges it.

Two versions stay outside the POMs, because Maven reads them before any POM: Stratum's own version
(`.mvn/maven.config`) and the Maven version (`.mvn/wrapper/maven-wrapper.properties`). CI's Java version and its
GitHub Actions are in `.github/workflows/build.yml`.

## Consequences

- To change a version, edit the root `pom.xml`. A Spring Boot upgrade is the parent version there, plus the
  documents of decision 7, step 3.
- Upgrades arrive in at most three small pull requests a week, each one tested.
- The build blocks a merge only once `main` requires the **Verify and SBOM** check, a repository setting the owner
  switches on.
- A service using `stratum-parent` has one more parent in its chain, `stratum`. The dependency and plugin versions it
  gets are the same as before.
- The installed `stratum-bom` also lists the root `pom.xml`'s properties, Spring Boot's included, so that its
  versions resolve when a service imports it. Maven imports only a BOM's `<dependencyManagement>`, so these
  properties do not reach the service.
- `stratum-build-rules` tests with Spring Boot's JUnit version.
- A fork under a company parent ([setup C](../guides/company-framework.md#setup-c-fork-stratum-under-the-company-parent))
  replaces the parent of the root `pom.xml` instead of `stratum-build-parent`'s.
- Rules ST-ENG-60 and ST-ENG-61 carry the decision into the code.
