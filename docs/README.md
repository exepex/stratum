# Stratum documentation

Welcome. This is the home of the Stratum documentation. The [project README](../README.md) gives the
overview; these pages give the detail: how to set Stratum up, how to configure each part of it, and
how to use it day to day. The same pages are copied into the
[Stratum wiki](https://github.com/exepex/stratum/wiki), which also holds the audit reports. Edit
them in the repository's `docs/` folder, not in the wiki
([how the copy works](contributing.md#the-wiki)).

> [!NOTE]
> Stratum is under construction: some modules are built, some partly built and some only planned. Each
> module page's status line says whether it describes built or **planned** behaviour; planned behaviour is
> the design target and may change in a later release. The `groupId` is `io.github.exepex.stratum`
> ([ADR 0018](adr/0018-group-id-io-github-exepex.md)).
>
> Examples use the latest released version, `0.2.0`. These pages describe Stratum's `main` branch, which
> may hold changes made after 0.2.0: the [0.2.0 release notes](releases/0.2.0.md) say what 0.2.0
> contains, and [Using a snapshot](getting-started/installation.md#using-a-snapshot) shows how to try
> the latest published snapshot of `main`, `0.3.0-SNAPSHOT`.

## Where to start

| If you are... | Read, in this order |
| --- | --- |
| New to Spring Boot or to microservices | [Core concepts](getting-started/concepts.md) → [Quick start](getting-started/quick-start.md) → [Configuration basics](configuration/basics.md) |
| An experienced Spring developer | [Quick start](getting-started/quick-start.md) → [Overriding defaults](configuration/overriding-defaults.md) → the module pages you need |
| Moving an existing service to Stratum | [Installation](getting-started/installation.md) → [Stratum as your parent POM](getting-started/with-stratum-parent.md) or [Stratum as a dependency](getting-started/with-stratum-bom.md) → [Migrating a service](guides/migrating-a-service.md) |
| Already using your company's framework | [Installation](getting-started/installation.md) → [Stratum as a dependency](getting-started/with-stratum-bom.md) → [Using Stratum with a company framework](guides/company-framework.md) |
| Looking up one property | [Configuration reference](configuration/reference.md) |

## Getting started

| Page | What it covers |
| --- | --- |
| [Core concepts](getting-started/concepts.md) | Starters, beans, auto-configuration, properties and profiles, explained from the beginning, plus a glossary. |
| [Installation](getting-started/installation.md) | Adding Stratum to a service: parent POM or BOM, choosing starters, checking that it works. |
| [Stratum as your parent POM](getting-started/with-stratum-parent.md) | The complete `pom.xml` of a microservice with `stratum-microservice-parent` as the parent, `stratum-parent` for other kinds of application, and what each parent sets for you. |
| [Stratum as a dependency, under another parent](getting-started/with-stratum-bom.md) | Complete `pom.xml` examples under `spring-boot-starter-parent` and under a company parent, the properties to set, what you lose without `stratum-parent`, and an older Spring Boot in the parent. |
| [Quick start](getting-started/quick-start.md) | Build a small but complete service step by step: REST API, logs, tracing, a downstream call, tests. |

## Configuration

| Page | What it covers |
| --- | --- |
| [Configuration basics](configuration/basics.md) | YAML and properties side by side, property names, types (durations, sizes, lists, maps), environment variables, validation, IDE completion. |
| [Profiles and environments](configuration/profiles-and-environments.md) | Per-environment settings, which source wins, keeping secrets out of files. |
| [Overriding defaults](configuration/overriding-defaults.md) | Changing values, customizers, replacing beans, switching modules off. |
| [Shared settings](configuration/shared-settings.md) | The service's name and environment, the instance ID, the Spring Boot keys Stratum gives a default, a complete example. |
| [Configuration reference](configuration/reference.md) | Every `stratum.*` key with its type, default and description, generated from the code, by module and group, with a link to each module's page. |

## Modules

Each module page follows the same layout so you always know where to look:
**what it does → add it → what you get → properties → examples → customize → switch off → troubleshoot.**

| Module | Page |
| --- | --- |
| Parent POMs and BOM | [parent-and-bom.md](modules/parent-and-bom.md) |
| Bundle starters (one dependency per kind of application) | [bundles.md](modules/bundles.md) |
| Web, for Spring MVC and WebFlux (errors, request logs, correlation IDs, timeouts, body limits) | [web.md](modules/web.md) |
| Web backward-compatible mode, which keeps existing consumers' error bodies | [web-compatible-mode.md](modules/web-compatible-mode.md) |
| Web response timeouts, for Spring MVC and WebFlux | [web-timeouts.md](modules/web-timeouts.md) |
| Web request body size limit, for Spring MVC and WebFlux | [web-body-limit.md](modules/web-body-limit.md) |
| Web answers to database failures: `503` for a busy database, `409` for a data conflict | [web-database-errors.md](modules/web-database-errors.md) |
| Web request log line: headers, token claims and switching it off | [web-request-log.md](modules/web-request-log.md) |
| Web MVC (error responses for Spring MVC services) | [stratum-starter-webmvc/README.md](../starters/stratum-starter-webmvc/README.md) |
| WebFlux (error responses for reactive services) | [stratum-starter-webflux/README.md](../starters/stratum-starter-webflux/README.md) |
| Logging | [stratum-starter-logging/README.md](../starters/stratum-starter-logging/README.md) |
| Logging workflows (named business work in logs, metrics and traces) | [logging-workflows.md](modules/logging-workflows.md) |
| Logging rate limits and sampling (each logger's lines per second, kept DEBUG and INFO lines) | [logging-volume.md](modules/logging-volume.md) |
| Observability (metrics, tracing, health) | [stratum-starter-observability/README.md](../starters/stratum-starter-observability/README.md) |
| Observability meter caps (total meters, HTTP client hosts, the `uri` tag) | [observability-metrics.md](modules/observability-metrics.md) |
| Observability of Kafka, RabbitMQ and JMS messages | [observability-messages.md](modules/observability-messages.md) |
| Observability traces (correlation ID, baggage, URLs on spans, errors on spans, span size, dropped spans, health probes, sampling and tail sampling) | [observability-traces.md](modules/observability-traces.md) |
| Observing a service's stack: JWT access tokens, PostgreSQL, Redis, JMS, and counting active users | [observability-service-stack.md](modules/observability-service-stack.md) |
| Observability known gaps, and which backends (OTLP HTTP and gRPC, Prometheus, Zipkin) are tested | [observability-known-gaps.md](modules/observability-known-gaps.md) |
| Security (resource server, JWT) | [security.md](modules/security.md) |
| OAuth2 client (the service's own tokens for the HTTP client) | [oauth2-client.md](modules/oauth2-client.md) |
| HTTP client | [http-client.md](modules/http-client.md), [auth and mTLS](modules/http-client-auth.md), [errors](modules/http-client-errors.md), [mocked clients](modules/http-client-mocks.md), [retries and the circuit breaker](modules/http-client-resilience.md), [clients Stratum did not build](modules/http-client-own-clients.md), [headers and interceptors](modules/http-client-headers.md), [call log and meters](modules/http-client-observability.md), [streaming](modules/http-client-streaming.md) |
| Resilience | [resilience.md](modules/resilience.md) |
| Data (JPA, JDBC, connection pool, transactions) | [data-jpa.md](modules/data-jpa.md), [jdbc.md](modules/jdbc.md), [data.md](modules/data.md) (the JDBC boundary every data starter shares), [statement time limits](modules/data-statement-limits.md), [TLS check](modules/data-tls-check.md), [PostgreSQL login limit](modules/data-login-limit.md), [query spans](modules/data-query-spans.md), [named pools](modules/data-named-pools.md) and [repositories on a pool](modules/data-named-pools.md#repositories-on-a-pool) |
| Cache | [cache.md](modules/cache.md) |
| Cache when Redis is down | [cache-redis-down.md](modules/cache-redis-down.md) |
| Cache stored values | [cache-values.md](modules/cache-values.md) |
| Cache time to live and timeout per cache | [cache-per-cache.md](modules/cache-per-cache.md) |
| Cache name on Redis spans | [cache-traces.md](modules/cache-traces.md) |
| Cache null results | [cache-null-results.md](modules/cache-null-results.md) |
| Cache in Caffeine | [cache-caffeine.md](modules/cache-caffeine.md) |
| Cache reactive and async methods | [cache-reactive.md](modules/cache-reactive.md) |
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
| [What your caller learns](guides/what-your-caller-learns.md) | The status and code a caller gets for each failure Stratum answers, whether it sends the request again, whether the work may be done, what jobs and listeners see, and timeouts in a chain of services. |
| [Error and warning codes](guides/error-codes.md) | What Stratum's codes look like, the internal and external error objects, using codes in your service, and every code with its fix. |
| [Troubleshooting](guides/troubleshooting.md) | Finding out what Stratum configured, and fixes for common problems. |
| [FAQ](guides/faq.md) | Short answers to common questions. |
| [Release notes for 0.3.0](releases/0.3.0.md) | Not released yet: the changes made on `main` since 0.2.0, with the steps to migrate. |
| [Release notes for 0.2.0](releases/0.2.0.md) | What 0.2.0 changed since 0.1.0, with the steps to migrate. |
| [Release notes for 0.1.0](releases/0.1.0.md) | What Stratum's first release contains, the modules left out of it and its known gaps. |

## Architecture decisions

| Record | What it decides |
| --- | --- |
| [ADR 0009: Mark internal types with `@StratumInternal`, and keep packages free of cycles](adr/0009-public-api-and-no-package-cycles.md) | Every public type a service must not use carries `@StratumInternal`, a type without it is API, an API type exposes only API types, no class moves, and no package ever depends on another in a cycle. |
| [ADR 0018: The Maven groupId is `io.github.exepex.stratum`; Java packages stay under `com.exepex.stratum`](adr/0018-group-id-io-github-exepex.md) | The domain `exepex.com` is lost, so Stratum cannot prove `com.exepex` on Maven Central and a third party could claim it; every artifact moves to `io.github.exepex.stratum`, verified through the GitHub organisation `exepex`, and the Java packages do not change. |
| [ADR 0023: Stratum next to a company framework](adr/0023-stratum-next-to-a-company-framework.md) | The parent sets the Spring Boot version; Stratum warns with `SX-CORE-1005` when it is outside the supported line and never stops startup over it; a company's bean wins over Stratum's, except the routing data source, Stratum's only primary bean; and a fork under the company parent is supported but not recommended. |
| [ADR 0024: Bundle starters, a parent per kind of application, and module switches](adr/0024-bundles-parents-and-module-switches.md) | One bundle starter and one parent per kind of application, one BOM, no starter depending on another, and every module on when its starter is present, off with `stratum.<module>.enabled=false`. |
| [ADR 0025: Separate web starters for Spring MVC and WebFlux](adr/0025-web-starters-for-mvc-and-webflux.md) | `stratum-starter-webmvc` and `stratum-starter-webflux` share one library, one module `web` and one set of `stratum.web.*` keys, with a bundle per stack. |
| [ADR 0026: One error format, and one backward-compatible switch, off by default](adr/0026-error-format-and-backward-compatible-switch.md) | RFC 9457 Problem Details is the standard; one `stratum.backward-compatible` switch in `stratum-core`, `false` by default, protects only external contracts; every code's status is set in `stratum.web.error.statuses`. |
| [ADR 0027: Every version in the root POM, and weekly dependency updates with Dependabot](adr/0027-versions-and-dependency-updates.md) | The root `pom.xml` inherits Spring Boot's parent and sets every version Stratum chooses; Dependabot opens grouped upgrade pull requests every week, each runs the full build and the owner merges it. |
| [ADR 0028: Version numbers, releases, and publishing only when the owner runs it](adr/0028-version-numbers-releases-and-publishing.md) | What a minor and a patch release may change before 1.0, `main` on the next minor's `-SNAPSHOT`, fixes forward, the owner tags and releases, at most one planned release a month, and the Publish workflow runs only when the owner starts it. |
| [ADR 0029: HTTP client starters on Spring HTTP interfaces, with one settings block per client](adr/0029-http-client-starters-and-settings.md) | Each client is a `@StratumClient` interface with its own `stratum.http-client.clients.<name>` block; every key with its default and range; `RestClient` and `RestTemplate` beans per client; clients Stratum did not build; mocks. |
| [ADR 0030: HTTP client auth, retries, circuit breaker and connections](adr/0030-http-client-auth-resilience-and-connections.md) | One kind of `auth` per client, token relay only after Spring Security's resource server verified the token; Resilience4j as the only retry layer with fixed rules; one breaker per client; bounded pools. |
| [ADR 0031: HTTP client errors, body limits and streaming](adr/0031-http-client-errors-and-body-limits.md) | The codes of a call and their statuses; an error answer is a `DownstreamException`, `500` uncaught; the service's own code by `errors`; the interceptor and response body limits; streaming; only startup codes in the startup failure report. |
| [ADR 0032: The HTTP client's call log, meters and spans](adr/0032-http-client-call-log-and-meters.md) | One line per failed call, with the masked `url` only while the logging starter's redaction runs; templates only in tags and span names; Stratum's own meters per client. |

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
