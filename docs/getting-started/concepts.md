# Core concepts

This page explains the ideas you need to use Stratum, starting from zero. If you already know Spring
Boot well, skim the headings and jump to [How Stratum fits in](#how-stratum-fits-in).

[← Documentation home](../README.md)

## Contents

- [Spring Boot in one minute](#spring-boot-in-one-minute)
- [Beans and dependency injection](#beans-and-dependency-injection)
- [Starters](#starters)
- [Auto-configuration](#auto-configuration)
- [Properties and configuration files](#properties-and-configuration-files)
- [Profiles](#profiles)
- [Actuator](#actuator)
- [How Stratum fits in](#how-stratum-fits-in)
- [Cross-cutting concerns](#cross-cutting-concerns)
- [Glossary](#glossary)

## Spring Boot in one minute

**Spring Framework** is a Java framework for building applications. **Spring Boot** is a layer on top
of it that makes a Spring application quick to start: it picks sensible defaults, embeds a web server
(Tomcat by default) and runs the application as a single `java -jar` file.

A minimal Spring Boot application is one class:

```java
@SpringBootApplication
public class OrderServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(OrderServiceApplication.class, args);
  }
}
```

`@SpringBootApplication` tells Spring Boot to scan this package for your classes and to apply
auto-configuration (explained below).

## Beans and dependency injection

A **bean** is an object that Spring creates and manages for you. Instead of writing
`new InventoryClient(...)` yourself, you ask Spring for one, and Spring **injects** it:

```java
@Service
class OrderService {

  private final InventoryClient inventory;

  OrderService(InventoryClient inventory) { // Spring passes the bean in
    this.inventory = inventory;
  }
}
```

This is **dependency injection**. It means the code that *uses* an object does not decide how the
object is *built*. That is what lets Stratum build objects (an HTTP client with timeouts, a security
filter) the standard way while your code just uses them.

Ways a bean comes into existence:

| How | Example |
| --- | --- |
| Annotating your class | `@Service`, `@Component`, `@RestController`, `@Repository` |
| A `@Bean` method in a `@Configuration` class | `@Bean RestClient restClient() { ... }` |
| Auto-configuration from a library | Stratum's starters, Spring Boot's own starters |

## Starters

A **starter** is a Maven dependency that exists to pull in a group of libraries and their
configuration for one purpose. Spring Boot ships starters such as `spring-boot-starter-webmvc`.
Stratum ships its own, such as `stratum-starter-logging`.

Adding a starter is usually the only step needed to use a feature:

```xml
<dependency>
  <groupId>com.exepex.stratum</groupId>
  <artifactId>stratum-starter-logging</artifactId>
</dependency>
```

## Auto-configuration

**Auto-configuration** is how Spring Boot creates beans for you. When the application starts, Spring
Boot looks at:

- which libraries are on the classpath ("is Kafka here?"),
- which beans already exist ("did the service define its own `ObjectMapper`?"),
- which properties are set ("is `stratum.cache.enabled` false?").

Based on the answers it decides which beans to create. These checks are called **conditions**. The
most important one for you is `@ConditionalOnMissingBean`: it means "create this bean only if the
application has not defined one already". Every bean Stratum creates has this condition, which is
why you can always replace it with your own.

You can see every decision Spring Boot made by starting the application with `--debug`. See
[Troubleshooting](../guides/troubleshooting.md#see-what-stratum-configured).

## Properties and configuration files

**Properties** are named settings, such as `server.port=8080`. Spring Boot reads them from several
places, most commonly a file in `src/main/resources`:

- `application.yml` (YAML format, nested by indentation), or
- `application.properties` (one `key=value` per line).

They hold the same information. Stratum supports both, and every example in these docs shows both:

```yaml
# application.yml
stratum:
  logging:
    format: ecs
```

```properties
# application.properties
stratum.logging.format=ecs
```

Spring Boot also reads environment variables, command-line arguments and more, in a fixed order of
priority. [Configuration basics](../configuration/basics.md) explains all of it.

## Profiles

A **profile** is a named set of settings that you switch on for one environment, for example
`local`, `test` or `prod`. Settings for a profile go in `application-<profile>.yml` (or
`.properties`) and apply only when that profile is active:

```bash
java -jar order-service.jar --spring.profiles.active=prod
```

See [Profiles and environments](../configuration/profiles-and-environments.md).

## Actuator

**Spring Boot Actuator** adds operational endpoints to your service, such as `/actuator/health`
(is the service up?) and `/actuator/prometheus` (metrics for Prometheus). Stratum's observability
starter switches Actuator on with a safe, standard set of endpoints.

## How Stratum fits in

Spring Boot gives you the *mechanism* (starters, auto-configuration, properties). It deliberately does
not decide *how* your organisation logs, traces, secures or calls services, because it has to suit
everyone. Stratum makes those decisions once, using the same mechanism:

| Spring Boot alone | Stratum on top |
| --- | --- |
| Offers several log formats; you pick and configure one | Picks the standard format and adds trace and correlation IDs |
| Offers `RestClient`; you set timeouts, tracing and tokens yourself | Gives you a client with all of that already set |
| Offers a resource server; you write the `SecurityFilterChain` | Builds the chain from a few `stratum.security.*` properties |
| Leaves versions of extra libraries to you | Pins and tests their versions in one BOM, on top of Spring Boot's |

Your service still is a normal Spring Boot application. You can read any Spring Boot guide and it
still applies.

## Cross-cutting concerns

A **cross-cutting concern** is a need that every service has, regardless of its business: logging,
tracing, metrics, security, error handling, calling other services, retries, caching, testing. These
are exactly what Stratum provides. Business logic (what an order is, how a payment is approved) is
never part of Stratum.

## Glossary

| Term | Meaning |
| --- | --- |
| **Auto-configuration** | Spring Boot code that creates beans automatically, based on conditions. |
| **Bean** | An object created and managed by Spring. |
| **BOM** (bill of materials) | A special POM that only lists dependency versions, so you can use the libraries without writing versions. |
| **Circuit breaker** | A guard that stops calling a failing service for a while, so failures do not pile up. |
| **Correlation ID** | An ID attached to a request and copied to every log line and downstream call, so you can find everything about one request. |
| **Customizer** | A small bean that adjusts a bean Stratum created, without replacing it. |
| **Dead-letter topic** | A Kafka topic where messages go after processing failed too many times, so they are not lost. |
| **JWT** (JSON Web Token) | A signed token that proves who the caller is and what they may do. |
| **MDC** (Mapped Diagnostic Context) | Per-request values (such as the trace ID) that the logger adds to every log line. |
| **OAuth2 resource server** | A service that accepts requests carrying OAuth2 access tokens and checks them. |
| **OAuth2 client** | A service that obtains access tokens so it can call other secured services. |
| **OpenTelemetry (OTel)** | The open standard for traces, metrics and logs. Stratum exports traces with it. |
| **Parent POM** | A POM your project inherits from, which brings plugin and dependency settings with it. |
| **Problem Details** | The standard JSON error format defined by RFC 9457, used for all Stratum error responses. |
| **Profile** | A named group of settings for one environment. |
| **Span / trace** | A trace is the full path of one request through all services; a span is one step in it. |
| **Starter** | A dependency that brings a feature's libraries and configuration in one step. |
| **Testcontainers** | A library that starts real databases or brokers in Docker for tests. |

Next: [Installation](installation.md) or straight to the [Quick start](quick-start.md).
