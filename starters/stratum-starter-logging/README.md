# Logging: `stratum-starter-logging`

**Status: Partly built.** Structured JSON logs in one format across every service, with the service's
name and environment on every line, and secrets and personal data masked before anything is written.
Trace IDs, workflow names and context that follows a request across threads are
[planned](#planned).

This page covers this module only. How Stratum works as a whole, its other modules and how
configuration works in general are in the [documentation](../../docs/README.md).

[← Documentation home](../../docs/README.md) · Property prefix: `stratum.logging`

## What it does

Logs are only useful if you can search them across services. That needs every service to write the
same fields with the same names, and no service to leak a password or a customer's card number into
them. This starter sets up Spring Boot's structured logging in one agreed format and masks
sensitive values in every field of every line.

You keep logging the usual way, with SLF4J:

```java
@Slf4j // Lombok, or: private static final Logger log = LoggerFactory.getLogger(OrderService.class);
@Service
class OrderService {

  void place(Order order) {
    log.atInfo().addKeyValue("order_id", order.id()).log("Order placed");
  }
}
```

## Add it

```xml
<dependency>
  <groupId>com.exepex.stratum</groupId>
  <artifactId>stratum-starter-logging</artifactId>
</dependency>
```

Every bundle starter (`stratum-starter-microservice`, `-web-ui`, `-batch` and `-cli`) already
includes it, so add it on its own only when your service uses no bundle. The version comes from
Stratum's parent or BOM ([installation](../../docs/getting-started/installation.md)).

The starter does nothing until you switch it on. It also needs the service's name, which it writes
on every line. In `application.properties`:

```properties
spring.application.name=order-service
stratum.environment=prod
stratum.logging.enabled=true
```

or in `application.yml`:

```yaml
spring:
  application:
    name: order-service
stratum:
  environment: prod
  logging:
    enabled: true
```

If the module is on and `spring.application.name` is missing or blank, the service stops at startup
with [`SX-LOGGING-1001`](../../docs/guides/error-codes.md#stratum-starter-logging).

Logback, Spring Boot's default, is the logging backend that Stratum tests.

## How it works with Spring Boot's logging

Spring Boot already sets up Logback for you, with no XML file, and can write
[structured JSON logs](https://docs.spring.io/spring-boot/reference/features/logging.html)
when `logging.structured.format.console` is set. This starter builds on that setup and does not
replace it:

- It ships no Logback XML, so it never clashes with yours. Without one, Spring Boot's own Logback
  setup is used. If you add a `logback-spring.xml`, your file takes over, and only its appenders
  that use Spring Boot's structured encoder are redacted ([Customize](#customize)).
- At startup, before any bean exists, it sets a few of Spring Boot's `logging.structured.*`
  properties, listed [below](#properties). A value you set in a configuration file, an environment
  variable, a system property or a command-line argument wins over Stratum's.
- It adds redaction to Spring Boot's `ecs`, `logstash` and `gelf` formats, so every line in those
  formats is masked, on the console and in a log file.

Everything else, such as `logging.level.*`, `logging.file.name` and `logging.structured.json.add.*`,
works as the Spring Boot documentation describes. To use your own Logback file, see
[Customize](#customize).

## What you get

The line above comes out as one JSON object in the
[Elastic Common Schema (ECS)](https://www.elastic.co/guide/en/ecs/current/), shown formatted here:

```json
{
  "@timestamp": "2026-10-28T21:15:04.512Z",
  "log": { "level": "INFO", "logger": "com.acme.orders.OrderService" },
  "process": { "pid": 4120, "thread": { "name": "tomcat-handler-12" } },
  "service": { "name": "order-service", "environment": "prod", "node": {} },
  "message": "Order placed",
  "order_id": "8f14e45f",
  "ecs": { "version": "8.11" }
}
```

| Field | Where it comes from |
| --- | --- |
| `service.name` | `spring.application.name` (required) |
| `service.environment` | [`stratum.environment`](../../docs/configuration/reference.md#shared-properties), when set |
| `service.version` | Your jar's manifest, when it has a version |
| `error.type`, `error.message`, `error.stack_trace` | The exception you pass to the log call. The root cause comes first, so a cut stack trace still shows why it failed. |
| Your own fields | Key-value pairs (`addKeyValue`) and MDC entries, at the top level |

Elastic reads ECS as it is; Grafana Loki and Datadog parse any JSON line. If your platform needs
another layout, set `stratum.logging.format` to `logstash` or `gelf`.

## Redaction

Redaction runs on every value of a JSON line: the message, the exception's message and stack
trace, MDC entries and key-value pairs. A key-value pair whose value is a number or an object, such
as `addKeyValue("customer", customer)`, is masked through the value's `toString()`; a list or map
is masked element by element. Ready-made JSON, such as a `WritableJson`, is masked through its text;
if anything in it is masked, it is written as a JSON string. It cannot be switched off.

| What | Found by | Written as |
| --- | --- | --- |
| Credentials: any name that contains `password`, `passwd`, `passphrase`, `passcode`, `secret`, `token`, `apikey`, `credential`, `privatekey`, `sessionid`, `cookie` or `authorization`, such as `dbPassword`, `aws_secret_access_key`, `X-CSRF-Token` or `JSESSIONID`; and the whole names `pwd`, `pass`, `auth`, `pin`, `otp`, `cvv` and `cvc` | Field or key name | `****` |
| Payment cards and national IDs: `card_number`, `pan`, `ssn`, `national_id`, `tax_id`, `passport` | Field or key name | `****1234` (last four kept) |
| Card numbers in text | 13 to 19 digits that pass the Luhn check, in groups split by a space or `-`; or split by two spaces, `.`, `/` or `_` used between every group of a card layout (4-4-4-4, 4-4-4-4-3, or American Express's 4-6-5) | `****1111` |
| US social security numbers in text | `123-45-6789` | `****6789` |
| JSON Web Tokens | `eyJ…` with two or three parts | `****` |
| `Basic`, `Bearer`, `Digest` and `Negotiate` credentials; Digest's `name="value"` list runs to the end of the line | The scheme name | `Bearer ****` |
| A password in a URL, with or without a user | `https://user:pass@host`, `redis://:pass@host`; a password with a raw `@` is masked up to the last `@` before the host, never past a space, `/`, `?`, `#`, a quote, `<` or `>`. When another `@` follows a `,`, `;`, `&` or `=`, it is unclear where the password ends, so the rest of the line is masked | `https://****@host`, `redis://****@host` |
| Names, dates of birth and addresses: `first_name`, `last_name`, `full_name`, `username`, `date_of_birth`, `address` and similar | Field or key name | `****` |
| Email, phone and IBAN fields | Field or key name | `****` plus last four |
| Email addresses in text | A valid address | `****.com` |
| International phone numbers in text | A valid number starting with `+` | `****0958` |
| IBANs in text | A valid IBAN (checksum checked) | `****5432` |
| Your own keys and patterns | [`extra-keys` and `extra-patterns`](#properties) | `****` |

Names are compared without case, `_` or `-`, and only their last part counts, so `password`,
`spring.datasource.password` and `PASSWORD` all match. A name is matched in a field, in an MDC
entry, and in text such as `password=x`, `"token":"x"`, `'token': 'x'`, escaped JSON
(`\"token\":\"x\"`), `?api_key=x&`, `password%3Dx` or Lombok's `User(email=x, …)`. An unquoted value in text runs until the next key, a comma, `;`, `&`, a closing
bracket or the end of the line. The values of `Cookie`, `Set-Cookie` and `Authorization` run to the
end of the line, so every cookie in the header is masked. A JSON array or object under a sensitive key,
as in `"token":[...]`, is masked up to its closing bracket. A card number next to another number, as
in `4111 1111 1111 1111 43`, is still found. Digits split by dots or underscores outside a card
layout, such as the timestamp `2026.10.29.07.39.22.124`, the file name
`export_20261029_073922_124.csv` or the two sides of a decimal point, are not read as one card
number; only a part that alone holds 13 to 19 digits and passes the Luhn check is masked. Card numbers,
social security numbers, email addresses, IBANs and phone numbers are all looked for in the same
text, and two that overlap, such as digits inside an email address, are masked as one.

Every value is also cut to `stratum.logging.max-field-length` (16KB by default) and ends with
`...[truncated]` when it was longer. The cut drops the word it falls in and any digits just before
it, so a token or card number that crosses the limit is never left half visible. If masking a value fails, `[REDACTION_FAILED]` is written
instead; the value is never written as it was. That includes an `extra-patterns` expression that
overflows the stack on long text. A `toString()` that returns `null` is written as `null`.

> [!IMPORTANT]
> Redaction is a safety net, not a licence to log personal data or secrets. A name or address with
> no telltale key cannot be found. Keep secrets out of `toString()`: mark them with Lombok's
> `@ToString.Exclude`, or leave them out of a record's `toString()`.

The `text` format writes plain lines that are **not** redacted. It is meant for your laptop. Unless
every active profile is `local` or `test`, the `text` format logs a warning at startup.

A log file, written when you set `logging.file.name`, gets the same format and the same redaction
as the console.

## Properties

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `stratum.logging.enabled` | boolean | `false` | Switches the whole starter on. Until it is `true`, the starter sets up nothing. |
| `stratum.logging.format` | `ecs`, `logstash`, `gelf`, `text` | `ecs` | Console log format. `text` is plain and not redacted; the others are JSON. |
| `stratum.logging.max-field-length` | data size, `1KB` to `1MB` | `16KB` | The longest value written, also used as the stack trace limit. Outside the range, startup stops with `SX-CORE-1003`, which names the rule `stratum.logging.max-field-length-in-range`. |
| `stratum.logging.redaction.extra-keys` | list | *(empty)* | More field and key names whose values are masked in full, added to the built-in names. |
| `stratum.logging.redaction.extra-patterns` | list of regex | *(empty)* | More patterns whose matches are masked in full. An invalid pattern stops startup with `SX-LOGGING-1002`; the message gives its position in the list, never the pattern. |

The shared [`stratum.environment`](../../docs/configuration/reference.md#shared-properties) sets
`service.environment`.

Spring Boot properties this starter sets by default (your values win):

| Spring Boot property | Stratum default |
| --- | --- |
| `logging.structured.format.console` | value of `stratum.logging.format` (unset when `text`) |
| `logging.structured.format.file` | value of `stratum.logging.format` (unset when `text`) |
| `logging.structured.ecs.service.environment` | value of `stratum.environment` |
| `logging.structured.json.stacktrace.max-length` | value of `stratum.logging.max-field-length`, in bytes |
| `logging.structured.json.stacktrace.root` | `first` |

## Examples

Production, with the service's own sensitive names and an employee-ID pattern:

```yaml
stratum:
  environment: prod
  logging:
    enabled: true
    redaction:
      extra-keys:
        - badge-no
        - member-id
      extra-patterns:
        - '\bEMP-\d{6}\b'
```

```properties
stratum.environment=prod
stratum.logging.enabled=true
stratum.logging.redaction.extra-keys=badge-no,member-id
stratum.logging.redaction.extra-patterns[0]=\\bEMP-\\d{6}\\b
```

> [!NOTE]
> In `.properties` files a backslash must be doubled (`\\d`). In YAML, single-quote the pattern
> and write each backslash once.

Readable logs on your laptop, in `application-local.yml` or `application-local.properties`:

```yaml
stratum:
  logging:
    format: text
```

```properties
stratum.logging.format=text
```

Add a fixed field to every line, such as the team that owns the service, with Spring Boot's own
property:

```yaml
logging:
  structured:
    json:
      add:
        team: payments
```

```properties
logging.structured.json.add.team=payments
```

Add a field to every line of one piece of work with the MDC:

```java
try (var ignored = MDC.putCloseable("tenant_id", tenantId)) {
  orderService.place(order);
}
```

## Configure in Java

Not possible for this module. Logging starts before Spring creates any bean, so a
`StratumPropertiesCustomizer<LoggingProperties>` bean would come too late to change it. Set these
properties in `application.properties`, `application.yml`, environment variables or command-line
arguments.

## Change log levels at runtime

Log levels are set with Spring Boot's `logging.level.*` properties. To change one without a
redeploy, use Spring Boot Actuator's `loggers` endpoint. It is not exposed over HTTP by default;
expose it only on the management port, behind authentication:

```properties
management.server.port=8081
management.endpoints.web.exposure.include=health,loggers
```

```yaml
management:
  server:
    port: 8081
  endpoints:
    web:
      exposure:
        include: health,loggers
```

```bash
curl -X POST -H 'Content-Type: application/json' -d '{"configuredLevel":"DEBUG"}' \
  http://localhost:8081/actuator/loggers/com.acme.orders
```

A Stratum endpoint that switches DEBUG on for a limited time, and back off by itself, is
[planned](#planned).

## Customize

- **Different JSON fields:** Spring Boot's `StructuredLogFormatter` lets you write your own format;
  set `logging.structured.format.console` to its class name. Prefer the standard format so logs
  stay searchable across services.

  > [!WARNING]
  > Your own formatter is **not** redacted unless its constructor takes a
  > `StructuredLoggingJsonMembersCustomizer` and applies it to the members it writes. Spring Boot
  > passes Stratum's redaction in only through that parameter.
- **Your own Logback file:** Stratum ships no Logback XML, so it never clashes with yours. Most
  needs, such as levels, a log file or the format, are met with `logging.*` properties. If you need
  a `logback-spring.xml`, for example for an extra appender, start from Spring Boot's structured
  appender so that every line is still JSON and still redacted:

  ```xml
  <configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
    <include resource="org/springframework/boot/logging/logback/structured-console-appender.xml"/>
    <root level="INFO">
      <appender-ref ref="CONSOLE"/>
    </root>
    <logger name="com.acme.orders" level="DEBUG"/>
  </configuration>
  ```

  For a log file, also include `structured-file-appender.xml` and add `<appender-ref ref="FILE"/>`.
  Redaction applies to any appender that uses Spring Boot's `StructuredLogEncoder`; an appender
  with a pattern encoder writes plain, unredacted text.

## Switch it on or off

`stratum.logging.enabled=true` switches the starter on. Leaving it unset is the same as `false`,
which keeps Spring Boot's default plain-text logging, with no checks and no redaction.

## Planned

| Feature | What it adds |
| --- | --- |
| Workflows | `@Workflow` on a method, or `Workflows.start` without AOP, names a piece of work; `workflow`, `root_workflow` and an optional `workflow_id` go on every line, and errors carry `error.code` |
| Context hand-off | The same fields, trace and correlation IDs follow work into `@Async`, executors, `@Scheduled` jobs and Reactor, and across services |
| Management endpoint | Secured, timed switches for DEBUG on one logger, timing lines for one workflow, and unmasking personal data for a short time, each switching back by itself |
| Trace and correlation IDs | `trace.id`, `span.id` and `correlation_id`, with the observability and web starters |
| Body logging on demand | Request and response bodies in the logs when you need them, planned for the web and HTTP client starters rather than this one |

## Troubleshooting

| Symptom | Likely cause and fix |
| --- | --- |
| Startup stops with `SX-LOGGING-1001` | Set `spring.application.name`. |
| Startup stops with `SX-LOGGING-1002` | An `extra-patterns` entry is not a valid regular expression; the message gives its position, never the pattern. |
| Logs are plain text in production | `stratum.logging.enabled` is not `true`, or `stratum.logging.format=text` is set somewhere. Look for the startup warning. |
| A secret still appears in logs | Add its key to `extra-keys` or its shape to `extra-patterns`, then rotate the secret. Report the format so it can be added to the built-in list. |
| A value shows `[REDACTION_FAILED]` | Masking that value failed, so it was hidden. Report the log call so the cause can be fixed. |
| Duplicate log lines | Your service has its own `logback-spring.xml` adding a second appender. |
