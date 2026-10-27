# Configuration basics

**Status: Planned.** How to configure Stratum with `application.yml` or `application.properties`, how
property names and values are written, and how Stratum checks them.

[← Documentation home](../README.md)

## Contents

- [YAML or properties: both work](#yaml-or-properties-both-work)
- [How property names are built](#how-property-names-are-built)
- [Writing values: types and formats](#writing-values-types-and-formats)
- [Lists and maps](#lists-and-maps)
- [Environment variables and command-line arguments](#environment-variables-and-command-line-arguments)
- [Which value wins](#which-value-wins)
- [Validation: wrong values fail fast](#validation-wrong-values-fail-fast)
- [IDE completion](#ide-completion)
- [Stratum properties and Spring Boot properties](#stratum-properties-and-spring-boot-properties)

## YAML or properties: both work

Stratum reads its settings through Spring Boot, so every file format and source Spring Boot
supports works with Stratum. The two common files live in `src/main/resources`:

| | `application.yml` | `application.properties` |
| --- | --- | --- |
| Format | Nested by indentation (2 spaces) | One flat `key=value` per line |
| Good for | Many related settings, lists and maps | Few settings, simple tooling, no indentation mistakes |
| Comments | `# comment` | `# comment` |

The same configuration in both formats:

```yaml
# application.yml
spring:
  application:
    name: order-service

stratum:
  logging:
    format: ecs
    redaction:
      enabled: true
  http-client:
    defaults:
      connect-timeout: 1s
      read-timeout: 5s
```

```properties
# application.properties
spring.application.name=order-service

stratum.logging.format=ecs
stratum.logging.redaction.enabled=true
stratum.http-client.defaults.connect-timeout=1s
stratum.http-client.defaults.read-timeout=5s
```

> [!TIP]
> Pick **one** format per service and stick with it. If both files exist in the same place, Spring
> Boot reads both, and for a key set in both, `application.properties` wins. That surprises people.

### Converting between the two

Each level of YAML indentation becomes one dot-separated part of the key:

```text
stratum:                    →  stratum
  http-client:              →  stratum.http-client
    defaults:               →  stratum.http-client.defaults
      read-timeout: 5s      →  stratum.http-client.defaults.read-timeout=5s
```

## How property names are built

Every Stratum property follows the same pattern:

```text
stratum . <module> . <group> . <setting>
   │         │         │          └── what you set, e.g. read-timeout
   │         │         └── optional sub-area, e.g. defaults, redaction, clients.inventory
   │         └── the starter, e.g. http-client, logging, security
   └── always "stratum"
```

Examples: `stratum.logging.redaction.enabled`, `stratum.security.public-paths`,
`stratum.http-client.clients.inventory.base-url`.

Names are written in **kebab-case** (lower case, words joined by `-`). Spring Boot's **relaxed
binding** also accepts `readTimeout` or `read_timeout`, but kebab-case is the standard; use it so
everyone can search for one spelling.

Every module has an on/off switch: `stratum.<module>.enabled` (default `true`).

## Writing values: types and formats

| Type | Write it as | Examples |
| --- | --- | --- |
| Boolean | `true` or `false` | `stratum.cache.enabled=false` |
| Number | Digits | `stratum.resilience.policies.default.retry.max-attempts=3` |
| Text | As is; quote in YAML if it contains `:` or `#` | `stratum.web.correlation.header=X-Correlation-Id` |
| Duration | Number plus unit: `ms`, `s`, `m`, `h`, `d` | `500ms`, `5s`, `10m`, `1h` |
| Data size | Number plus unit: `B`, `KB`, `MB`, `GB` | `256KB`, `10MB` |
| URI or URL | Full address with scheme | `https://idp.example.com/realms/acme` |
| Choice (enum) | One of the listed values, case-insensitive | `stratum.logging.format=ecs` |

> [!WARNING]
> Always write the unit on durations. `read-timeout=5` is read as 5 **milliseconds** for most
> Spring Boot durations, which is almost never what you mean.

## Lists and maps

### Lists

```yaml
stratum:
  security:
    public-paths:
      - /actuator/health/**
      - /docs/**
```

```properties
# Either indexed...
stratum.security.public-paths[0]=/actuator/health/**
stratum.security.public-paths[1]=/docs/**
# ...or comma-separated on one line
stratum.security.public-paths=/actuator/health/**,/docs/**
```

A list set in a higher-priority source **replaces** the whole list; lists are never merged. To add
one path, repeat the full list.

### Maps (named entries)

Many Stratum settings are maps keyed by a name you choose, such as one entry per downstream
service:

```yaml
stratum:
  http-client:
    clients:
      inventory:
        base-url: https://inventory.internal
      payments:
        base-url: https://payments.internal
        read-timeout: 10s
```

```properties
stratum.http-client.clients.inventory.base-url=https://inventory.internal
stratum.http-client.clients.payments.base-url=https://payments.internal
stratum.http-client.clients.payments.read-timeout=10s
```

Use lower-case kebab-case for the names you choose (`inventory`, `payment-gateway`). If a name
contains dots or slashes, wrap it in brackets: `stratum.cache.caches.[orders.v2].ttl=5m`.

## Environment variables and command-line arguments

You can set any property without touching a file. This is the normal way to set values per
environment in containers and Kubernetes.

**Environment variables:** upper case, replace `.` with `_`, remove `-`.

| Property | Environment variable |
| --- | --- |
| `stratum.logging.format` | `STRATUM_LOGGING_FORMAT` |
| `stratum.http-client.defaults.read-timeout` | `STRATUM_HTTPCLIENT_DEFAULTS_READTIMEOUT` |
| `stratum.http-client.clients.inventory.base-url` | `STRATUM_HTTPCLIENT_CLIENTS_INVENTORY_BASEURL` |
| `stratum.security.public-paths[0]` | `STRATUM_SECURITY_PUBLICPATHS_0` |

**Command-line arguments:** prefix with `--`:

```bash
java -jar order-service.jar --stratum.logging.format=text --server.port=9090
```

## Which value wins

When the same property is set in several places, the source higher in this list wins (simplified
from Spring Boot's full order):

1. Command-line arguments (`--key=value`)
2. Environment variables
3. Profile-specific files outside the jar (`config/application-prod.yml` next to the jar)
4. `application.properties` / `application.yml` outside the jar
5. Profile-specific files inside the jar (`src/main/resources/application-prod.yml`)
6. `application.properties` / `application.yml` inside the jar
7. **Stratum's built-in defaults** (always last, so anything you set wins)

For profiles and per-environment setups, see
[Profiles and environments](profiles-and-environments.md).

## Validation: wrong values fail fast

Stratum validates every `stratum.*` property when the application starts. A missing required value,
a negative timeout or an unknown choice stops the startup with a message like this:

```text
***************************
APPLICATION FAILED TO START
***************************

Description:

Binding to target [Bindable@... type = StratumHttpClientProperties] failed:

    Property: stratum.http-client.clients.inventory.base-url
    Value: "null"
    Reason: must not be null

Action:

Update your application's configuration
```

This is deliberate: a broken setting found at deploy time is far cheaper than one found by a
customer's request at 3 a.m.

## IDE completion

Each Stratum starter ships Spring Boot configuration metadata. IntelliJ IDEA (Ultimate, or with the
Spring plugin), VS Code (with the Spring Boot Tools extension) and Eclipse (Spring Tools) then:

- complete `stratum.*` keys as you type, in both `.yml` and `.properties`,
- show each property's description, type and default,
- warn about unknown or deprecated keys.

If completion does not appear, re-import the Maven project so the IDE sees the starter jars.

## Stratum properties and Spring Boot properties

Stratum does not hide Spring Boot. You can use any Spring Boot property next to Stratum's:

```yaml
server:
  port: 8080
logging:
  level:
    com.acme.orders: DEBUG
stratum:
  logging:
    format: ecs
```

Some Stratum properties are shortcuts that set Spring Boot properties for you. For example,
`stratum.observability.tracing.sampling-probability` sets Spring Boot's
`management.tracing.sampling.probability`. If you set the Spring Boot property directly, your value
wins. Each module page lists which Spring Boot properties it sets by default.

Next: [Profiles and environments](profiles-and-environments.md).
