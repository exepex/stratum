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
> Examples use the latest released version, `0.1.0`. These pages describe Stratum's `main` branch, which
> may hold changes made after 0.1.0: the [0.1.0 release notes](releases/0.1.0.md) say what 0.1.0
> contains, and [Using a snapshot](getting-started/installation.md#using-a-snapshot) shows how to try
> the latest published snapshot of `main`, `0.2.0-SNAPSHOT`.

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
| HTTP client | [http-client.md](modules/http-client.md), [auth and mTLS](modules/http-client-auth.md), [errors](modules/http-client-errors.md), [mocked clients](modules/http-client-mocks.md), [retries and the circuit breaker](modules/http-client-resilience.md), [clients Stratum did not build](modules/http-client-own-clients.md), [headers and interceptors](modules/http-client-headers.md), [call log and meters](modules/http-client-observability.md) |
| Resilience | [resilience.md](modules/resilience.md) |
| Data (JPA, connection pool, transactions) | [data-jpa.md](modules/data-jpa.md), [statement time limits](modules/data-jpa-statement-limits.md), [TLS check](modules/data-jpa-tls-check.md), [PostgreSQL login limit](modules/data-jpa-login-limit.md) |
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
| [Error and warning codes](guides/error-codes.md) | What Stratum's codes look like, the internal and external error objects, using codes in your service, and every code with its fix. |
| [Troubleshooting](guides/troubleshooting.md) | Finding out what Stratum configured, and fixes for common problems. |
| [FAQ](guides/faq.md) | Short answers to common questions. |
| [Release notes for 0.1.0](releases/0.1.0.md) | What Stratum's first release contains, the modules left out of it and its known gaps. |

## Architecture decisions

| Record | What it decides |
| --- | --- |
| [ADR 0001: Stratum next to a company framework](adr/0001-coexistence-with-company-frameworks.md) | The parent sets the Spring Boot version, a company's bean wins over Stratum's, and a fork under the company parent is supported but not recommended. |
| [ADR 0002: No startup check of the Spring Boot version](adr/0002-no-startup-version-check.md) | Replaces the startup version check of ADR 0001: the parent POM alone decides the Spring Boot version. |
| [ADR 0003: Bundle starters and module switches](adr/0003-bundle-starters-and-module-switches.md) | One bundle starter per kind of application instead of parents, and every module off until `stratum.<module>.enabled=true`. Replaced in part by ADR 0010. Also replaced in part by ADR 0019. |
| [ADR 0004: Weekly dependency updates with Dependabot](adr/0004-dependency-updates.md) | Dependabot opens grouped upgrade pull requests every week, Spring upgrades together, each one runs the full build and the owner merges it. |
| [ADR 0005: Every version in the root POM](adr/0005-versions-in-the-root-pom.md) | Replaces two parts of ADR 0004: the root `pom.xml` inherits Spring Boot's parent and sets every version Stratum chooses, so no version is copied by hand. |
| [ADR 0006: Separate starters for Spring MVC and WebFlux](adr/0006-separate-mvc-and-webflux-starters.md) | `stratum-starter-webmvc` and `stratum-starter-webflux` share one library and one set of `stratum.web.*` keys, and a new reactive microservice bundle brings WebFlux. |
| [ADR 0007: Warn when Spring Boot is outside the supported line](adr/0007-spring-boot-version-warning.md) | Amends ADR 0002: Stratum logs one `SX-CORE-1005` warning at startup for an unsupported Spring Boot version, and never stops the service. |
| [ADR 0008: One error format, with a backward-compatible mode on by default](adr/0008-error-format-and-backward-compatible-mode.md) | RFC 9457 Problem Details is the standard; `stratum.web.backward-compatible` is `true` by default so running services keep their consumers' error bodies and get no new limits, and the mode is permanent and frozen. Replaced in part by ADR 0010: the switch is now `stratum.backward-compatible`, `false` by default. |
| [ADR 0009: Mark internal types with `@StratumInternal`, and keep packages free of cycles](adr/0009-public-api-and-no-package-cycles.md) | Every public type a service must not use carries `@StratumInternal`, a type without it is API, an API type exposes only API types, no class moves, and no package ever depends on another in a cycle. |
| [ADR 0010: Modules on when present, and one compatibility switch](adr/0010-modules-on-when-present-and-one-compatibility-switch.md) | Replaces parts of ADR 0003, 0006 and 0008, and brings back a step of ADR 0001: every module is on when its starter is on the classpath, a service turns one off with `stratum.<module>.enabled=false`, and one `stratum.backward-compatible` switch in `stratum-core`, `false` by default, protects only external contracts. Both decisions are built: decision 1 in item 2a, decision 2 in item 2b. |
| [ADR 0011: HTTP client starters on Spring HTTP interfaces, with one Stratum block per client](adr/0011-http-client-starters.md) | Each client is a `@StratumClient` interface with its own `stratum.http-client.clients.<name>` block and its own pool; Resilience4j is the only retry layer, with fixed retry and breaker rules; safe defaults (8s total, 2s pool wait, 5-minute connection lifetime); token relay fails closed. Changes ST-ENG-34, ST-SEC-03 and ST-ENG-51. Mostly built; the open items are #308, #262 and #269. |
| [ADR 0012: Changes to the HTTP client starters found while building them](adr/0012-http-client-changes-found-while-building.md) | Replaces three parts of ADR 0011: the key is `interfaces`, a list; `MockClient` lives in the HTTP client starter; and `stratumHttpClients.restClient(name)` sends each call once, with retry from a `resilience(name)` wrapper. Replaced in part by ADR 0014. |
| [ADR 0013: Retry and circuit breaker keys for the HTTP client starters](adr/0013-http-client-retry-and-circuit-breaker-keys.md) | Replaces the `retries`, `retry-delay` and `circuit-breaker` keys of ADR 0011 with the `retry` and `circuit-breaker` groups, named as in Spring and Resilience4j, under `defaults` and each client; what is retried and counted stays fixed. |
| [ADR 0014: A client's RestClient and RestTemplate are beans that retry and guard their calls](adr/0014-http-client-beans-that-retry.md) | Replaces ADR 0012 decision 3 and ADR 0011's `resilience(name)`: each client is a `RestClient` and a `RestTemplate` bean, injected by `@Qualifier("<name>")`, whose calls retry and are guarded by the client's settings within `total-timeout`; a call with a body is sent once; a client with its own engine gets `settings(name)` only, its retry and breaker in #262. |
| [ADR 0015: A limit on the request body an HTTP client interceptor reads](adr/0015-http-client-interceptor-body-limit.md) | `OutgoingRequest.body()` reads at most `interceptor-body-limit` (1 MiB by default, 1 B to 1 GiB, in both modes); a larger body fails before sending with `SX-HTTPC-0005`, not retried and ignored by the circuit breaker. |
| [ADR 0016: The HTTP client's call log key, its redaction signal and its meters](adr/0016-http-client-call-log-and-meters.md) | Adds the key `call-log` (`errors`, `all`, `none`); the logging starter tells the client starter its redaction runs through a marker bean, so the line carries the masked `url` only then; Stratum's own meters per client, with the pool-full counter of #237. |
| [ADR 0017: A limit on the body of a successful answer an HTTP client reads](adr/0017-http-client-response-body-limit.md) | A client reads at most `response-body-limit` of a successful answer (32 MB by default, `-1` for none, otherwise 1 B to 1 GiB, in both modes); a larger one fails with `SX-HTTPC-0006`, not retried and counted as a success by the circuit breaker; an `InputStream` download is not limited. |
| [ADR 0018: The Maven groupId is `io.github.exepex.stratum`; Java packages stay under `com.exepex.stratum`](adr/0018-group-id-io-github-exepex.md) | The domain `exepex.com` is lost, so Stratum cannot prove `com.exepex` on Maven Central and a third party could claim it; every artifact moves to `io.github.exepex.stratum`, verified through the GitHub organisation `exepex`, and the Java packages do not change. |
| [ADR 0019: One parent per kind of application, which brings its bundle; one BOM](adr/0019-one-parent-per-kind-of-application.md) | Replaces ADR 0003 decision 3: a parent per kind of application, such as `stratum-microservice-parent`, inherits `stratum-parent` and brings that kind's bundle, `stratum-test` and `spring-boot-starter-test`, so a new microservice writes only `<parent>`; `stratum-bom` stays the one BOM. |
| [ADR 0020: Version numbers and releases](adr/0020-version-numbers-and-releases.md) | What a minor and a patch release may change before 1.0, `main` on the next minor's `-SNAPSHOT`, fixes forward with a patch branch only for an urgent security fix, the owner tags and releases, and at most one planned release a month because of Maven Central's publishing limits. |
| [ADR 0021: Publish to Maven Central only when the owner runs it](adr/0021-publish-only-when-the-owner-runs-it.md) | Replaces ADR 0020's statement that each push to `main` deploys a snapshot: the Publish workflow runs only for a tag `v<version>` the owner creates, or when the owner runs it on `main` or a `release/*` branch. |

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
