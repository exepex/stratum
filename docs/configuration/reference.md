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
| `stratum.context-propagation.async-threads` | boolean | `true` | logging, observability | Whether `@Async` methods and work on Spring Boot's executors keep the trace and workflow fields of the code that started them. Sets `spring.task.execution.propagate-context`. When the service's only executors are its own pool beans, Stratum also creates the task executor Spring Boot would have made and points `@Async` at it, so `@Async` keeps them ([details](../guides/context-across-threads.md)). |
| `stratum.context-propagation.reactor` | boolean | `true` | logging, observability | Whether log lines in WebFlux handlers and every Reactor operator keep the trace, correlation ID and workflow fields, and `WebClient` sends the correlation ID on. Sets `spring.reactor.context-propagation` to `auto` (or `limited` when `false`). |
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

| Prefix | Module | Full table | Required settings |
| --- | --- | --- | --- |
| `stratum.web.*` | `stratum-starter-webmvc`, `stratum-starter-webflux` | [Web MVC → Properties](../../starters/stratum-starter-webmvc/README.md#properties), [WebFlux → Properties](../../starters/stratum-starter-webflux/README.md#properties), all of them, planned ones included, in [Web](../modules/web.md#properties) | none |
| `stratum.logging.*` | `stratum-starter-logging` | [Logging → Properties](../../starters/stratum-starter-logging/README.md#properties) | none |
| `stratum.observability.*` | `stratum-starter-observability` | [Observability → Properties](../../starters/stratum-starter-observability/README.md#properties) | Collector addresses, once enabled |
| `stratum.security.*` | `stratum-starter-security` | [Security → Properties](../modules/security.md#properties) | `resource-server.issuer-uri` |
| `stratum.oauth2-client.*` | `stratum-starter-oauth2-client` | [OAuth2 client → Properties](../modules/oauth2-client.md#properties) | a `spring.security.oauth2.client.registration.<name>` |
| `stratum.http-client.*` | `stratum-starter-http-client`, `stratum-starter-reactive-http-client` | [HTTP client → Every key a client needs](../modules/http-client.md#every-key-a-client-needs) | `spring.http.serviceclient.<name>.base-url` for each client |
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
| `stratum.http-client.enabled` | HTTP client timeouts and the bounded connection pool |
| `stratum.resilience.enabled` | All resilience policies |
| `stratum.data.enabled` | Stratum's data defaults |
| `stratum.cache.enabled` | Caching |
| `stratum.kafka.enabled` | Stratum's Kafka defaults |
| `stratum.cloud.enabled` | Cloud connectors |

## Spring Boot properties Stratum sets for you

Stratum applies these as **defaults**. They have the lowest priority, so setting any of them yourself
always wins. Where a default comes from a Stratum property, change that property: your IDE completes
it and shows what it does. Details are on each module page.

| Spring Boot property | Stratum default | Set by |
| --- | --- | --- |
| `logging.structured.format.console` | `ecs` | logging |
| `logging.structured.format.file` | `ecs` | logging |
| `logging.structured.ecs.service.environment` | `${stratum.environment}` | logging |
| `logging.structured.json.stacktrace.max-length` | `16384` (from `stratum.logging.max-field-length`) | logging |
| `logging.structured.json.stacktrace.root` | `first`, the root cause (from `stratum.logging.stack-trace-starts-with-root-cause`) | logging |
| `spring.task.execution.propagate-context` | `true` (from `stratum.context-propagation.async-threads`) | logging, observability |
| `spring.reactor.context-propagation` | `auto` (from `stratum.context-propagation.reactor`) | logging, observability |
| `management.otlp.metrics.export.enabled` | `false` while the module is off | observability |
| `management.tracing.export.enabled` | `false` while the module is off | observability |
| `management.endpoints.web.exposure.include` | `health,info` (from `stratum.observability.actuator-endpoints-exposed-over-http`) | observability |
| `management.metrics.tags.application`, `.environment`, `.host` | `${spring.application.name}`, `${stratum.environment}`, `$HOSTNAME` | observability |
| `management.opentelemetry.resource-attributes[deployment.environment.name]` | `${stratum.environment}` | observability |
| `management.metrics.distribution.percentiles-histogram.all` | `true` (from `stratum.observability.record-timings-as-histograms`) | observability |
| `management.otlp.metrics.export.histogram-flavor` | `base2-exponential-bucket-histogram` | observability |
| `spring.kafka.template.observation-enabled`, `spring.kafka.listener.observation-enabled` | `true` (from `stratum.observability.trace-kafka-and-rabbitmq-messages`) | observability |
| `spring.rabbitmq.template.observation-enabled`, `spring.rabbitmq.listener.simple.observation-enabled`, `.direct.observation-enabled`, `.stream.observation-enabled` | `true` (from `stratum.observability.trace-kafka-and-rabbitmq-messages`) | observability |
| `spring.http.clients.connect-timeout` | `2s` | http-client |
| `spring.http.clients.read-timeout` | `5s` | http-client |
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
  http:
    serviceclient:
      inventory:
        base-url: http://inventory-service
      payments:
        base-url: http://payments-service
        read-timeout: 10s

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
        token-relay: true
      payments:
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
spring.http.serviceclient.inventory.base-url=http://inventory-service
spring.http.serviceclient.payments.base-url=http://payments-service
spring.http.serviceclient.payments.read-timeout=10s
stratum.http-client.clients.inventory.token-relay=true
stratum.http-client.clients.payments.resilience-policy=payments
stratum.resilience.policies.payments.retry.enabled=false
```
