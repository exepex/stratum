# Logging: `stratum-starter-logging`

**Status: Planned.** Structured JSON logs in one format across every service, with trace and
correlation IDs on every line and secrets redacted before anything is written.

[← Documentation home](../README.md) · Property prefix: `stratum.logging`

## What it does

Logs are only useful if you can search them across services. That needs every service to write the
same fields with the same names. This starter sets up Spring Boot's structured logging in one agreed
format and adds the context needed to follow a request.

You keep logging the usual way, with SLF4J:

```java
@Slf4j // Lombok, or: private static final Logger log = LoggerFactory.getLogger(OrderService.class);
@Service
class OrderService {

  void place(Order order) {
    log.info("Order placed: id={}, items={}", order.id(), order.items().size());
  }
}
```

## Add it

```xml
<dependency>
  <groupId>com.example.stratum</groupId>
  <artifactId>stratum-starter-logging</artifactId>
</dependency>
```

Logback is the logging backend, as in Spring Boot by default.

## What you get

A log line like the one above comes out as one JSON object (shown formatted here):

```json
{
  "@timestamp": "2026-10-27T21:15:04.512Z",
  "log.level": "INFO",
  "message": "Order placed: id=8f14e45f, items=3",
  "service.name": "order-service",
  "service.environment": "prod",
  "process.thread.name": "tomcat-handler-12",
  "log.logger": "com.acme.orders.OrderService",
  "trace.id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "span.id": "00f067aa0ba902b7",
  "correlation_id": "c0a8012e-7f3d-4a8e-9a43-2f9b6f1c2d10"
}
```

| Feature | Behaviour |
| --- | --- |
| Structured format | [Elastic Common Schema (ECS)](https://www.elastic.co/guide/en/ecs/current/) by default, so field names are the same everywhere |
| Trace context | `trace.id` and `span.id` on every line written during a traced request (needs the observability starter) |
| Correlation ID | `correlation_id` on every line (needs the web starter) |
| Service identity | `service.name` from `spring.application.name`, `service.environment` from [`stratum.environment`](../configuration/reference.md#shared-properties), plus version |
| Redaction | Values of secret-looking keys and patterns are replaced with `****` before writing |
| Readable local logs | `text` format with colours when the `local` profile is active |

### What redaction covers

Redaction is applied to the final log message and to structured key-value pairs:

| Rule | Example input | Written as |
| --- | --- | --- |
| Key names (case-insensitive): `password`, `passwd`, `secret`, `token`, `access_token`, `refresh_token`, `authorization`, `api-key`, `apikey`, `client-secret`, `cookie`, `set-cookie` | `password=hunter2` | `password=****` |
| `Bearer` tokens | `Authorization: Bearer eyJhbGci...` | `Authorization: Bearer ****` |
| JWT-shaped strings | `eyJhbGciOi....eyJzdWIi....sig` | `****` |
| Your own keys and patterns | see [properties](#properties) | `****` |

> [!IMPORTANT]
> Redaction is a safety net, not a licence. Do not log personal data or secrets on purpose. A
> pattern can always miss a format it was not written for.

## Properties

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `stratum.logging.enabled` | boolean | `true` | Switches the whole starter on or off. |
| `stratum.logging.format` | `ecs`, `logstash`, `gelf`, `text` | `ecs` | Console log format. `text` is for humans; the others are JSON. |
| `stratum.logging.include-trace-context` | boolean | `true` | Adds `trace.id` and `span.id`. |
| `stratum.logging.redaction.enabled` | boolean | `true` | Applies redaction. |
| `stratum.logging.redaction.replacement` | string | `****` | Text written instead of a redacted value. |
| `stratum.logging.redaction.extra-keys` | list | *(empty)* | More key names to redact, added to the built-in list. |
| `stratum.logging.redaction.extra-patterns` | list of regex | *(empty)* | More patterns whose matches are redacted. |
| `stratum.logging.mdc.extra-keys` | list | *(empty)* | Additional MDC keys to write as top-level fields. |

Spring Boot properties this starter sets by default (your values win):

| Spring Boot property | Stratum default |
| --- | --- |
| `logging.structured.format.console` | value of `stratum.logging.format` (unset when `text`) |
| `logging.level.root` | `INFO` |

Log levels are still set with Spring Boot's `logging.level.*` properties.

## Examples

Production: JSON, environment name, extra redaction:

```yaml
stratum:
  environment: prod
  logging:
    format: ecs
    redaction:
      extra-keys:
        - iban
        - card-number
      extra-patterns:
        - '\b\d{4}[ -]?\d{4}[ -]?\d{4}[ -]?\d{4}\b'   # card-like numbers
logging:
  level:
    com.acme.orders: DEBUG
```

```properties
stratum.logging.format=ecs
stratum.environment=prod
stratum.logging.redaction.extra-keys=iban,card-number
stratum.logging.redaction.extra-patterns[0]=\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?\\d{4}\\b
logging.level.com.acme.orders=DEBUG
```

> [!NOTE]
> In `.properties` files a backslash must be doubled (`\\d`). In YAML, single-quote the pattern
> and write each backslash once.

Readable logs on your laptop, in `application-local.yml`:

```yaml
stratum:
  logging:
    format: text
```

```properties
stratum.logging.format=text
```

Add your own field to every log line of a request:

```java
try (var ignored = MDC.putCloseable("tenant_id", tenantId)) {
  orderService.place(order);
}
```

```yaml
stratum:
  logging:
    mdc:
      extra-keys:
        - tenant_id
```

```properties
stratum.logging.mdc.extra-keys=tenant_id
```

## Configure in Java

**Planned.** You will also be able to set every property on this page in Java code, through this module's
customizer or builder, with the same names and defaults. This section will show how once that API is designed.

## Customize

- **Different JSON fields:** Spring Boot's `StructuredLogFormatter` lets you write your own format;
  set `logging.structured.format.console` to its class name. Prefer the standard format so logs
  stay searchable across services.
- **Your own Logback file:** a `logback-spring.xml` in your service replaces Stratum's setup
  completely, including redaction. Avoid it unless you have a strong reason.

## Switch it off

`stratum.logging.enabled=false` returns to Spring Boot's default plain-text logging.

## Troubleshooting

| Symptom | Likely cause and fix |
| --- | --- |
| No `trace.id` in log lines | `stratum-starter-observability` is missing, or the line was written outside a request (for example at startup). |
| Logs are plain text in production | The `local` profile is active, or `stratum.logging.format=text` is set somewhere. Check `/actuator/env` in a safe environment. |
| A secret still appears in logs | Add its key to `extra-keys` or its shape to `extra-patterns`, then rotate the secret. Report the format so it can be added to the built-in list. |
| Duplicate log lines | Your service has its own `logback-spring.xml` adding a second appender. |
