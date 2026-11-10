# Installation

**Status: Built, not released.** How to add Stratum to a service, new or existing. The parent POM, the
BOM and the starters used below exist, but no version is published yet.

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

There are two ways. Both give you the same tested versions; they differ in what else you get.

| | Stratum as the parent | Stratum as a dependency |
| --- | --- | --- |
| Use when | You start a new service, or your `pom.xml` has no parent | Your `pom.xml` must keep its parent: `spring-boot-starter-parent` or your company's parent |
| What you add | `stratum-parent` as the `<parent>` | `stratum-bom` imported in `<dependencyManagement>` |
| Dependency versions | Managed | Managed |
| Java 25 | Set for you | You set one property |
| Build info, Git info and the JDK 25 check | Set for you | You add three plugins |
| Stratum's quality checks | Set for you | Not available yet |
| Spring Boot version | Set by Stratum | Set by your parent, and it must be 4.1.x |
| Recommended | **Yes** | When the parent cannot change |
| Guide, with a complete `pom.xml` | [Stratum as your parent POM](with-stratum-parent.md) | [Stratum as a dependency, under another parent](with-stratum-bom.md) |

If your parent belongs to a company framework with its own starters, also read
[Using Stratum with a company framework](../guides/company-framework.md). It explains which bean wins
when both frameworks provide one, how to turn off a Stratum module that overlaps, and how a team can
fork Stratum to put it under the company parent.

What the parent and the BOM contain in detail: [Parent POM and BOM](../modules/parent-and-bom.md).

## Step 2: add the starters you need

The quickest way is one [bundle starter](../modules/bundles.md) for your kind of application:
`stratum-starter-microservice`, `stratum-starter-reactive-microservice`, `stratum-starter-web-ui`,
`stratum-starter-batch` or `stratum-starter-cli`. Or pick single starters that match what your
service does. You can add more later.

| Your service... | Add |
| --- | --- |
| Exposes a REST API | `stratum-starter-webmvc` (or `stratum-starter-webflux` if it is reactive), `stratum-starter-observability`, `stratum-starter-logging` |
| Requires a valid token on its API | `stratum-starter-security` (**planned**: it brings no dependency yet) |
| Calls other services over HTTP | `stratum-starter-http-client`, which gives each client its own timeouts, connection pool, retries and circuit breaker |
| Calls secured services | `stratum-starter-oauth2-client` |
| Uses a relational database | `stratum-starter-data-jpa` (partly built) |
| Caches results | `stratum-starter-cache` |
| Produces or consumes Kafka messages | `stratum-starter-kafka` (**planned**) |
| Uses cloud storage, queues or secrets | `stratum-starter-cloud-<provider>` (**planned**) |
| Has tests (every service) | `stratum-test` with `<scope>test</scope>` |

A typical REST service that calls other services and caches results, with built starters only:

```xml
<dependencies>
  <dependency>
    <groupId>com.exepex.stratum</groupId>
    <artifactId>stratum-starter-webmvc</artifactId>
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
    <artifactId>stratum-starter-http-client</artifactId>
  </dependency>
  <dependency>
    <groupId>com.exepex.stratum</groupId>
    <artifactId>stratum-starter-cache</artifactId>
  </dependency>

  <dependency>
    <groupId>com.exepex.stratum</groupId>
    <artifactId>stratum-test</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>
```

Every starter's module is on as soon as the starter is on the classpath; you write no line to
switch it on. To keep one off, set `stratum.<module>.enabled=false`. A module turned off is named
in one INFO line at startup, with the property that turned it off.

Rules that keep you out of trouble:

- **Never write a `<version>`** for a Stratum starter or for a library Stratum manages. The parent or
  BOM decides it. A hard-coded version is the most common cause of startup errors after an upgrade.
- **Do not add the Spring Boot starter as well** when a Stratum starter covers it. For example,
  `stratum-starter-webmvc` already brings Spring MVC.
- **Need a library Stratum does not manage?** Add it with a version as usual. If several services
  need it, propose it for the BOM (see [Contributing](../contributing.md)).

## Step 3: set the minimum configuration

Every service must have a name. Stratum uses it in logs, metrics, traces and cache keys. Each module
you added is already on, so you write no line to switch one on:

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
message if it is missing, and the message also names `stratum.<module>.enabled=false`, which turns
that module off instead:

| Starter | Required setting |
| --- | --- |
| `stratum-starter-logging` | `spring.application.name` |
| `stratum-starter-observability` | `management.otlp.metrics.export.url` and `management.opentelemetry.tracing.export.otlp.endpoint`, in every profile |
| `stratum-starter-security` (**planned**) | `stratum.security.resource-server.issuer-uri` |
| `stratum-starter-http-client` | `stratum.http-client.clients.<name>.url` for each client |
| `stratum-starter-data-jpa` | `spring.datasource.url`, `username`, `password` |
| `stratum-starter-cache` | `spring.application.name`, with Spring Data Redis, unless you set `spring.cache.redis.key-prefix` |
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

Every module whose starter you added is on. If you turned one off, the startup log names it in one
INFO line, with the property that turned it off:

```text
Stratum modules present but off: cache (stratum.cache.enabled=false)
```

If something is not what you expect, see [Troubleshooting](../guides/troubleshooting.md).

## Next steps

- Build a full example service: [Quick start](quick-start.md)
- Learn how configuration works: [Configuration basics](../configuration/basics.md)
- Moving an existing service? [Migrating a service](../guides/migrating-a-service.md)
