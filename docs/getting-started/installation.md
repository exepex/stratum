# Installation

**Status: Planned.** How to add Stratum to a service, new or existing.

[← Documentation home](../README.md)

## Prerequisites

| Tool | Version | Check with |
| --- | --- | --- |
| JDK | 25 | `java -version` |
| Maven | 3.9 or later | `mvn -version` |
| Docker | Any recent version (only for integration tests) | `docker info` |

> [!NOTE]
> `0.1.0-SNAPSHOT` below is a placeholder for the first released version.

## Step 1: choose how your service gets Stratum's versions

There are two options. Both give you the same tested versions; they differ in what else you get.

| | Option A: parent POM | Option B: BOM import |
| --- | --- | --- |
| Use when | You start a new service, or your `pom.xml` has no parent | Your `pom.xml` already has a parent you must keep, such as your company's parent |
| Dependency versions | Managed | Managed |
| Java 25 compiler settings | Set for you | You set them |
| Plugin versions and quality gates | Set for you | You set them |
| Spring Boot version | Set by Stratum | Set by your parent |
| Recommended | **Yes** | When A is not possible |

### Option A: inherit `stratum-parent`

```xml
<project>
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.exepex.stratum</groupId>
    <artifactId>stratum-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <relativePath/> <!-- look the parent up in the repository, not on disk -->
  </parent>

  <groupId>com.acme.orders</groupId>
  <artifactId>order-service</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</project>
```

`stratum-parent` itself inherits Spring Boot's parent, so everything a Spring Boot parent gives you
(the `spring-boot-maven-plugin`, resource filtering, and so on) still works.

### Option B: import `stratum-bom`

```xml
<dependencyManagement>
  <dependencies>
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

The BOM does not set Spring Boot's versions; your parent does. If your parent extends
`spring-boot-starter-parent`, as most company parents do, there is nothing more to add. If it does
not, also import `spring-boot-dependencies` at the version in the
[compatibility table](../guides/upgrading.md#compatibility).

If your parent belongs to a company framework with its own starters, read
[Using Stratum with a company framework](../guides/company-framework.md). It explains which bean wins
when both frameworks provide one, how to turn off a Stratum module that overlaps, and how a team can
fork Stratum to put it under the company parent.

With the BOM you also set the Java version yourself:

```xml
<properties>
  <maven.compiler.release>25</maven.compiler.release>
</properties>
```

What each option contains in detail: [Parent POM and BOM](../modules/parent-and-bom.md).

## Step 2: add the starters you need

Pick the starters that match what your service does. You can add more later.

| Your service... | Add |
| --- | --- |
| Exposes a REST API | `stratum-starter-web`, `stratum-starter-observability`, `stratum-starter-logging` |
| Requires a valid token on its API | `stratum-starter-security` |
| Calls other services over HTTP | `stratum-starter-http-client` (and `stratum-starter-resilience` for retries and circuit breakers) |
| Calls secured services | `stratum-starter-oauth2-client` |
| Uses a relational database | `stratum-starter-data-jpa` |
| Caches results | `stratum-starter-cache` |
| Produces or consumes Kafka messages | `stratum-starter-kafka` |
| Uses cloud storage, queues or secrets | `stratum-starter-cloud-<provider>` |
| Has tests (every service) | `stratum-test` with `<scope>test</scope>` |

A typical REST service that calls one other service:

```xml
<dependencies>
  <dependency>
    <groupId>com.exepex.stratum</groupId>
    <artifactId>stratum-starter-web</artifactId>
  </dependency>
  <dependency>
    <groupId>com.exepex.stratum</groupId>
    <artifactId>stratum-starter-logging</artifactId>
  </dependency>
  <dependency>
    <groupId>com.exepex.stratum</groupId>
    <artifactId>stratum-starter-observability</artifactId>
  </dependency>
  <dependency>
    <groupId>com.exepex.stratum</groupId>
    <artifactId>stratum-starter-security</artifactId>
  </dependency>
  <dependency>
    <groupId>com.exepex.stratum</groupId>
    <artifactId>stratum-starter-http-client</artifactId>
  </dependency>

  <dependency>
    <groupId>com.exepex.stratum</groupId>
    <artifactId>stratum-test</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>
```

Rules that keep you out of trouble:

- **Never write a `<version>`** for a Stratum starter or for a library Stratum manages. The parent or
  BOM decides it. A hard-coded version is the most common cause of startup errors after an upgrade.
- **Do not add the Spring Boot starter as well** when a Stratum starter covers it. For example,
  `stratum-starter-web` already brings Spring MVC.
- **Need a library Stratum does not manage?** Add it with a version as usual. If several services
  need it, propose it for the BOM (see [Contributing](../contributing.md)).

## Step 3: set the minimum configuration

Every service must have a name. Stratum uses it in logs, metrics, traces and cache keys.

```yaml
# src/main/resources/application.yml
spring:
  application:
    name: order-service
```

```properties
# src/main/resources/application.properties
spring.application.name=order-service
```

Some starters need one more setting before they can work. Stratum stops at startup with a clear
message if it is missing:

| Starter | Required setting |
| --- | --- |
| `stratum-starter-security` | `stratum.security.resource-server.issuer-uri` |
| `stratum-starter-http-client` | `stratum.http-client.clients.<name>.base-url` for each client |
| `stratum-starter-data-jpa` | `spring.datasource.url`, `username`, `password` |
| `stratum-starter-kafka` | `spring.kafka.bootstrap-servers` |

## Step 4: check that it works

```bash
mvn spring-boot:run
```

Then, in another terminal:

```bash
curl -s localhost:8080/actuator/health
# {"status":"UP"}
```

The startup log tells you which Stratum modules are active:

```text
Stratum 0.1.0 active modules: web, logging, observability, security, http-client
```

If something is not what you expect, see [Troubleshooting](../guides/troubleshooting.md).

## Next steps

- Build a full example service: [Quick start](quick-start.md)
- Learn how configuration works: [Configuration basics](../configuration/basics.md)
- Moving an existing service? [Migrating a service](../guides/migrating-a-service.md)
