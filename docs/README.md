# Stratum documentation

Welcome. This is the home of the Stratum documentation. The [project README](../README.md) gives the
overview; these pages give the detail: how to set Stratum up, how to configure each part of it, and
how to use it day to day.

> [!NOTE]
> Stratum is in the design stage. Each module page's status line says whether it describes built or
> **planned** behaviour; planned behaviour is the design target and may change before the first
> release. The `groupId` is
> `com.exepex.stratum`; `0.1.0-SNAPSHOT` is a placeholder for the first released version.

## Where to start

| If you are... | Read, in this order |
| --- | --- |
| New to Spring Boot or to microservices | [Core concepts](getting-started/concepts.md) → [Quick start](getting-started/quick-start.md) → [Configuration basics](configuration/basics.md) |
| An experienced Spring developer | [Quick start](getting-started/quick-start.md) → [Overriding defaults](configuration/overriding-defaults.md) → the module pages you need |
| Moving an existing service to Stratum | [Installation](getting-started/installation.md) → [Migrating a service](guides/migrating-a-service.md) |
| Already using your company's framework | [Installation](getting-started/installation.md) → [Using Stratum with a company framework](guides/company-framework.md) |
| Looking up one property | [Configuration reference](configuration/reference.md) |

## Getting started

| Page | What it covers |
| --- | --- |
| [Core concepts](getting-started/concepts.md) | Starters, beans, auto-configuration, properties and profiles, explained from the beginning, plus a glossary. |
| [Installation](getting-started/installation.md) | Adding Stratum to a service: parent POM or BOM, choosing starters, checking that it works. |
| [Quick start](getting-started/quick-start.md) | Build a small but complete service step by step: REST API, logs, tracing, a downstream call, tests. |

## Configuration

| Page | What it covers |
| --- | --- |
| [Configuration basics](configuration/basics.md) | YAML and properties side by side, property names, types (durations, sizes, lists, maps), environment variables, validation, IDE completion. |
| [Profiles and environments](configuration/profiles-and-environments.md) | Per-environment settings, which source wins, keeping secrets out of files. |
| [Overriding defaults](configuration/overriding-defaults.md) | Changing values, customizers, replacing beans, switching modules off. |
| [Configuration reference](configuration/reference.md) | Every `stratum.*` prefix with a link to its full property table. |

## Modules

Each module page follows the same layout so you always know where to look:
**what it does → add it → what you get → properties → examples → customize → switch off → troubleshoot.**

| Module | Page |
| --- | --- |
| Parent POM and BOM | [parent-and-bom.md](modules/parent-and-bom.md) |
| Bundle starters (one dependency per kind of application) | [bundles.md](modules/bundles.md) |
| Web, for Spring MVC and WebFlux (errors, request logs, correlation IDs, timeouts, body limits) | [web.md](modules/web.md) |
| Web response timeouts, for Spring MVC and WebFlux | [web-timeouts.md](modules/web-timeouts.md) |
| Web request body size limit, for Spring MVC and WebFlux | [web-body-limit.md](modules/web-body-limit.md) |
| Web MVC (error responses for Spring MVC services) | [stratum-starter-webmvc/README.md](../starters/stratum-starter-webmvc/README.md) |
| WebFlux (error responses for reactive services) | [stratum-starter-webflux/README.md](../starters/stratum-starter-webflux/README.md) |
| Logging | [stratum-starter-logging/README.md](../starters/stratum-starter-logging/README.md) |
| Observability (metrics, tracing, health) | [stratum-starter-observability/README.md](../starters/stratum-starter-observability/README.md) |
| Security (resource server, JWT) | [security.md](modules/security.md) |
| OAuth2 client (service-to-service tokens) | [oauth2-client.md](modules/oauth2-client.md) |
| HTTP client | [http-client.md](modules/http-client.md) |
| Resilience | [resilience.md](modules/resilience.md) |
| Data (JPA, connection pool, migrations) | [data-jpa.md](modules/data-jpa.md) |
| Cache | [cache.md](modules/cache.md) |
| Kafka | [kafka.md](modules/kafka.md) |
| Cloud connectors | [cloud.md](modules/cloud.md) |
| Testing (`stratum-test`) | [test.md](modules/test.md) |

## Guides

| Page | What it covers |
| --- | --- |
| [Migrating a service](guides/migrating-a-service.md) | Moving an existing Spring Boot service onto Stratum, one concern at a time. |
| [Versioning and upgrades](guides/upgrading.md) | How Stratum versions work, what an upgrade involves, the compatibility table. |
| [Using Stratum with a company framework](guides/company-framework.md) | Keeping your company's parent and framework, which bean wins, turning off overlapping modules, forking Stratum under the company parent. |
| [Log fields across threads](guides/context-across-threads.md) | Which log fields follow a request to `@Async` methods, executors, event listeners, Reactor and virtual threads, and what your own threads need. |
| [Linking work that runs later](guides/linking-later-work.md) | When a job's trace and correlation ID should link to the API request that caused it, and how to link work a request saves for later. |
| [Architecture](guides/architecture.md) | How Stratum is built inside: repository layout, how defaults are applied, module rules. |
| [Error and warning codes](guides/error-codes.md) | What Stratum's codes look like, the internal and external error objects, using codes in your service, and every code with its fix. |
| [Troubleshooting](guides/troubleshooting.md) | Finding out what Stratum configured, and fixes for common problems. |
| [FAQ](guides/faq.md) | Short answers to common questions. |

## Architecture decisions

| Record | What it decides |
| --- | --- |
| [ADR 0001: Stratum next to a company framework](adr/0001-coexistence-with-company-frameworks.md) | The parent sets the Spring Boot version, a company's bean wins over Stratum's, and a fork under the company parent is supported but not recommended. |
| [ADR 0002: No startup check of the Spring Boot version](adr/0002-no-startup-version-check.md) | Replaces the startup version check of ADR 0001: the parent POM alone decides the Spring Boot version. |
| [ADR 0003: Bundle starters and module switches](adr/0003-bundle-starters-and-module-switches.md) | One bundle starter per kind of application instead of parents, and every module off until `stratum.<module>.enabled=true`. |
| [ADR 0004: Weekly dependency updates with Dependabot](adr/0004-dependency-updates.md) | Dependabot opens grouped upgrade pull requests every week, Spring upgrades together, each one runs the full build and the owner merges it. |
| [ADR 0005: Every version in the root POM](adr/0005-versions-in-the-root-pom.md) | Replaces two parts of ADR 0004: the root `pom.xml` inherits Spring Boot's parent and sets every version Stratum chooses, so no version is copied by hand. |
| [ADR 0006: Separate starters for Spring MVC and WebFlux](adr/0006-separate-mvc-and-webflux-starters.md) | `stratum-starter-webmvc` and `stratum-starter-webflux` share one library and one set of `stratum.web.*` keys, and a new reactive microservice bundle brings WebFlux. |

## Contributing

| Page | What it covers |
| --- | --- |
| [Contributing guide](contributing.md) | How to propose a change, when a new starter makes sense, the checklist every starter must meet. |

## Conventions used in these pages

- **Planned** marks behaviour that is designed but not built yet.
- Every configuration example is shown in **YAML** (`application.yml`) and **properties**
  (`application.properties`). Use whichever your service uses; they are equivalent.
- Every setting can also be set in Java code, through the module's customizer or builder, for services whose
  configuration files would grow too large. **Planned:** each module page will show this once the module's Java
  API is designed.
- Durations are written like `5s`, `200ms` or `10m`. Sizes are written like `10MB`.
- `<name>` in a property key means "a name you choose", for example the name of a downstream service.
