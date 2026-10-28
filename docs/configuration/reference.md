# Configuration reference

**Status: Planned.** Every `stratum.*` property, grouped by module. Each module page holds the full
table (type, default, description) and examples in YAML and properties; this page is the map that
gets you there.

[← Documentation home](../README.md)

New to configuration? Read [Configuration basics](basics.md) first.

## Shared properties

These are not tied to one module.

| Property | Type | Default | Used by | Description |
| --- | --- | --- | --- | --- |
| `spring.application.name` | string | **required** | All modules | The service's name: in logs, metrics, traces, cache keys and the Kafka consumer group. Spring Boot property. |
| `stratum.environment` | string | *(none)* | logging, observability | Environment name such as `dev`, `staging` or `prod`; written to logs as `service.environment` and to metrics as the `environment` tag. |

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

| Prefix | Module | Full table | Required settings |
| --- | --- | --- | --- |
| `stratum.web.*` | `stratum-starter-web` | [Web → Properties](../modules/web.md#properties) | none |
| `stratum.logging.*` | `stratum-starter-logging` | [Logging → Properties](../modules/logging.md#properties) | none |
| `stratum.observability.*` | `stratum-starter-observability` | [Observability → Properties](../modules/observability.md#properties) | none |
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
starter, or a [bundle](../modules/bundles.md), sets nothing up by itself.

| Property | Switches on |
| --- | --- |
| `stratum.web.enabled` | Error format, correlation IDs, request logging, JSON settings |
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

Stratum applies these as **defaults**. They have the lowest priority, so setting any of them yourself
always wins. Details are on each module page.

| Spring Boot property | Stratum default | Set by |
| --- | --- | --- |
| `spring.mvc.problemdetails.enabled` | `true` | web |
| `server.error.include-stacktrace` | `never` | web |
| `server.shutdown` | `graceful` | web |
| `logging.structured.format.console` | `ecs` | logging |
| `management.endpoints.web.exposure.include` | `health,info,prometheus` | observability |
| `management.endpoint.health.probes.enabled` | `true` | observability |
| `management.tracing.sampling.probability` | `0.1` | observability |
| `spring.jpa.open-in-view` | `false` | data |
| `spring.jpa.hibernate.ddl-auto` | `validate` | data |
| `spring.kafka.producer.acks` | `all` | kafka |
| `spring.kafka.consumer.group-id` | `${spring.application.name}` | kafka |

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

stratum:
  environment: prod
  security:
    enabled: true
    resource-server:
      issuer-uri: https://idp.acme.com/realms/acme
      audiences: [order-service]
  observability:
    enabled: true
    tracing:
      sampling-probability: 0.05
      export:
        endpoint: http://otel-collector:4318/v1/traces
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

stratum.environment=prod
stratum.security.enabled=true
stratum.observability.enabled=true
stratum.http-client.enabled=true
stratum.resilience.enabled=true
stratum.security.resource-server.issuer-uri=https://idp.acme.com/realms/acme
stratum.security.resource-server.audiences=order-service
stratum.observability.tracing.sampling-probability=0.05
stratum.observability.tracing.export.endpoint=http://otel-collector:4318/v1/traces
stratum.http-client.clients.inventory.base-url=http://inventory-service
stratum.http-client.clients.inventory.token-relay=true
stratum.http-client.clients.payments.base-url=http://payments-service
stratum.http-client.clients.payments.read-timeout=10s
stratum.http-client.clients.payments.resilience-policy=payments
stratum.resilience.policies.payments.retry.enabled=false
```
