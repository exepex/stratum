# ADR 0005: Every version in the root POM

**Status:** Accepted, 2026-10-28, by the owner. Replaces two parts of
[ADR 0004](0004-dependency-updates.md): Dependabot no longer leaves out eight Maven plugins, and a
Spring Boot upgrade no longer needs plugin versions copied by hand (step 3). Every other part of
ADR 0004 stands.

[← Documentation home](../README.md) · Where each version is set:
[Architecture](../guides/architecture.md#where-each-version-is-set)

## Context

Stratum's versions were set in four POMs. `stratum-build-parent` had the Spring Boot parent and
the versions of the build tools, `stratum-bom` had the versions of the libraries it manages, and the
root `pom.xml` and `stratum-build-rules` repeated versions they could not inherit. Twelve versions
were written twice, and `stratum-build-rules` chose its own JUnit version. Eight of the twelve were
Spring Boot's plugin versions, copied by hand into the root `pom.xml` at every Spring Boot upgrade,
and Dependabot had to be told to leave those eight alone.

The copies existed because of a loop. `stratum-build-parent` loads its rules from
`stratum-build-rules` and imports `stratum-bom`, so neither of the two can inherit
`stratum-build-parent`. Their parent was the root `pom.xml`, which had no versions of its own.

## Decision

1. The root `pom.xml` inherits `spring-boot-starter-parent`, so the Spring Boot version is written
   there, once. Every Stratum POM inherits the root `pom.xml`: `stratum-bom` and
   `stratum-build-rules` directly, and the others through `stratum-build-parent`.
2. Every other version Stratum chooses is a property in the root `pom.xml`: Java, the build tools
   and plugins Spring Boot does not manage, and the libraries `stratum-bom` manages. No other POM
   writes a version number.
3. Spring Boot's versions still come only from Spring Boot's parent. Stratum overrides none of them
   (ST-ENG-61), and none is copied any more.
4. `stratum-bom` is installed flattened in `bom` mode, without a parent. It manages only Stratum's
   modules and its extra libraries, so it still brings no Spring Boot version to a service under a
   company parent ([ADR 0001](0001-coexistence-with-company-frameworks.md)).
5. The Enforcer rule that every plugin has a version runs from the root `pom.xml`, so it covers
   every Stratum module and every service that uses `stratum-parent`.

Two versions stay outside the POMs, because Maven reads them before any POM: Stratum's own version
(`.mvn/maven.config`) and the Maven version (`.mvn/wrapper/maven-wrapper.properties`). CI's Java
version and its GitHub Actions are in `.github/workflows/build.yml`.

## Consequences

- To change a version, edit the root `pom.xml`. A Spring Boot upgrade is the parent version there,
  plus the documents that name it (ADR 0004, step 3).
- Dependabot's Spring group still upgrades the Spring Boot parent and every Spring library in one
  pull request, and it has no list of plugins to leave out.
- A service using `stratum-parent` has one more parent in its chain, `stratum`. The dependency and
  plugin versions it gets are the same as before.
- The installed `stratum-bom` also lists the root `pom.xml`'s properties, Spring Boot's included, so
  that its versions resolve when a service imports it. Maven imports only a BOM's
  `<dependencyManagement>`, so these properties do not reach the service.
- `stratum-build-rules` now tests with Spring Boot's JUnit version (6.0.3) instead of its own (6.1.3).
- A fork under a company parent ([setup C](../guides/company-framework.md#setup-c-fork-stratum-under-the-company-parent))
  replaces the parent of the root `pom.xml` instead of `stratum-build-parent`'s.
