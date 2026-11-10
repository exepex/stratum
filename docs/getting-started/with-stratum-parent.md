# Stratum as your parent POM

**Status: Built, not released.** `stratum-parent` exists and builds the example below. No release is
published yet, only [snapshots](installation.md#using-a-snapshot). `0.1.0-SNAPSHOT` is a placeholder
for the first released version.

This guide sets up a service whose `pom.xml` inherits `stratum-parent`. Use it for a new service, or
for a service whose `pom.xml` has no parent yet. If your service must keep another parent, such as
`spring-boot-starter-parent` or your company's parent, read
[Stratum as a dependency, under another parent](with-stratum-bom.md) instead.

[← Documentation home](../README.md) · [Installation](installation.md)

## What a parent POM is

A Maven *parent POM* is a `pom.xml` that other projects inherit. The child project gets the parent's
dependency versions, plugin versions and plugin settings, so it does not repeat them. A project has
at most one parent.

`stratum-parent` inherits Spring Boot's own parent, `spring-boot-starter-parent`, through Stratum's
root POM. So your service gets everything Spring Boot's parent gives, plus:

- the versions of Stratum's modules and of the extra libraries Stratum uses, from `stratum-bom`;
- Java 25;
- Stratum's build checks: formatting, Checkstyle, PMD, SpotBugs, the Maven Enforcer rules and JaCoCo;
- `build-info.properties` and `git.properties` in the jar, which Spring Boot's `info` endpoint reads;
- the executable jar.

The full list, with every setting, is in [Parent POM and BOM](../modules/parent-and-bom.md#the-parent-pom).

## Before you start

You need JDK 25 and Maven 3.9 or later. The parent checks both and stops the build with an Enforcer
error if either one is older. See [Installation](installation.md#prerequisites).

## The complete `pom.xml`

This is the whole `pom.xml` of a REST service that logs, exports metrics and traces, calls other
services over HTTP and caches results. It uses only starters that are built today: web for Spring
MVC, logging, observability, the HTTP client and the cache.

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

  <groupId>com.acme.orders</groupId>
  <artifactId>order-service</artifactId>
  <version>1.0.0-SNAPSHOT</version>

  <dependencies>
    <!-- No <version>: stratum-parent manages every Stratum module. -->
    <dependency>
      <groupId>io.github.exepex.stratum</groupId>
      <artifactId>stratum-starter-webmvc</artifactId>
    </dependency>
    <dependency>
      <groupId>io.github.exepex.stratum</groupId>
      <artifactId>stratum-starter-logging</artifactId>
    </dependency>
    <dependency>
      <groupId>io.github.exepex.stratum</groupId>
      <artifactId>stratum-starter-observability</artifactId>
    </dependency>
    <dependency>
      <groupId>io.github.exepex.stratum</groupId>
      <artifactId>stratum-starter-http-client</artifactId>
    </dependency>
    <dependency>
      <groupId>io.github.exepex.stratum</groupId>
      <artifactId>stratum-starter-cache</artifactId>
    </dependency>

    <dependency>
      <groupId>io.github.exepex.stratum</groupId>
      <artifactId>stratum-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

There is no `<build>` section: the parent already declares the Spring Boot plugin, which builds the
executable jar and writes `build-info.properties`, and the Git plugin, which writes `git.properties`.

## What you set yourself

Nothing else in the `pom.xml`. In particular:

- **No Java version.** The parent sets `java.version` to 25, and Spring Boot's parent turns it into
  the compiler's `maven.compiler.release`.
- **No `<version>` on a Stratum starter**, or on a library that Spring Boot or `stratum-bom`
  manages. The parent decides it. To change one managed version, for example for an urgent security
  fix, see [Overriding one managed version](../modules/parent-and-bom.md#overriding-one-managed-version).
- **No flatten plugin.** Stratum uses `flatten-maven-plugin` only to write fixed version numbers into
  its own published POMs. `stratum-parent` turns it off for services, so do not add it.

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

Choose the starters, set the minimum configuration and check that the service starts:
[Installation, step 2](installation.md#step-2-add-the-starters-you-need).
