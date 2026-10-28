# Architecture

**Status: Planned, except the repository layout and the build, which exist.** How Stratum is built
inside. Useful if you contribute to Stratum, or want to understand exactly what happens when your
service starts.

[← Documentation home](../README.md)

## Design principles

| Principle | What it means in practice |
| --- | --- |
| **Convention over configuration** | Every module works with zero configuration and production-safe defaults. |
| **Opt-in** | One starter per concern; a service carries only what it uses. |
| **Never locked in** | Every bean is `@ConditionalOnMissingBean`; every default is a property; every module has an `enabled` switch and stays off until a service sets it. |
| **Plain Spring** | Stratum uses Spring Boot's auto-configuration and Spring's extension points. No custom programming model. |
| **Secure by default** | Secrets redacted, timeouts on every remote call, minimal Actuator exposure, validation of tokens. |
| **Fail fast** | Missing or invalid configuration stops startup with a clear message. |
| **Built for volume** | Bounded pools, queues and caches; no blocking on hot paths; retries only for idempotent operations. |
| **No business logic** | Stratum holds cross-cutting concerns only. |

## Repository layout

The build skeleton exists; the modules in it hold no code yet. The repository keeps three groups
apart: the platform (versions, build rules and parent POMs), the shared libraries, and the starters.

```text
stratum/
├── pom.xml                         # reactor and every version: Spring Boot, Java, tools, extra libraries
├── mvnw, mvnw.cmd                  # Maven Wrapper: downloads and runs the pinned Maven version
├── .mvn/maven.config               # Stratum's version (revision), the jar timestamp and the coverage gate
├── platform/
│   ├── stratum-bom/                # versions of Stratum's modules and the extra libraries it uses
│   ├── stratum-build-parent/       # the BOM and the build checks
│   ├── stratum-build-rules/        # Checkstyle, PMD and Enforcer rules, packaged as a jar
│   ├── stratum-library-parent/     # parent POM for libs and starters: adds Lombok and Stratum-only checks
│   └── stratum-parent/             # parent POM for services: adds the executable jar and build info
├── libs/
│   ├── stratum-core/               # shared internals: constants, property defaults
│   └── stratum-test/               # test support for services
├── starters/
│   ├── stratum-starter-batch/      # bundle: data-jpa, logging, observability
│   ├── stratum-starter-cache/
│   ├── stratum-starter-cli/        # bundle: logging
│   ├── stratum-starter-data-jpa/
│   ├── stratum-starter-http-client/
│   ├── stratum-starter-kafka/
│   ├── stratum-starter-logging/
│   ├── stratum-starter-microservice/ # bundle: web, logging, observability, http-client, resilience, security
│   ├── stratum-starter-oauth2-client/
│   ├── stratum-starter-observability/
│   ├── stratum-starter-resilience/
│   ├── stratum-starter-security/
│   ├── stratum-starter-web/
│   └── stratum-starter-web-ui/     # bundle: web, logging, observability, security
└── docs/                           # this documentation
```

Planned, not in the repository yet: `starters/stratum-starter-cloud-<provider>/`, once the first
cloud provider is chosen, and `samples/`, small example services built and tested with Stratum.

### Where each version is set

| What | Set in | Rule |
| --- | --- | --- |
| Stratum's own version | `.mvn/maven.config`, as `-Drevision=...` | Every POM says `${revision}`; `flatten-maven-plugin` writes the real version into each POM it installs |
| Spring Boot and every library and plugin it manages | The `<parent>` of the root `pom.xml`: `spring-boot-starter-parent` and its version | Stratum never overrides these versions ([ADR 0001](../adr/0001-coexistence-with-company-frameworks.md)) |
| Java, the tools and plugins Spring Boot does not manage, and the libraries `stratum-bom` manages | The root `pom.xml`, in `<properties>` | One `*.version` property each, sorted by name ([ADR 0005](../adr/0005-versions-in-the-root-pom.md)) |
| Which of Stratum's modules and extra libraries a service can use | `platform/stratum-bom` | One `<dependencyManagement>` list, sorted by name, with its versions from the root `pom.xml` |
| Maven itself | `.mvn/wrapper/maven-wrapper.properties` | `./mvnw` downloads that version and checks its SHA-256 |

No POM other than the root `pom.xml` writes a version number. Enforcer's `requirePluginVersions`,
run from the root `pom.xml`, fails the build if a plugin has no version.

Stratum's own jars are reproducible: `.mvn/maven.config` sets `project.build.outputTimestamp`, so two
builds of the same commit give byte-identical jars. Services are not affected, so their
`/actuator/info` still shows the real build time.

### How the parents fit together

```mermaid
flowchart TD
    SB[spring-boot-starter-parent] -->|parent of| ROOT[root pom.xml]
    ROOT -->|parent of| BP[stratum-build-parent]
    BOM[stratum-bom] -->|imported by| BP
    BR[stratum-build-rules] -->|rules loaded by| BP
    BP -->|parent of| SP[stratum-parent]
    BP -->|parent of| LP[stratum-library-parent]
    LP -->|parent of| LIBS[libs and starters]
    SP -->|parent of| SVC[a service]
    ROOT -->|parent of| BOM
    ROOT -->|parent of| BR
```

- The root `pom.xml` sets every version, as described above, and is the parent of every other
  Stratum POM.
- `stratum-build-parent` imports the BOM and holds the build checks. Services get it through
  `stratum-parent`, and Stratum's libraries and starters through `stratum-library-parent`, so
  Stratum's own code is held to the rules it gives its users.
- `stratum-library-parent` adds Lombok and the annotation processors Stratum's own code uses, and
  checks for that code: Google's Checkstyle configuration (`google_checks.xml`), Javadoc on every
  type and method and at most 400 lines per Java file (`stratum/checkstyle-library.xml`), and no
  hand-written code that Lombok generates (`stratum/pmd-lombok.xml`). Services get none of these;
  those choices are their team's.
- `stratum-parent` adds what only a service needs: the executable jar, `build-info.properties` and
  `git.properties`. Libraries do not inherit it, because a library jar that carried those two files
  would hide the service's own files from `/actuator/info`.
- `stratum-bom` and `stratum-build-rules` inherit the root `pom.xml` directly: `stratum-build-parent`
  imports the BOM and loads the build rules, so neither can inherit `stratum-build-parent`. The BOM
  is installed without a parent, so it never brings Spring Boot's versions to a service.

## Inside a starter

Every starter has the same shape:

```text
stratum-starter-http-client/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/exepex/stratum/httpclient/
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
  scanning), with explicit ordering against Spring Boot's own. They sort after auto-configurations
  that declare no order, so a company framework's beans are created first and Stratum's back off
  (see [ADR 0001](../adr/0001-coexistence-with-company-frameworks.md)).

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
in order. An accepted ADR is never edited; a new one replaces it. Decisions so far, and the ones
planned next:

| Topic | Status |
| --- | --- |
| [0001: Stratum next to a company framework](../adr/0001-coexistence-with-company-frameworks.md) | Accepted; the startup version check in it is replaced by 0002 |
| [0002: No startup check of the Spring Boot version](../adr/0002-no-startup-version-check.md) | Accepted |
| [0003: Bundle starters and module switches](../adr/0003-bundle-starters-and-module-switches.md) | Accepted |
| Maven `groupId` and Java package names | Decided by the owner: `com.exepex.stratum`, and packages under `com.exepex.stratum` |
| Problem Details (RFC 9457) as the error format | Proposed |
| Resilience4j as the resilience engine | Proposed |
| First cloud provider | Open |
