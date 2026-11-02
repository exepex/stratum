# Configuration reference

**Status: Built** for the web, logging and observability modules; the others are planned. This page
is the map of Stratum's settings: the keys that every module shares, which belong to no module, and
a link to each module's own table. Each module's table is the one list of its keys, `stratum.*` and
the Spring Boot keys it gives a default, with Stratum's default and Spring Boot's side by side. This
page never copies them.

[← Documentation home](../README.md)

New to configuration? Read [Configuration basics](basics.md) first.

## Shared properties

These are not tied to one module.

| Property | Type | Default | Used by | Description |
| --- | --- | --- | --- | --- |
| `spring.application.name` | string | **required** | All modules | The service's name: in logs, metrics, traces, cache keys and the Kafka consumer group. Spring Boot property. |
| `stratum.environment` | string | *(none)* | logging, observability | Environment name such as `dev`, `staging` or `prod`; written to logs as `service.environment` and to metrics as the `environment` tag. |
| `stratum.context-propagation.executor-beans` | boolean | `true` | logging, observability | Whether the pool beans the service declares as `Executor`, `ExecutorService` or `ScheduledExecutorService` are wrapped so their tasks keep the trace, correlation ID, workflow and service MDC fields of the code that submitted them. |

```yaml
spring:
  application:
    name: order-service
stratum:
  environment: prod
```

```properties
spring.application.name=order-service
stratum.environment=prod
```

## Property prefixes by module

Each module's full table, with every key it reads or gives a default:

| Prefix | Module | Full table | Required settings |
| --- | --- | --- | --- |
| `stratum.web.*` | `stratum-starter-webmvc`, `stratum-starter-webflux` | [Web → Properties](../modules/web.md#properties), for both web starters | none |
| `stratum.logging.*` | `stratum-starter-logging` | [Logging → Properties](../../starters/stratum-starter-logging/README.md#properties) | none |
| `stratum.observability.*` | `stratum-starter-observability` | [Observability → Properties](../../starters/stratum-starter-observability/README.md#properties) | Collector addresses, once enabled |
| `stratum.security.*` | `stratum-starter-security` | [Security → Properties](../modules/security.md#properties) | `resource-server.issuer-uri` |
| `stratum.oauth2-client.*` | `stratum-starter-oauth2-client` | [OAuth2 client → Properties](../modules/oauth2-client.md#properties) | a `spring.security.oauth2.client.registration.<name>` |
| `stratum.http-client.*` | `stratum-starter-http-client` | [HTTP client → Properties](../modules/http-client.md#properties) | `clients.<name>.base-url` |
| `stratum.resilience.*` | `stratum-starter-resilience` | [Resilience → Properties](../modules/resilience.md#properties) | none |
| `stratum.data.*` | `stratum-starter-data-jpa` | [Data → Properties](../modules/data-jpa.md#properties) | `spring.datasource.url`, `username`, `password` |
| `stratum.cache.*` | `stratum-starter-cache` | [Cache → Properties](../modules/cache.md#properties) | `spring.data.redis.*` when `type=redis` |
| `stratum.kafka.*` | `stratum-starter-kafka` | [Kafka → Properties](../modules/kafka.md#properties) | `spring.kafka.bootstrap-servers` |
| `stratum.cloud.*` | `stratum-starter-cloud-*` | [Cloud → Properties](../modules/cloud.md#properties-draft) | depends on provider |
| `stratum.test.*` | `stratum-test` | [Testing → Properties](../modules/test.md#properties) | none |

## On/off switches

Every module is off until you switch it on with one property. All default to `false`, so adding a
starter, or a [bundle](../modules/bundles.md), sets nothing up by itself. A module's own switches,
such as `stratum.web.request-log.enabled`, apply only once the module is on.

| Property | Switches on |
| --- | --- |
| `stratum.web.enabled` | Error format, request fields in logs, correlation ID, request log, response timeouts, body size limit |
| `stratum.logging.enabled` | Structured format and redaction |
| `stratum.observability.enabled` | Stratum's metrics, tracing and health settings |
| `stratum.security.enabled` | Stratum's security chain (Spring Security's defaults then apply) |
| `stratum.oauth2-client.enabled` | Token handling for outgoing calls |
| `stratum.http-client.enabled` | Stratum's HTTP client setup |
| `stratum.resilience.enabled` | All resilience policies |
| `stratum.data.enabled` | Stratum's data defaults |
| `stratum.cache.enabled` | Caching |
| `stratum.kafka.enabled` | Stratum's Kafka defaults |
| `stratum.cloud.enabled` | Cloud connectors |

## Spring Boot properties Stratum sets for you

Where Spring Boot has a key, Stratum has no key of its own: it gives Spring Boot's key a default,
with the lowest priority, so any value you set wins. Each module's table lists the Spring Boot keys
it sets, with both defaults:
[Web](../modules/web.md#properties),
[Logging](../../starters/stratum-starter-logging/README.md#properties) and
[Observability](../../starters/stratum-starter-observability/README.md#properties). The planned
modules' pages list theirs.

## A complete example

A service that exposes a secured API, calls two services and uses a database, in both formats.

```yaml
spring:
  application:
    name: order-service
  datasource:
    url: jdbc:postgresql://orders-db:5432/orders
    username: orders
    password: ${ORDERS_DB_PASSWORD}

# Where the observability starter sends metrics and traces: your OpenTelemetry Collector.
# Required in every profile once stratum.observability.enabled is true.
management:
  otlp:
    metrics:
      export:
        url: http://otel-collector:4318/v1/metrics
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: http://otel-collector:4318/v1/traces

stratum:
  environment: prod
  security:
    enabled: true
    resource-server:
      issuer-uri: https://idp.acme.com/realms/acme
      audiences: [order-service]
  observability:
    enabled: true
  http-client:
    enabled: true
    clients:
      inventory:
        base-url: http://inventory-service
        token-relay: true
      payments:
        base-url: http://payments-service
        read-timeout: 10s
        resilience-policy: payments
  resilience:
    enabled: true
    policies:
      payments:
        retry:
          enabled: false
```

```properties
spring.application.name=order-service
spring.datasource.url=jdbc:postgresql://orders-db:5432/orders
spring.datasource.username=orders
spring.datasource.password=${ORDERS_DB_PASSWORD}
# Where the observability starter sends metrics and traces: your OpenTelemetry Collector.
# Required in every profile once stratum.observability.enabled is true.
management.otlp.metrics.export.url=http://otel-collector:4318/v1/metrics
management.opentelemetry.tracing.export.otlp.endpoint=http://otel-collector:4318/v1/traces

stratum.environment=prod
stratum.security.enabled=true
stratum.observability.enabled=true
stratum.http-client.enabled=true
stratum.resilience.enabled=true
stratum.security.resource-server.issuer-uri=https://idp.acme.com/realms/acme
stratum.security.resource-server.audiences=order-service
stratum.http-client.clients.inventory.base-url=http://inventory-service
stratum.http-client.clients.inventory.token-relay=true
stratum.http-client.clients.payments.base-url=http://payments-service
stratum.http-client.clients.payments.read-timeout=10s
stratum.http-client.clients.payments.resilience-policy=payments
stratum.resilience.policies.payments.retry.enabled=false
```
