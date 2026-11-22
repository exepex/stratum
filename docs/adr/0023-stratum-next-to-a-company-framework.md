# ADR 0023: Stratum next to a company framework

**Status:** Accepted, 2026-11-22, by the owner. Its decisions were taken from 2026-10-27 to 2026-11-15; each
gives its date.

[← Documentation home](../README.md) · How to apply it:
[Using Stratum with a company framework](../guides/company-framework.md) ·
[Versioning and upgrades](../guides/upgrading.md#compatibility) ·
[Repositories on a pool](../modules/data-named-pools.md#repositories-on-a-pool)

## Context

- **Many companies have their own framework on Spring Boot:** a parent POM that extends
  `spring-boot-starter-parent`, and starters with the company's own beans. Teams in such companies want to use
  Stratum's starters as well. Two layouts were requested:
  1. The service keeps the company parent and uses Stratum's starters through `stratum-bom`.
  2. A team forks Stratum and makes the company parent the parent of `stratum-parent`, so the chain is Spring Boot →
     company parent → `stratum-parent` → service.

  Both layouts must give one Spring Boot version and must let the company's beans take priority over Stratum's.
- **In Maven, dependency versions inherited from a parent win over versions imported from a BOM,** without a
  warning. Checked with Maven 3.9.11: a library managed at one version by a parent and at another by an imported BOM
  resolves to the parent's version.
- **In Spring, a bean cannot be stopped by another bean's priority.** Only a condition stops a bean from being
  created; `@Primary` and `@Fallback` only choose which of several existing beans is injected.
- **The Spring Boot version of a service is already decided in one place:** its parent POM. With `stratum-parent` it
  is the version Stratum is tested with; under a company parent it is the company's choice. A check that stops
  startup would repeat that decision in code, and would stop a service that runs a newer line on purpose. Under a
  company parent no build step compares the version with the line Stratum supports, and a version outside it fails
  later, with errors such as `NoSuchMethodError` that do not point at the cause.
- **The data JPA starter can map JPA repositories to named pools,** with `stratum.data.sources.<name>.packages` or
  `@UseDataSource`. Spring Boot's entity manager factory, `JdbcTemplate` and JPA transaction manager take the one
  primary `DataSource`, so the data source that picks each statement's pool must be that primary bean. A `@Fallback`
  bean would lose to Spring Boot's own `dataSource`, and the mapping would be dropped without a word. A service that
  defines its own `DataSource` makes Spring Boot create none, and a second primary `DataSource` makes injection
  ambiguous.

## Decision

1. **The parent sets the Spring Boot version** (owner, 2026-10-27). `stratum-bom` manages only Stratum's modules and
   the libraries that Spring Boot does not manage, and does not import Spring Boot's BOM. `stratum-parent` gets
   Spring Boot's versions by inheriting `spring-boot-starter-parent`, through the root `pom.xml`
   ([ADR 0027](0027-versions-and-dependency-updates.md)). Each release supports one Spring Boot line, such as
   `4.1.x`, and Java 25; older lines are not supported (owner, 2026-11-01).
2. **Stratum warns about the Spring Boot version, and never stops startup over it.**
   1. Stratum does not stop startup over the Spring Boot version; the parent POM alone decides it. Each release
      states the line it supports in the [compatibility table](../guides/upgrading.md#compatibility) (owner,
      2026-10-28).
   2. At startup, `stratum-core` compares the running Spring Boot version with the supported line. When it is
      outside, it logs one warning, `SX-CORE-1005`, naming the version found, the supported line and where to change
      it: the parent POM. Any patch release of the line is quiet (owner, 2026-11-01).
   3. The supported line comes from the build: the root `pom.xml`'s Spring Boot parent is written into
      `stratum-core`'s jar, so the version stays in one place.
   4. When Spring Boot cannot report its version, as from some repackaged jars, nothing is logged.
3. **The company's bean always wins** (owner, 2026-10-27). Every Stratum bean is `@ConditionalOnMissingBean` on the
   type services inject and is marked `@Fallback`, and every Stratum auto-configuration sorts after
   auto-configurations that declare no order. When whole setups overlap, such as two security filter chains, the
   service turns Stratum's module off with `stratum.<module>.enabled=false`; a module is on while its starter is
   present ([ADR 0024](0024-bundles-parents-and-module-switches.md)).
4. **One exception: the routing data source is Stratum's only primary bean** (owner, 2026-11-15).
   1. When repositories are mapped to named pools, the data JPA starter registers `stratumRoutingDataSource` as
      `@Primary`, with no `@Fallback` and no `@ConditionalOnMissingBean`. Without a mapping, Stratum registers nothing
      and decision 3 applies.
   2. Stratum stops startup rather than back off. A mapping together with no Spring Boot `dataSource`, another primary
      `DataSource`, or a service bean named `stratumRoutingDataSource` stops startup with `SX-DATA-1007`. Its fix
      says: use Spring Boot's dataSource and no other primary DataSource, or drop the mapping.
   3. No other Stratum bean is primary. Any later primary bean needs its own record.
5. **The fork is supported, but not recommended** (owner, 2026-10-27). The documentation recommends layout 1.
   Layout 2 is documented as supported, with its cost: the team merges every Stratum release into its fork.

## Consequences

- A service under a company parent gets no second Spring Boot version from Stratum. A team on an unsupported version
  learns of it from one line at startup, before anything fails, and is not stopped. A team that runs a newer line on
  purpose sees the warning on every start until a Stratum release supports that line.
- Java needs no check: Stratum's classes are compiled for Java 25, so an older JVM refuses to load them.
- With `stratum-parent`, a Spring Boot managed version is overridden through Spring Boot's own version property.
  Stratum itself overrides no version that Spring Boot manages, because such an override would not reach services
  under a company parent.
- A Stratum auto-configuration that must run before one of Spring Boot's can still run before a company's unordered
  auto-configuration, because `before` and `after` take precedence over the order value. Its module page says so, and
  the company orders against it with `beforeName` or the service turns the module off.
- A company auto-configuration that also sorts late and is itself `@ConditionalOnMissingBean` backs off when
  Stratum's bean exists, and `@Fallback` cannot bring it back. The company then orders against Stratum with
  `beforeName`, or the service turns the module off.
- Stratum's tests must show each bean backing off for a bean from an unordered auto-configuration, not only for a
  bean from the service.
- A service that maps repositories keeps Spring Boot's `dataSource`. A plain `DataSource` is then the routing data
  source; `@Qualifier("dataSource")` reaches the main pool, as `unwrap(HikariDataSource.class)` does. A company
  framework that defines its own primary `DataSource` cannot be combined with a mapping: the team drops the mapping
  and reaches the named pool by its qualifier. The rule is checked once, at startup, from bean definitions, so it
  costs nothing per call.
- Rules ST-ENG-21, ST-ENG-22, ST-ENG-60, ST-ENG-61 and ST-TEST-20 carry the decision into the code.
