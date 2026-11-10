# Stratum as your parent POM

**Status: Built, not released.** `stratum-microservice-parent` and `stratum-parent` exist and build the
examples below. No release is published yet, only [snapshots](installation.md#using-a-snapshot).
`0.1.0-SNAPSHOT` is a placeholder for the first released version.

This guide sets up a service whose `pom.xml` inherits a Stratum parent. Use it for a new service, or
for a service whose `pom.xml` has no parent yet. If your service must keep another parent, such as
`spring-boot-starter-parent` or your company's parent, read
[Stratum as a dependency, under another parent](with-stratum-bom.md) instead.

[← Documentation home](../README.md) · [Installation](installation.md)

## What a parent POM is

A Maven *parent POM* is a `pom.xml` that other projects inherit. The child project gets the parent's
dependencies, dependency versions, plugin versions and plugin settings, so it does not repeat them. A
project has at most one parent.

Stratum has two parents for services:

| Parent | For | What it brings |
| --- | --- | --- |
| `stratum-microservice-parent` | A REST microservice | Everything `stratum-parent` brings, plus the [`stratum-starter-microservice` bundle](../modules/bundles.md) and the test libraries |
| `stratum-parent` | Any other kind of application, until it has its own parent | The build setup only: you add a bundle or single starters yourself |

`stratum-parent` inherits Spring Boot's own parent, `spring-boot-starter-parent`, through Stratum's
root POM. So your service gets everything Spring Boot's parent gives, plus:

- the versions of Stratum's modules and of the extra libraries Stratum uses, from `stratum-bom`;
- Java 25;
- Stratum's build checks: formatting, Checkstyle, PMD, SpotBugs, the Maven Enforcer rules and JaCoCo;
- `build-info.properties` and `git.properties` in the jar, which Spring Boot's `info` endpoint reads;
- the executable jar.

The full list, with every setting, is in [Parent POM and BOM](../modules/parent-and-bom.md#the-parent-pom).
Why there is one parent per kind of application is in
[ADR 0019](../adr/0019-one-parent-per-kind-of-application.md).

## Before you start

You need JDK 25 and Maven 3.9 or later. The parent checks both and stops the build with an Enforcer
error if either one is older. See [Installation](installation.md#prerequisites).

## The complete `pom.xml` of a microservice

This is the whole `pom.xml` of a REST service that logs, exports metrics and traces, calls other
services over HTTP, caches results and has tests:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <!-- Stratum's microservice parent: Spring Boot 4.1, Java 25, Stratum's versions, its build checks,
       the microservice bundle and the test libraries. -->
  <parent>
    <groupId>io.github.exepex.stratum</groupId>
    <artifactId>stratum-microservice-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <relativePath/> <!-- look the parent up in the repository, not on disk -->
  </parent>

  <groupId>com.acme.orders</groupId>
  <artifactId>order-service</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</project>
```

There is no `<dependencies>` section. The parent brings:

| Dependency | Scope | What it gives |
| --- | --- | --- |
| `stratum-starter-microservice` | compile | The single starters web MVC, logging, observability, HTTP client and cache ([Bundle starters](../modules/bundles.md)) |
| `stratum-test` | test | Stratum's test containers ([Testing](../modules/test.md)) |
| `spring-boot-starter-test` | test | JUnit 5, AssertJ, Mockito and Spring Boot's test support |

There is no `<build>` section either: the parent already declares the Spring Boot plugin, which builds
the executable jar and writes `build-info.properties`, and the Git plugin, which writes `git.properties`.

### Add a starter

Declare it, with no `<version>`. For example, a service that calls secured services:

```xml
<dependencies>
  <dependency>
    <groupId>io.github.exepex.stratum</groupId>
    <artifactId>stratum-starter-oauth2-client</artifactId>
  </dependency>
</dependencies>
```

### Leave a bundled starter out

Declare the bundle again, with an `<exclusion>`. Your own declaration replaces the parent's, so the
excluded starter is not on your classpath. Here the service drops Stratum's caching:

```xml
<dependencies>
  <dependency>
    <groupId>io.github.exepex.stratum</groupId>
    <artifactId>stratum-starter-microservice</artifactId>
    <exclusions>
      <exclusion>
        <groupId>io.github.exepex.stratum</groupId>
        <artifactId>stratum-starter-cache</artifactId>
      </exclusion>
    </exclusions>
  </dependency>
</dependencies>
```

To keep a starter on the classpath but its module off, set `stratum.<module>.enabled=false` instead
([Bundle starters](../modules/bundles.md#leave-a-starter-out)).

## Any other kind of application: `stratum-parent`

A reactive service, a web UI, a batch job or a command-line application has no parent of its own yet.
Use `stratum-parent` and add the bundle for your kind of application, with the test libraries. This
is the complete `pom.xml` of a batch job:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <!-- Stratum's parent: Spring Boot 4.1, Java 25, Stratum's versions and its build checks. -->
  <parent>
    <groupId>io.github.exepex.stratum</groupId>
    <artifactId>stratum-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <relativePath/> <!-- look the parent up in the repository, not on disk -->
  </parent>

  <groupId>com.acme.billing</groupId>
  <artifactId>invoice-job</artifactId>
  <version>1.0.0-SNAPSHOT</version>

  <dependencies>
    <!-- No <version>: stratum-parent manages every published Stratum module. -->
    <dependency>
      <groupId>io.github.exepex.stratum</groupId>
      <artifactId>stratum-starter-batch</artifactId>
    </dependency>

    <dependency>
      <groupId>io.github.exepex.stratum</groupId>
      <artifactId>stratum-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

## What you set yourself

Nothing else in the `pom.xml`. In particular:

- **No Java version.** The parent sets `java.version` to 25, and Spring Boot's parent turns it into
  the compiler's `maven.compiler.release`.
- **No `<version>` on a Stratum starter**, or on a library that Spring Boot or `stratum-bom`
  manages. The parent decides it. To change one managed version, for example for an urgent security
  fix, see [Overriding one managed version](../modules/parent-and-bom.md#overriding-one-managed-version).
- **No flatten plugin.** Stratum uses `flatten-maven-plugin` only to write fixed version numbers into
  its own published POMs. Stratum's parents turn it off for services, so do not add it.

One Maven property is optional: `stratum.coverage.minimum`, the line coverage that the
`coverage-gate` profile requires ([Tuning the checks](../modules/parent-and-bom.md#tuning-the-checks)).

## Build it

```bash
mvn verify
```

This runs the build checks with your tests. Which check runs in which phase is listed in
[Build checks your service gets](../modules/parent-and-bom.md#build-checks-your-service-gets). To fix
the formatting that the check reports, run `mvn spotless:apply`.

The jar in `target/` is executable, and it holds `META-INF/build-info.properties` and
`BOOT-INF/classes/git.properties`. A build without a `.git` folder, as in many Docker builds, still
works; its `git.properties` then has no commit.

## Next steps

Set the minimum configuration and check that the service starts:
[Installation, step 3](installation.md#step-3-set-the-minimum-configuration).
