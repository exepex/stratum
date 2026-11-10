# Stratum as a dependency, under another parent

**Status: Built, not released.** `stratum-bom` exists and builds both examples below, but no version
is published yet. `0.1.0-SNAPSHOT` is a placeholder for the first released version.

This guide sets up a service that keeps its own parent POM and gets Stratum through `stratum-bom`.
Use it when your `pom.xml` must keep `spring-boot-starter-parent` or your company's parent. If your
service can use Stratum's parent instead, [Stratum as your parent POM](with-stratum-parent.md) is
simpler and gives you more.

[← Documentation home](../README.md) · [Installation](installation.md)

## What a BOM is

A *BOM* (bill of materials) is a POM that lists dependency versions and nothing else. You import it
in the `<dependencyManagement>` section of your `pom.xml`. Your dependencies then need no
`<version>`: Maven takes it from the BOM.

`stratum-bom` lists the versions of Stratum's modules and of the extra libraries Stratum uses that
Spring Boot does not manage, such as Resilience4j and MapStruct. It lists no Spring Boot versions and
no plugins: those come from your parent, or from you. The reasons are in
[Parent POM and BOM](../modules/parent-and-bom.md#the-bom).

## Requirements that no parent can change

Stratum works only with **Spring Boot 4.1.x and Java 25**. A service on Spring Boot 3.x, or on a JDK
older than 25, cannot use it:

- Stratum's classes are compiled for Java 25 (class file version 69). A JDK 21 build of a service
  that uses a Stratum type fails with `class file has wrong version 69.0, should be 65.0`, and a jar
  started on JDK 21 fails with `UnsupportedClassVersionError`.
- Stratum's code imports packages that exist only from Spring Boot 4.0, where Spring Boot split its
  auto-configuration into one module per technology, such as `org.springframework.boot.webmvc.error`,
  `org.springframework.boot.restclient` and `org.springframework.boot.cache.autoconfigure`.
- It uses Jackson 3 (`tools.jackson`), which Spring Boot 4 brings in place of Jackson 2.

Under `spring-boot-starter-parent` 3.4.2, for example, `mvn package` fails in the Spring Boot
plugin's `repackage` goal with `Unsupported class file major version 69`, and the service started
from its classes stops with `NoClassDefFoundError`. Spring Boot 4.0.x is not supported and not
tested. Moving a service from Spring Boot 3 to 4 is described in Spring's own
[Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide).

## The properties to set

| Your parent | Set | Why |
| --- | --- | --- |
| `spring-boot-starter-parent` 4.1.x | `java.version` = `25` | Spring Boot's parent sets `maven.compiler.release` from `java.version`, whose default there is 17 |
| A company parent that extends `spring-boot-starter-parent` 4.1.x | `java.version` = `25` | The same: the company parent passes Spring Boot's properties on |
| A company parent that does not extend it | `maven.compiler.release` = `25`, `maven.compiler.parameters` = `true`, `project.build.sourceEncoding` = `UTF-8`, and a Spring Boot version of your own, such as `spring-boot.version` = `4.1.2` | Without Spring Boot's parent, the compiler reads `maven.compiler.release` directly, and nothing keeps parameter names or manages Spring Boot's versions |

A property you set in your `pom.xml` wins over the same property in any parent. If your company
parent writes `<release>` inside its compiler plugin configuration, instead of using a property,
write `<release>25</release>` in the same plugin's configuration in your `pom.xml`.

Spring needs parameter names, for example for `@PathVariable String sku` without an explicit name.
Spring Boot's parent keeps them (`<parameters>true</parameters>`); without it, set
`maven.compiler.parameters`.

## Example 1: under `spring-boot-starter-parent`

The complete `pom.xml` of the same service as in [Stratum as your parent POM](with-stratum-parent.md),
with Spring Boot's parent. The `<build>` section adds back what `stratum-parent` would have given:
the JDK check, `git.properties` and `build-info.properties`.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <!-- Spring Boot's parent sets Spring Boot's versions and its plugin versions. -->
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.2</version>
    <relativePath/>
  </parent>

  <groupId>com.acme.orders</groupId>
  <artifactId>order-service</artifactId>
  <version>1.0.0-SNAPSHOT</version>

  <properties>
    <!-- Spring Boot's parent turns this into maven.compiler.release. Its own default is 17. -->
    <java.version>25</java.version>
  </properties>

  <!-- Stratum's versions: its modules and the extra libraries it uses. -->
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>io.github.exepex.stratum</groupId>
        <artifactId>stratum-bom</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <!-- No <version>: stratum-bom manages every Stratum module. -->
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

  <build>
    <plugins>
      <!-- Fails the build on a JDK older than 25 or a Maven older than 3.9. -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-enforcer-plugin</artifactId>
        <executions>
          <execution>
            <id>enforce-jdk-and-maven</id>
            <goals>
              <goal>enforce</goal>
            </goals>
            <configuration>
              <rules>
                <requireJavaVersion>
                  <version>[25,)</version>
                </requireJavaVersion>
                <requireMavenVersion>
                  <version>[3.9,)</version>
                </requireMavenVersion>
              </rules>
            </configuration>
          </execution>
        </executions>
      </plugin>
      <!-- Writes git.properties, the Git commit that /actuator/info shows. -->
      <plugin>
        <groupId>io.github.git-commit-id</groupId>
        <artifactId>git-commit-id-maven-plugin</artifactId>
        <configuration>
          <!-- A build without a .git folder, as in many Docker builds, still works. -->
          <failOnNoGitDirectory>false</failOnNoGitDirectory>
        </configuration>
      </plugin>
      <!-- Builds the executable jar, and writes build-info.properties for /actuator/info. -->
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <executions>
          <execution>
            <id>build-info</id>
            <goals>
              <goal>build-info</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

No plugin here needs a `<version>`: Spring Boot's parent manages all three. It also already binds the
Git plugin's `revision` goal and the Spring Boot plugin's `repackage` goal, so listing the plugins is
enough to turn them on.

## Example 2: under a company parent

The same service under `com.acme.platform:acme-parent`, a company parent that does **not** extend
`spring-boot-starter-parent` and sets Java 21. The service therefore imports Spring Boot's BOM
itself, sets Java 25 and writes the plugin versions.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <!-- Your company's parent. In this example it does not extend spring-boot-starter-parent. -->
  <parent>
    <groupId>com.acme.platform</groupId>
    <artifactId>acme-parent</artifactId>
    <version>7.3.0</version>
    <relativePath/>
  </parent>

  <groupId>com.acme.orders</groupId>
  <artifactId>order-service</artifactId>
  <version>1.0.0-SNAPSHOT</version>

  <properties>
    <!-- The Spring Boot line Stratum supports. -->
    <spring-boot.version>4.1.2</spring-boot.version>
    <!-- Java 25, whatever the company parent sets. -->
    <maven.compiler.release>25</maven.compiler.release>
    <!-- Keeps parameter names, which Spring reads for @PathVariable and constructor binding. -->
    <maven.compiler.parameters>true</maven.compiler.parameters>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>

  <dependencyManagement>
    <dependencies>
      <!-- Spring Boot's versions. Leave this out when your parent already manages them. -->
      <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>${spring-boot.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <!-- Stratum's versions: its modules and the extra libraries it uses. -->
      <dependency>
        <groupId>io.github.exepex.stratum</groupId>
        <artifactId>stratum-bom</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <!-- No <version>: stratum-bom manages every Stratum module. -->
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

  <build>
    <plugins>
      <!-- Fails the build on a JDK older than 25 or a Maven older than 3.9. -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-enforcer-plugin</artifactId>
        <version>3.6.3</version>
        <executions>
          <execution>
            <id>enforce-jdk-and-maven</id>
            <goals>
              <goal>enforce</goal>
            </goals>
            <configuration>
              <rules>
                <requireJavaVersion>
                  <version>[25,)</version>
                </requireJavaVersion>
                <requireMavenVersion>
                  <version>[3.9,)</version>
                </requireMavenVersion>
              </rules>
            </configuration>
          </execution>
        </executions>
      </plugin>
      <!-- Writes git.properties, the Git commit that /actuator/info shows. -->
      <plugin>
        <groupId>io.github.git-commit-id</groupId>
        <artifactId>git-commit-id-maven-plugin</artifactId>
        <version>9.2.0</version>
        <executions>
          <execution>
            <goals>
              <goal>revision</goal>
            </goals>
          </execution>
        </executions>
        <configuration>
          <generateGitPropertiesFile>true</generateGitPropertiesFile>
          <generateGitPropertiesFilename>${project.build.outputDirectory}/git.properties</generateGitPropertiesFilename>
          <!-- A build without a .git folder, as in many Docker builds, still works. -->
          <failOnNoGitDirectory>false</failOnNoGitDirectory>
        </configuration>
      </plugin>
      <!-- Builds the executable jar, and writes build-info.properties for /actuator/info. -->
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <version>${spring-boot.version}</version>
        <executions>
          <execution>
            <id>repackage</id>
            <goals>
              <goal>repackage</goal>
            </goals>
          </execution>
          <execution>
            <id>build-info</id>
            <goals>
              <goal>build-info</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

The Enforcer and Git plugin versions are the ones Spring Boot 4.1.2 manages. When your company
parent manages a version of one of these plugins, leave that `<version>` out.

**If your company parent extends `spring-boot-starter-parent` 4.1.x**, the service is simpler: leave
out the `spring-boot-dependencies` import and the four properties, set `java.version` to `25`, and
use the `<build>` section of [example 1](#example-1-under-spring-boot-starter-parent).

## What you lose without `stratum-parent`

| What `stratum-parent` gives | Under another parent | How to get it back |
| --- | --- | --- |
| Java 25 | Your parent's Java version | The properties in [The properties to set](#the-properties-to-set) |
| `build-info.properties`, shown by `/actuator/info` | Not written | The `build-info` execution of `spring-boot-maven-plugin`, as in the examples |
| `git.properties`, shown by `/actuator/info` | Not written | `git-commit-id-maven-plugin`, as in the examples |
| The Enforcer check for JDK 25 and Maven 3.9 | Not checked | The `enforce-jdk-and-maven` execution, as in the examples |
| The Enforcer's other rules: duplicate classes, version ranges, libraries with known remote code execution flaws | Not checked | Add the rules you want to the same execution; the list is in [Parent POM and BOM](../modules/parent-and-bom.md#the-parent-pom) |
| Stratum's quality checks: Spotless, Checkstyle, PMD, SpotBugs with FindSecBugs, JaCoCo and its `coverage-gate` and `security` profiles | Not run | Not available yet: see below |
| `*IntegrationTest` classes run by Failsafe in `mvn verify`, and kept out of `mvn test` | Spring Boot's parent does not run Failsafe unless you declare `maven-failsafe-plugin`; it then runs its default names, such as `*IT` | Declare the plugin, or follow your parent's naming for integration tests |

Stratum's Checkstyle and PMD rule files are in the artifact `stratum-build-rules`, which
`stratum-bom` does not manage. A service under another parent cannot use them without writing
Stratum's version a second time, so they are not available to it yet. Whether `stratum-bom` will
manage `stratum-build-rules` is decided with the 0.1.0 release
([#205](https://github.com/exepex/stratum/issues/205)). Until then, keep your parent's own checks.

**You do not need the flatten plugin.** Stratum uses `flatten-maven-plugin` only to write fixed
version numbers into its own published POMs. Importing `stratum-bom` does not bring it, and a service
does not add it.

## When the company parent pins an older Spring Boot

First check which Spring Boot version your service gets:

```bash
mvn dependency:tree -Dincludes=org.springframework.boot:spring-boot
```

If it is older than 4.1, what you can do depends on how the company parent sets it. Read its
`pom.xml`, or run `mvn help:effective-pom`:

| How the company parent sets Spring Boot's version | What the service does |
| --- | --- |
| It imports `spring-boot-dependencies` with a version property, such as `${spring-boot.version}` | Set that property to `4.1.2` in the service's `<properties>` |
| It imports `spring-boot-dependencies` with a fixed version | Import `spring-boot-dependencies` 4.1.2 in the service's own `<dependencyManagement>`, as in [example 2](#example-2-under-a-company-parent). A BOM the service imports is used before one its parent imports |
| It extends `spring-boot-starter-parent` 3.x, or lists Spring Boot's versions directly in its `<dependencyManagement>` | Nothing in the service can override it: versions inherited from a parent win over every BOM the service imports. Ask for a company parent release on Spring Boot 4.1, or use [example 1](#example-1-under-spring-boot-starter-parent) |

In the first two cases, also give `spring-boot-maven-plugin` the version `${spring-boot.version}`, as
example 2 does, because the company parent may manage an older plugin version. Then run the
`dependency:tree` command again to check that it shows 4.1.2.

Your service's own code may also need changes to move from Spring Boot 3 to 4: follow the
[Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide).
Which bean wins when a company framework and Stratum both provide one is described in
[Using Stratum with a company framework](../guides/company-framework.md).

## Next steps

Choose the starters, set the minimum configuration and check that the service starts:
[Installation, step 2](installation.md#step-2-add-the-starters-you-need).
