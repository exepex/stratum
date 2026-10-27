# Architecture

**Status: Planned.** How Stratum is built inside. Useful if you contribute to Stratum, or want to
understand exactly what happens when your service starts.

[← Documentation home](../README.md)

## Design principles

| Principle | What it means in practice |
| --- | --- |
| **Convention over configuration** | Every module works with zero configuration and production-safe defaults. |
| **Opt-in** | One starter per concern; a service carries only what it uses. |
| **Never locked in** | Every bean is `@ConditionalOnMissingBean`; every default is a property; every module has an `enabled` switch. |
| **Plain Spring** | Stratum uses Spring Boot's auto-configuration and Spring's extension points. No custom programming model. |
| **Secure by default** | Secrets redacted, timeouts on every remote call, minimal Actuator exposure, validation of tokens. |
| **Fail fast** | Missing or invalid configuration stops startup with a clear message. |
| **Built for volume** | Bounded pools, queues and caches; no blocking on hot paths; retries only for idempotent operations. |
| **No business logic** | Stratum holds cross-cutting concerns only. |

## Repository layout

```text
stratum/
├── pom.xml                         # reactor: builds every module in order
├── stratum-bom/                    # versions only
├── stratum-parent/                 # parent POM for services (imports the BOM)
├── stratum-build-rules/            # Checkstyle, PMD and SpotBugs rules, packaged as a jar
├── stratum-core/                   # shared internals: property defaults, constants, utilities
├── starters/
│   ├── stratum-starter-web/
│   ├── stratum-starter-logging/
│   ├── stratum-starter-observability/
│   ├── stratum-starter-security/
│   ├── stratum-starter-oauth2-client/
│   ├── stratum-starter-http-client/
│   ├── stratum-starter-resilience/
│   ├── stratum-starter-data-jpa/
│   ├── stratum-starter-cache/
│   ├── stratum-starter-kafka/
│   └── stratum-starter-cloud-<provider>/
├── stratum-test/                   # test support for services
├── samples/                        # small example services, built and tested with Stratum
└── docs/                           # this documentation
```

## Inside a starter

Every starter has the same shape:

```text
stratum-starter-http-client/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/example/stratum/httpclient/
    │   │   ├── autoconfigure/                       # wiring only: @AutoConfiguration classes
    │   │   │   └── StratumHttpClientAutoConfiguration.java
    │   │   ├── StratumHttpClientProperties.java     # @ConfigurationProperties("stratum.http-client")
    │   │   └── ...                                  # behaviour, testable without Spring
    │   └── resources/META-INF/
    │       ├── spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
    │       └── additional-spring-configuration-metadata.json
    └── test/
```

- The **`autoconfigure` package only wires beans**. All behaviour lives in ordinary classes that can
  be unit tested without starting Spring.
- **Properties** are a typed `@ConfigurationProperties` class with validation and Javadoc on every
  field; the Javadoc becomes the description your IDE shows.
- Auto-configurations are registered in the `AutoConfiguration.imports` file (never by component
  scanning), with explicit ordering against Spring Boot's own.

## What happens at startup

```mermaid
sequenceDiagram
    participant Boot as Spring Boot
    participant EPP as Stratum defaults<br/>(EnvironmentPostProcessor)
    participant AC as Stratum auto-configurations
    participant App as Your beans
    Boot->>EPP: prepare the environment
    EPP->>Boot: add "stratum-defaults" property source (lowest priority)
    Boot->>Boot: bind and validate stratum.* properties
    Boot->>App: register your @Configuration and @Component beans
    Boot->>AC: evaluate conditions (class present? bean missing? enabled?)
    AC->>Boot: create the beans your service did not define
    Boot->>Boot: log active Stratum modules
```

### How defaults are applied

Stratum puts its defaults for Spring Boot properties (for example `spring.jpa.open-in-view=false`)
into a property source with the **lowest** priority. Any value from your files, environment variables
or command line therefore overrides it, with no special syntax. The defaults of Stratum's own
`stratum.*` properties live in the properties classes themselves.

## How starters work together

Starters never import each other's internal classes. They cooperate through Spring and Micrometer's
public extension points:

| Collaboration | Mechanism |
| --- | --- |
| HTTP client sends trace headers | Spring's observation support on `RestClient`, configured by observability |
| Logs show trace IDs | Micrometer Tracing puts IDs in the MDC; logging writes them |
| Logs show correlation IDs | The web starter puts the ID in the MDC; logging writes it |
| HTTP client sends tokens | A request interceptor backed by Spring Security's `OAuth2AuthorizedClientManager` |
| HTTP client uses resilience policies | The resilience starter registers a customizer when present |
| Errors from HTTP clients become `503`/`504` | The web starter handles `DownstreamUnavailableException` from the shared core |

Shared names (header names, MDC keys, metric names) are constants in `stratum-core`, so every
module uses the same spelling.

## Architecture decisions

Significant decisions are recorded as ADRs (architecture decision records) in `docs/adr/`, numbered
in order. An accepted ADR is never edited; a new one replaces it. Planned first decisions:

| Topic | Status |
| --- | --- |
| Maven `groupId` and Java package names | Open |
| Problem Details (RFC 9457) as the error format | Proposed |
| Resilience4j as the resilience engine | Proposed |
| First cloud provider | Open |
