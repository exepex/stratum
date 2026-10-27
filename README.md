# Stratum

**A standardization SDK for building microservices on Spring Boot and Java 25.**

Stratum sits between Spring Boot and your business services. It packages the
components that every service needs (logging, observability, tracing, security,
REST and OAuth2 clients, resilience, testing support) as pre-configured,
opt-in modules. Teams add the modules they need, set a few properties, and get
the same production-ready behaviour in every service, without writing the
beans themselves.

> **Status: pre-alpha.** This README describes the intent and the planned
> design. No modules are implemented yet. Names marked as placeholders below
> are still to be decided.

---

## Why Stratum exists

Spring Boot is mature and makes services quick to build, but it is a
*general-purpose* framework. It supports many ways of doing the same thing,
which leaves each team to work out:

- **Which classes and modules to use.** Spring has a large surface area, and
  the right choice for logging, tracing, HTTP clients or security is not
  obvious.
- **How to configure them.** Bean definitions, auto-configuration ordering,
  property names and sensible defaults are easy to forget. Developers end up
  searching the web or asking an AI assistant, and still get it slightly wrong.
- **How to keep services consistent.** When every team wires its own logging
  format, trace propagation, error responses and client timeouts, services in
  the same architecture behave differently. That makes them harder to operate,
  debug and secure.
- **How to keep dependencies current.** Each service carries its own version
  upgrades and migrations, repeated many times across the organisation.

Stratum answers these questions once, centrally, so that business services
don't have to.

## What Stratum gives you

| Without Stratum | With Stratum |
| --- | --- |
| Every service picks and configures its own libraries | One curated, tested set of components |
| Custom beans copied between repositories | Auto-configured beans, overridable when needed |
| Log formats, trace headers and error bodies differ per service | Same log format, trace propagation and error envelope everywhere |
| Each team upgrades Spring Boot and libraries on its own | One Stratum version bump upgrades the whole stack |
| Test setup (containers, mocks, security) rebuilt in each repo | Shared test support with Testcontainers ready to use |

Teams focus on business logic. Stratum takes care of the components that
would otherwise be replicated across every service.

## Design principles

1. **Convention over configuration.** A module works with zero configuration
   and safe, production-ready defaults.
2. **Opt-in and modular.** Services include only the modules they need. A
   service that does not call other services does not pull in an HTTP client.
3. **Overridable, never locked in.** Every bean Stratum creates backs off
   (`@ConditionalOnMissingBean`) when a service defines its own, and every
   default is a property under the `stratum.*` namespace.
4. **Secure by default.** Secrets, tokens and personal data are never logged;
   timeouts are always set on remote calls.
5. **Standard Spring underneath.** Stratum builds on Spring Boot's own
   auto-configuration and starters. Anything you know about Spring Boot still
   applies, and nothing is hidden behind a proprietary API.
6. **No business logic.** Stratum holds cross-cutting concerns only. Domain
   code belongs in the services.

## Technology baseline

| Component | Version |
| --- | --- |
| Java | 25 |
| Spring Boot | 4.1.2 (latest GA as of October 2026; 4.2.0 is at RC1) |
| Spring Framework | 7.x (managed by Spring Boot) |
| Build | Maven, multi-module |

Spring Boot manages most third-party versions. Stratum's BOM pins everything
else, so services never declare library versions themselves.

## Planned modules

Module names follow the `stratum-*` pattern. The list below is the initial
plan and will grow as services need more.

### Build and platform

| Module | Purpose |
| --- | --- |
| `stratum-parent` | Parent POM for services: Java 25, plugin management, compiler settings and quality gates (formatting, Checkstyle, PMD, SpotBugs, unit and integration test separation, coverage). |
| `stratum-bom` | Bill of materials that pins every Stratum module and third-party version. For services that cannot inherit the parent POM. |
| `stratum-build-rules` | Shared Checkstyle, PMD and SpotBugs rule sets, packaged as a jar so every build uses the same rules. |

### Starters

Each starter is a single dependency that brings its libraries and their
auto-configuration.

| Starter | What it sets up |
| --- | --- |
| `stratum-starter-web` | REST defaults: a standard error response envelope, request validation, correlation ID handling and consistent JSON settings. |
| `stratum-starter-logging` | Structured JSON logging, MDC enrichment (trace ID, span ID, correlation ID) and redaction of secrets and personal data. |
| `stratum-starter-observability` | Micrometer metrics, OpenTelemetry tracing and export, health and readiness probes, common metric tags. |
| `stratum-starter-security` | OAuth2 resource server with JWT validation, standard authority mapping and secure endpoint defaults. |
| `stratum-starter-oauth2-client` | OAuth2 client for service-to-service calls (client credentials), with token caching and refresh. |
| `stratum-starter-http-client` | Pre-configured `RestClient` and HTTP interface clients with timeouts, trace propagation, logging and optional token relay. |
| `stratum-starter-resilience` | Retries, circuit breakers, rate limiting and bulkheads with sensible defaults and metrics. |
| `stratum-starter-data-jpa` | JPA and connection pool defaults, database migrations, auditing columns. |
| `stratum-starter-cache` | Local and distributed caching with consistent key and TTL conventions. |
| `stratum-starter-kafka` | Kafka producers and consumers with standard serialization, error handling, dead-letter topics and trace propagation. |
| `stratum-starter-cloud-*` | Connectors for cloud services (for example storage, queues and secrets) per provider. |

### Testing

| Module | Purpose |
| --- | --- |
| `stratum-test` | JUnit 5, AssertJ and Mockito, plus Testcontainers definitions (PostgreSQL, Kafka, Redis and others) shared across services, HTTP stubbing for downstream calls, and helpers for testing secured endpoints with mock JWTs. |

## Using Stratum in a service

> The examples use `com.example.stratum` as a **placeholder** groupId and
> `0.1.0-SNAPSHOT` as the version. Both will change once they are decided.

### 1. Inherit the parent POM

```xml
<parent>
  <groupId>com.example.stratum</groupId>
  <artifactId>stratum-parent</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</parent>
```

If your service already has a parent, import the BOM instead:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.example.stratum</groupId>
      <artifactId>stratum-bom</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

### 2. Add the starters you need

No versions are needed; the parent or BOM manages them.

```xml
<dependencies>
  <dependency>
    <groupId>com.example.stratum</groupId>
    <artifactId>stratum-starter-web</artifactId>
  </dependency>
  <dependency>
    <groupId>com.example.stratum</groupId>
    <artifactId>stratum-starter-observability</artifactId>
  </dependency>
  <dependency>
    <groupId>com.example.stratum</groupId>
    <artifactId>stratum-starter-http-client</artifactId>
  </dependency>

  <dependency>
    <groupId>com.example.stratum</groupId>
    <artifactId>stratum-test</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>
```

### 3. Configure only what is specific to your service

Defaults cover the common case. You set what only your service knows, such as
the URL of a downstream service:

```yaml
spring:
  application:
    name: order-service

stratum:
  http-client:
    clients:
      inventory:
        base-url: https://inventory.internal
```

### 4. Write business code

```java
@RestController
@RequiredArgsConstructor
class OrderController {

  private final InventoryClient inventory; // configured by Stratum

  @GetMapping("/orders/{id}")
  OrderView get(@PathVariable String id) {
    // Logging, tracing, metrics, timeouts and error mapping are already in place.
    return OrderView.from(id, inventory.stockFor(id));
  }
}
```

### Overriding a default

Define your own bean and Stratum's version steps aside:

```java
@Bean
RestClientCustomizer myCustomizer() {
  return builder -> builder.defaultHeader("X-Client", "order-service");
}
```

The `stratum.*` property reference for each module will live in that module's
own README.

## Testing with Stratum

`stratum-test` aims to make unit, mock and integration tests quick to write:

- **Unit tests** (`*Test.java`, run by Surefire) with JUnit 5, AssertJ and
  Mockito already on the classpath.
- **Integration tests** (`*IT.java`, run by Failsafe) that start real
  dependencies with Testcontainers, using images and versions pinned centrally.
- **Downstream stubs** for HTTP dependencies, so services can test client
  behaviour without the real service.
- **Security helpers** to call secured endpoints with test JWTs.

## Upgrades and versioning

- Stratum follows semantic versioning.
- Each Stratum release is tested against one Spring Boot version. Upgrading
  Stratum upgrades Spring Boot and every managed library together.
- Breaking changes and migration steps are listed in the release notes, so
  each service does not have to research them on its own.

## Repository layout (planned)

```
stratum/
├── pom.xml                  # reactor: builds every module
├── stratum-parent/
├── stratum-bom/
├── stratum-build-rules/
├── starters/
│   ├── stratum-starter-web/
│   ├── stratum-starter-logging/
│   └── ...
├── stratum-test/
└── samples/                 # example services that use Stratum
```

## Building

Requires JDK 25 and Maven 3.9+.

```bash
mvn verify
```

## Contributing

A new starter is welcome when a concern is needed by more than one service.
Each starter should:

- work with no configuration and secure defaults;
- expose its settings as `@ConfigurationProperties` under `stratum.<module>`;
- let services override every bean;
- include tests for its auto-configuration and a short README.

## License

Apache License 2.0. See [LICENSE](LICENSE).
