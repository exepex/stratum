# Parent POM and BOM: `stratum-parent` and `stratum-bom`

**Status: Built, not released.** `stratum-parent` and `stratum-bom` exist in the repository and pass
`mvn verify`, but no version is published yet.
This page explains how your service gets Stratum's tested versions, Java settings and build checks.

[← Documentation home](../README.md) · How to choose between them: [Installation](../getting-started/installation.md)

## Why this matters

A Spring Boot service depends on hundreds of libraries. When each service picks versions on its own,
services drift apart, and upgrades turn into long investigations. Stratum tests one set of versions
together and publishes it in two forms:

| Artifact | What it is | Gives you |
| --- | --- | --- |
| `stratum-bom` | A *bill of materials*: a POM that lists versions only | Versions of Stratum's modules and of the extra libraries Stratum uses |
| `stratum-parent` | A parent POM that inherits Spring Boot's parent, imports the BOM and adds build settings | Spring Boot's versions, the BOM's versions, **plus** Java 25, plugin versions and quality checks |

```mermaid
flowchart TD
    SB[spring-boot-starter-parent] -->|parent of, through stratum-build-parent| SP[stratum-parent]
    SB -->|parent of| CP[company parent]
    BOM[stratum-bom] -->|imported by| SP
    SP -->|parent of| S1[order-service]
    CP -->|parent of| S2[service with a company parent]
    BOM -->|imported by| S2
```

Spring Boot's versions always come from the parent, never from the BOM. A service with
`stratum-parent` gets them from `spring-boot-starter-parent`; a service with a company parent gets
them from that parent. This way a service never receives two Spring Boot versions, and Stratum can be
used next to a company framework. The reasons are in
[ADR 0001](../adr/0001-coexistence-with-company-frameworks.md), and how to set it up is in
[Using Stratum with a company framework](../guides/company-framework.md).

## The BOM

The BOM manages versions of:

- every Stratum module,
- the extra libraries Stratum uses or recommends that Spring Boot does not manage (Resilience4j,
  cloud SDKs, and so on),
- the container images used in tests (as Maven properties).

Today it lists Stratum's own modules and MapStruct (`mapstruct`, `mapstruct-processor` and
`lombok-mapstruct-binding`). Each other extra library is added together with the first starter that
uses it.

It does **not** import Spring Boot's BOM (`spring-boot-dependencies`). If your parent does not
manage Spring Boot's versions, import `spring-boot-dependencies` yourself, at the version in the
[compatibility table](../guides/upgrading.md#compatibility). The two BOMs list different libraries,
so their order does not matter.

### The supported Spring Boot version

Each Stratum release supports one Spring Boot line, such as `4.1.x`. When your service starts,
Stratum checks the Spring Boot version that is running. If it is outside the supported line, the
service stops with a message that names both versions, so a mismatch is found on the first start
and not in production.

### Overriding one managed version

How you override a version depends on who manages the library.

**A library Spring Boot manages**, such as the PostgreSQL driver: set Spring Boot's version
property. This works with `stratum-parent` and with any parent that extends
`spring-boot-starter-parent`:

```xml
<properties>
  <postgresql.version>X.Y.Z</postgresql.version> <!-- the patched version -->
</properties>
```

**A library `stratum-bom` manages**, such as Resilience4j: a property does **not** work, because
Maven resolves the BOM's properties before importing it. Declare the dependency in your own
`dependencyManagement`. A version you declare in your own POM wins over every inherited or imported
one. With a BOM import, put it above the import to make that clear to readers:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.resilience4j</groupId>
      <artifactId>resilience4j-circuitbreaker</artifactId>
      <version>X.Y.Z</version> <!-- the patched version -->
    </dependency>
    <dependency>
      <groupId>com.exepex.stratum</groupId>
      <artifactId>stratum-bom</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

> [!NOTE]
> With a company parent, a library that both the company parent and `stratum-bom` manage gets the
> company parent's version: Maven lets versions inherited from a parent win over versions imported
> from a BOM, without a warning. See
> [Using Stratum with a company framework](../guides/company-framework.md#rule-1-one-spring-boot-version-set-by-the-parent).

> [!WARNING]
> An overridden version is no longer tested with the rest. Do it only for an urgent security fix,
> and remove the override at the next Stratum upgrade.

## The parent POM

On top of Spring Boot's parent and the BOM, `stratum-parent` sets the following. Everything except
the build info and packaging rows comes from `stratum-build-parent`, its own parent, which Stratum's
libraries and starters also inherit, through `stratum-library-parent`.

| Area | Setting |
| --- | --- |
| Java | `java.version=25`, which Spring Boot's parent turns into `maven.compiler.release`; `-parameters`; UTF-8 everywhere |
| Annotation processors | Lombok, MapStruct (with `lombok-mapstruct-binding`) and `spring-boot-configuration-processor` run at compile time, at the versions Spring Boot and the BOM manage. A processor does nothing unless your code uses its annotations. To add your own, declare `<annotationProcessorPaths combine.children="append">` in your `maven-compiler-plugin` configuration, so the list is extended rather than replaced |
| Plugin versions | Spring Boot's parent sets the versions of the plugins it manages, such as Compiler, Surefire, Failsafe, Enforcer and the Spring Boot plugin. `stratum-build-parent` sets the rest: JaCoCo, Spotless, Checkstyle, PMD, SpotBugs, FindSecBugs and OWASP Dependency-Check, one property each in `<properties>` |
| Tests | `*Test.java` run by Surefire in `mvn test`; `*IT.java` run by Failsafe in `mvn verify` |
| Formatting | Spotless checks Google Java Format; `mvn spotless:apply` fixes it |
| Static analysis | Checkstyle, PMD and SpotBugs with the FindSecBugs security rules. The Checkstyle, PMD and Enforcer rule files come from `stratum-build-rules` |
| Enforcer | Requires JDK 25 and Maven 3.9+; bans duplicate classes, version ranges and `LATEST`/`RELEASE`, a dependency declared twice, plugins without a version, and library versions with known remote code execution flaws (Log4j 1.x, Log4j 2 before 2.17.1, Commons Collections before 3.2.2, Commons Text before 1.10.0, SnakeYAML before 2.0) |
| Build info | `/actuator/info` shows the build version and time (`build-info.properties`) and the Git commit (`git.properties`) |
| Packaging | `spring-boot-maven-plugin` builds an executable jar; `mvn spring-boot:build-image` builds a container image |

Every execution that Stratum's parent POMs add has an id starting with `stratum-`, such as
`stratum-checkstyle-check`, so it never merges with an execution of a company parent.

The Checkstyle rules check names and imports, and reject magic numbers, console output and
`Thread.sleep`. They do not ask your service for Javadoc: whether to write it is your team's choice.
Stratum's own libraries and starters are also checked for Javadoc on every type and method, by a
second set of rules that only `stratum-library-parent` runs.
Layout is left to Google Java Format. The PMD rules are PMD's best-practice, error-prone,
multithreading, performance and security categories, without the few rules that conflict with
Stratum's conventions; each exclusion is explained in the rule file.

### Build checks your service gets

| Phase | Command | Checks |
| --- | --- | --- |
| validate | `mvn validate` | Formatting, Checkstyle, Enforcer rules |
| test | `mvn test` | Unit and slice tests |
| verify | `mvn verify` | Integration tests, PMD, SpotBugs, coverage report |

### Optional profiles

| Profile | Command | Adds |
| --- | --- | --- |
| `coverage-gate` | `mvn verify -Pcoverage-gate` | Fails the build below the coverage threshold |
| `security` | `mvn verify -Psecurity` | Scans dependencies with OWASP Dependency-Check and fails on a vulnerability scored 7.0 or higher. It reads an NVD API key from the `NVD_API_KEY` environment variable |

### Tuning the checks

A property a service may set in its own `pom.xml` (it can raise the bar, never lower it below Stratum's minimum):

| Maven property | Default | Meaning |
| --- | --- | --- |
| `stratum.coverage.minimum` | `0.80` | Line coverage required by `coverage-gate` |

## Upgrading

Change the version of `stratum-parent` (or `stratum-bom`) in one place and run `mvn verify`. Read the
release notes first; see [Versioning and upgrades](../guides/upgrading.md).
