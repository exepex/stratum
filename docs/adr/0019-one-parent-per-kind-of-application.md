# ADR 0019: One parent per kind of application, which brings its bundle; one BOM

**Status:** Accepted, 2026-11-10, by the owner. It replaces two parts of
[ADR 0003](0003-bundle-starters-and-module-switches.md): decision 3 ("Parents stay build-only") and the second reason
in its Context ("A dependency that a parent declares cannot be excluded"). Every other part of ADR 0003 stands, as
[ADR 0010](0010-modules-on-when-present-and-one-compatibility-switch.md) left it.

[← Documentation home](../README.md) · How to use it:
[Stratum as your parent POM](../getting-started/with-stratum-parent.md) · [Parent POM and BOM](../modules/parent-and-bom.md)

## Context

- ADR 0003 gave each kind of application a bundle starter and kept every parent build-only. It rejected a parent per
  kind of application for two reasons: a service under a company parent cannot use it, and a dependency that a parent
  declares "cannot be excluded" by the child.
- The second reason is only partly true. A child that declares the parent's bundle again, with an `<exclusion>`, drops
  that starter: Maven uses the child's own declaration of the same dependency. This was checked on Maven 3.9.11 with
  Stratum's Enforcer rules, which accept the second declaration, and the dependency tree then has no excluded starter.
- The first reason still holds, and the BOM answers it: a service that must keep its parent imports `stratum-bom` and
  adds the bundle.
- With `stratum-parent` and a bundle, a new microservice still writes the bundle, `stratum-test` and Spring Boot's
  `spring-boot-starter-test` into every `pom.xml`. `stratum-test` brings no JUnit, so a service that forgets
  `spring-boot-starter-test` cannot compile a test.

## Decision

1. **One generic BOM.** `stratum-bom` stays the single BOM, with the versions of everything Stratum publishes. There is
   no BOM per kind of application: an import brings only versions, never dependencies or plugins; a version of a
   library a service does not use costs it nothing; and several BOMs would repeat the same versions.
2. **One bundle starter per kind of application**, as ADR 0003 decision 1 says, unchanged.
3. **One parent per kind of application.** It inherits `stratum-parent`, with all its build setup, and brings that
   kind's bundle (compile scope) and `stratum-test` and `spring-boot-starter-test` (test scope), nothing else. A
   service on it writes only `<parent>`, adds a starter by declaring it, and drops one of the bundle's starters by
   declaring the bundle again with an `<exclusion>`; this is how ADR 0003 decision 1 reads on the parent path. Only
   `stratum-microservice-parent` is built now. The parents of the other kinds are built with their bundles. Until
   then, `stratum-parent` stays the build-only parent for every kind without its own.
4. **Services that must keep Spring Boot's or a company parent** import `stratum-bom` and add the bundle, as before.

## Consequences

- A new microservice's `pom.xml` holds only its `<parent>` and its own coordinates.
- `stratum-parent` keeps its role: the build checks, for every kind of application, and the parent of each parent per
  kind.
- Adding a starter to a bundle also changes every service on that bundle's parent, so it goes in the release notes,
  as ADR 0003 says.
- Rule ST-ENG-10 names the parent per kind next to the bundle starters.
