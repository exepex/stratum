# Logging: `stratum-starter-logging`

**Status: Partly built.** Structured JSON logs in one format across every service, with the service's
name and environment on every line, and secrets and personal data masked before anything is written.
Named [workflows](#workflows) put their name on every line they write, and are timed and traced when
the service has observability. They follow their work into `@Async` methods, executors, futures
and Reactor.

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
  properties and `spring.task.execution.propagate-context`, listed [below](#properties). A value
  you set in a configuration file, an environment variable, a system property or a command-line
  argument wins over Stratum's.
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
| `error.code` | The code of a `StratumException`, such as `SX-CORE-1003` or your own `PAY-0001`, when it is the exception you log or one of its causes ([error codes](../../docs/guides/error-codes.md)). Search and count errors by this field. In GELF it is `_error_code`; when the console and the file use different formats, the console format decides the name. |
| `workflow`, `root_workflow`, `workflow_id` | The [workflow](#workflows) the line was written in |
| Your own fields | Key-value pairs (`addKeyValue`) and MDC entries, at the top level |

Elastic reads ECS as it is; Grafana Loki and Datadog parse any JSON line. If your platform needs
another layout, set `stratum.logging.format` to `logstash` or `gelf`.

## Workflows

A workflow is a named piece of business work, such as checking out an order. Name it once, and
every log line written while it runs carries its name, so a dashboard can show the load and the
failures of each workflow in every service.

With AspectJ on the classpath, put `@Workflow` on a method of a Spring bean:

```java
@Service
class CheckoutService {

  @Workflow("ORDER_CHECKOUT")
  public Receipt checkOut(Order order) {
    log.info("Order checked out"); // carries "workflow":"ORDER_CHECKOUT"
    return paymentService.pay(order); // its own @Workflow("PAYMENT") is nested inside
  }
}
```

AspectJ is the library Spring uses to run code around annotated methods. Add it with Spring Boot's
starter; Stratum does not bring it:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-aspectj</artifactId>
</dependency>
```

Without AspectJ, `@Workflow` is ignored. Spring also skips it on a private method, and when a
method calls another method of the same class. In those cases, and to give a workflow an ID such
as an order number, inject `Workflows` and run the work through it:

```java
@Service
@RequiredArgsConstructor
class RefundService {

  private final Workflows workflows;

  void refund(Order order) {
    workflows.run("ORDER_REFUND", order.number(), () -> sendRefund(order));
  }

  Receipt price(Order order) {
    return workflows.call("ORDER_PRICING", () -> priceList.price(order));
  }
}
```

`run` is for work that returns nothing and `call` for work that returns a value. Both pass on any
exception the work throws, unchanged.

| Field | Value |
| --- | --- |
| `workflow` | The name of the workflow the line was written in: the innermost one when workflows are nested |
| `root_workflow` | The name of the outermost workflow, so `ORDER_CHECKOUT` failures can be counted even when they happen inside `PAYMENT` |
| `workflow_id` | The ID given to `Workflows`, kept by the workflows nested inside it. Left out when none was given, or when it is not 1 to 128 letters, digits, `-`, `_`, `.` or `:`, so it cannot forge a log line |

When the workflow ends, the outer workflow's fields come back, or the fields are removed. A name is
a fixed word of 1 to 128 letters, digits, `-`, `_`, `.` or `:`, such as `ORDER_CHECKOUT`; any other
name throws an `IllegalArgumentException`. Never build a name from data, such as an order number:
that is what the ID is for.

When the service has an `ObservationRegistry`, as with the
[observability starter](../stratum-starter-observability/README.md), each workflow is also timed and
traced, with no extra code:

- A timer named `stratum.workflow`, tagged with `workflow` and `root_workflow`, counts the calls,
  the failures (tag `error`) and their durations. The observability starter stores durations as
  histograms, so a dashboard reads any percentile per workflow, for one pod or for all.
- A span named after the workflow, with the ID as the `workflow_id` attribute, so a trace can be
  found by an order number. A failure marks the span as failed. Log lines mask an ID that looks
  like a card or national ID, but the span gets it as it is: use an order number or a similar
  business ID, never personal data.

Stratum records only how long a workflow took. It never labels a workflow slow or fast; your
dashboards and alerts decide that. Without a registry, workflows only set the log fields.

### Async and reactive work

A workflow ends when its work has finished, even when that is after the call returns:

- Work that returns a `CompletableFuture` or another `CompletionStage` is timed and traced until
  the stage completes, and its failure is recorded.
- A `@Workflow` method that returns a `Mono` or `Flux` only builds the work. The workflow runs
  each time the result is subscribed, until it completes, fails or is cancelled. Lines written
  while the method builds it are outside the workflow, and so is an exception it throws instead of
  returning one: it passes on unchanged, but is not timed. The class's method decides, so this
  holds when an interface declares `Publisher` or a type variable and the class returns `Mono` or
  `Flux`. A method that returns only `Publisher` runs as other work does.

Without `@Workflow`, run a `Mono` or `Flux` through `ReactiveWorkflows`, a bean that exists when
Reactor is on the classpath. `flux` does the same for a `Flux`:

```java
@Service
@RequiredArgsConstructor
class PaymentService {

  private final ReactiveWorkflows reactiveWorkflows;
  private final PaymentGateway gateway;

  Mono<Receipt> pay(Order order) {
    return reactiveWorkflows.mono("PAYMENT", order.number(), gateway.charge(order));
  }
}
```

The fields follow the work to other threads:

- **`@Async` methods and Spring Boot's executors.** The starter sets Spring Boot's
  `spring.task.execution.propagate-context=true`, so work on Spring Boot's task executor and
  scheduler keeps the fields, and the trace and correlation IDs when the service has
  observability. An executor you create yourself does too when you build it with Spring Boot's
  `ThreadPoolTaskExecutorBuilder` bean. Other threads, such as those of
  `CompletableFuture.supplyAsync` without an executor, do not get them.
- **Reactor.** The fields travel in the Reactor context. With Spring Boot's default,
  `spring.reactor.context-propagation=limited`, they are on the lines written in `handle` and `tap`
  operators. Set it to `auto` to have them on every operator's lines, at a small cost per operator.
- **`@Scheduled` jobs.** Each run starts outside any workflow, as its own piece of work.

The fields stay inside the service. With the observability starter, the trace ID reaches the
services it calls; sending the workflow fields too is [planned](#planned). The observability
starter also puts `traceId`, `spanId` and `correlationId` on the lines written inside a traced
operation, with the same limits for Reactor and the service's own threads
([observability](../stratum-starter-observability/README.md#threads-and-reactive-code)).

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
| `stratum.logging.stack-trace-starts-with-root-cause` | boolean | `true` | Whether a stack trace starts with the root cause, the exception that started the failure, so a stack trace cut at `max-field-length` still shows why it failed. `false` starts with the outermost exception, the one your code caught, as Java prints it. |
| `stratum.logging.redaction.extra-keys` | list | *(empty)* | More field and key names whose values are masked in full, added to the built-in names. |
| `stratum.logging.redaction.extra-patterns` | list of regex | *(empty)* | More patterns whose matches are masked in full. An invalid pattern stops startup with `SX-LOGGING-1002`; the message gives its position in the list, never the pattern. |

The shared [`stratum.environment`](../../docs/configuration/reference.md#shared-properties) sets
`service.environment`, and the shared `stratum.propagate-context-to-async-threads` (default `true`) decides whether
`@Async` methods and Spring Boot's executors keep the workflow fields.

Spring Boot properties this starter sets by default. A value you set wins, from any file, the
environment, the command line or Java default properties:

| Spring Boot property | Stratum default |
| --- | --- |
| `logging.structured.format.console` | value of `stratum.logging.format` (unset when `text`) |
| `logging.structured.format.file` | value of `stratum.logging.format` (unset when `text`) |
| `logging.structured.ecs.service.environment` | value of `stratum.environment` |
| `logging.structured.json.stacktrace.max-length` | value of `stratum.logging.max-field-length`, in bytes |
| `logging.structured.json.stacktrace.root` | `first` (root cause first), or `last` when `stratum.logging.stack-trace-starts-with-root-cause=false` |
| `spring.task.execution.propagate-context` | value of `stratum.propagate-context-to-async-threads` |

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

Logging starts before Spring creates any bean, so the Java configuration is a class rather than a
bean. It receives the settings from your files, with the defaults for anything unset, and its
values win:

```java
package com.acme.orders.config;

public class OrdersLoggingSettings implements LoggingPropertiesCustomizer {

  @Override
  public LoggingProperties customize(LoggingProperties properties) {
    return properties.toBuilder()
        .format(LogFormat.GELF)
        .maxFieldLength(DataSize.ofKilobytes(32))
        .redaction(properties.redaction().toBuilder().extraKeys(List.of("iban-hash")).build())
        .build();
  }
}
```

List the class in `src/main/resources/META-INF/spring.factories`:

```properties
com.exepex.stratum.logging.LoggingPropertiesCustomizer=com.acme.orders.config.OrdersLoggingSettings
```

It needs a public no-argument constructor and may run more than once at startup. Declared as a
`@Bean` instead, it stops startup with `SX-CORE-1004`. To set the switch in Java, see
[default properties](../../docs/configuration/overriding-defaults.md#switches-shared-properties-and-spring-boot-properties-in-java).

## Change log levels at runtime

Use Spring Boot Actuator's `loggers` endpoint, exposed only on the management port and behind
authentication, as [Troubleshooting](../../docs/guides/troubleshooting.md#change-a-log-level-without-a-redeploy)
shows. A Stratum endpoint that switches DEBUG on for a limited time is [planned](#planned).

## Customize

- **Your own workflow handling:** declare a `Workflows`, `ReactiveWorkflows` or `WorkflowAspect`
  bean; Stratum's then backs off.
- **Not replaceable:** the startup checks, the redaction and the `error.code` field run before any
  bean exists, so only `stratum.logging.enabled` turns them off.
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
| Workflows across services | The workflow fields sent to the service's own downstream services, planned with the HTTP client starter |
| Management endpoint | Secured, timed switches for DEBUG on one logger, timing lines for one workflow, and unmasking personal data for a short time, each switching back by itself |
| ECS names for trace IDs | Today's `traceId`, `spanId` and `correlationId` fields renamed to ECS's `trace.id`, `span.id` and `correlation_id`, with the observability and web starters |
| Body logging on demand | Request and response bodies in the logs when you need them, planned for the web and HTTP client starters rather than this one |

## Troubleshooting

| Symptom | Likely cause and fix |
| --- | --- |
| Startup stops with `SX-LOGGING-1001` | Set `spring.application.name`. |
| Startup stops with `SX-CORE-1004` | A logging customizer is a `@Bean`; list its class in `META-INF/spring.factories` instead. |
| Startup stops with `SX-LOGGING-1002` | An `extra-patterns` entry is not a valid regular expression; the message gives its position, never the pattern. |
| Logs are plain text in production | `stratum.logging.enabled` is not `true`, or `stratum.logging.format=text` is set somewhere. Look for the startup warning. |
| A secret still appears in logs | Add its key to `extra-keys` or its shape to `extra-patterns`, then rotate the secret. Report the format so it can be added to the built-in list. |
| A value shows `[REDACTION_FAILED]` | Masking that value failed, so it was hidden. Report the log call so the cause can be fixed. |
| Duplicate log lines | Your service has its own `logback-spring.xml` adding a second appender. |
