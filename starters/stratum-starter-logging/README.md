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
    log.atInfo().addKeyValue("orderId", order.id()).log("Order placed");
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

Every bundle starter (`stratum-starter-microservice`, `-reactive-microservice`, `-web-ui`, `-batch`
and `-cli`) includes it, so add it on its own only when your service uses no bundle. The version comes from
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

- It ships no Logback XML. With the module on, your own Logback file (`logback*.xml`), `logging.config`
  or a formatter class of your own stops startup with `SX-LOGGING-1003`: Stratum masks only its own formats.
- At startup, before any bean exists, it sets a few of Spring Boot's `logging.structured.*`
  properties and `spring.task.execution.propagate-context`, listed [below](#properties). A value
  you set in a configuration file, an environment variable, a system property or a command-line
  argument wins over Stratum's.
- It adds redaction to Spring Boot's `ecs`, `logstash` and `gelf` formats, and writes the `text`
  format itself through Spring Boot's structured logging, so every line in every format is masked,
  on the console and in a log file, with Logback or Log4j2 ([What is masked](#what-is-masked)).

Everything else, such as `logging.level.*`, `logging.file.name` and `logging.structured.json.add.*`,
works as the Spring Boot documentation describes.

## What you get

The line above comes out as one JSON object in the
[Elastic Common Schema (ECS)](https://www.elastic.co/guide/en/ecs/current/), shown formatted here:

```json
{
  "@timestamp": "2026-10-28T21:15:04.512Z",
  "log": { "level": "INFO", "logger": "com.acme.orders.OrderService" },
  "process": { "pid": 4120, "thread": { "name": "tomcat-handler-12" } },
  "service": { "name": "order-service", "environment": "prod", "node": { "name": "k3x9q2m7ab" } },
  "message": "Order placed",
  "orderId": "8f14e45f",
  "ecs": { "version": "8.11" }
}
```

| Field | Where it comes from |
| --- | --- |
| `service.name` | `spring.application.name` (required) |
| `service.environment` | [`stratum.environment`](../../docs/configuration/reference.md#shared-properties), when set |
| `service.version` | Your jar's manifest, when it has a version |
| `service.node.name` | The [instance ID](../../docs/configuration/reference.md#instance-id) of this running copy; `instanceId` in Logstash, `_instanceId` in GELF |
| `error.type`, `error.message`, `error.stack_trace` | The exception you pass to the log call. The root cause comes first, so a cut stack trace still shows why it failed. |
| `error.code` | The code of a `StratumException`, or of an exception that implements [`FailureWithErrorCode`](../../docs/guides/error-codes.md#existing-exceptions-a-migration-path), such as `SX-CORE-1003` or your own `PAY-0001`, when it is the exception you log or one of its causes ([error codes](../../docs/guides/error-codes.md)). Search and count errors by this field. It is a separate key next to the `error` group, not inside it: Elasticsearch merges it into `error`, while Loki and Datadog see the literal `error.code`. In GELF it is `_error_code`; when the console and the file use different formats, the console format decides the name. |
| `workflow`, `rootWorkflow`, `workflowId` | The [workflow](#workflows) the line was written in |
| Your own fields | Key-value pairs (`addKeyValue`) and MDC entries, at the top level |

Elastic reads ECS as it is; Grafana Loki and Datadog parse any JSON line. If your platform needs
another layout, set `stratum.logging.format` to `logstash` or `gelf`.

## Workflows

A workflow is a named piece of business work, such as checking out an order. Put `@Workflow` on a
method, or run the work through the `Workflows` or `ReactiveWorkflows` bean, and every log line
written while it runs carries its name and business ID, so a dashboard can show the load and the
failures of each workflow in every service. The fields follow the work to `@Async` methods, Spring
Boot's executors and Reactor's thread changes. [Workflows](../../docs/modules/logging-workflows.md)
shows each way to start one, the fields and timings it adds, and how async and reactive work ends.

## Redaction

Redaction runs on every value of a JSON line: the message, the exception's message and stack
trace, MDC entries and key-value pairs. A key-value pair whose value is a number or an object, such
as `addKeyValue("customer", customer)`, is masked through the value's `toString()`, which is read
once; a list, map or array is masked element by element and keeps only its first elements
([lists, maps and arrays](#lists-maps-and-arrays)). Ready-made JSON, such as a `WritableJson`, is masked through its text;
if anything in it is masked, it is written as a JSON string. It cannot be switched off.

> [!IMPORTANT]
> Redaction is a safety net, not a licence to log personal data or secrets. A name, address, phone
> number or social security number with no telltale key cannot be found: in text, Stratum masks only
> what it can confirm, such as a card number that passes the Luhn check. Keep secrets out of
> `toString()`: mark them with Lombok's `@ToString.Exclude`, or leave them out of a record's
> `toString()`.

| What | Found by | Written as |
| --- | --- | --- |
| Credentials: any name that contains `password`, `passwd`, `passphrase`, `passcode`, `secret`, `token`, `apikey`, `credential`, `privatekey`, `sessionid`, `cookie` or `authorization`, such as `dbPassword`, `aws_secret_access_key`, `X-CSRF-Token` or `JSESSIONID`; and any name with the word `pwd`, `pass`, `auth`, `pin`, `otp`, `cvv`, `cvc`, `jwt`, `access key`, `encryption key` or `signing key`, such as `dbPwd`, `card_cvv` or `accessKey` | Field or key name | `****` |
| Payment cards, accounts and national IDs: `card_number`, `pan`, `account_number`, `ssn`, `national_id`, `tax_id`, `passport` | Field or key name | `****1234` (last four kept) |
| Card numbers in text | 13 to 19 digits that pass the Luhn check, in groups split by a space or `-`; or split by two spaces, `.`, `/` or `_` used between every group of a card layout (4-4-4-4, 4-4-4-4-3, or American Express's 4-6-5) | `****1111` |
| JSON Web Tokens | `eyJ…` with two or three parts | `****` |
| `Basic`, `Bearer`, `Digest` and `Negotiate` credentials; Digest's `name="value"` list runs to the end of the line | The scheme name | `Bearer ****` |
| A password in a URL, with or without a user | `https://user:pass@host`, `redis://:pass@host`; a password with a raw `@` is masked up to the last `@` before the host, never past a space, `/`, `?`, `#`, a quote, `<` or `>`. When another `@` follows a `,`, `;`, `&` or `=`, it is unclear where the password ends, so the rest of the line is masked | `https://****@host`, `redis://****@host` |
| Names, dates of birth and addresses: `first_name`, `last_name`, `full_name`, `username`, `date_of_birth`, `address` and similar | Field or key name | `****` |
| Email, phone and IBAN fields | Field or key name | `****` plus last four |
| Email addresses in text | A valid address | `****.com` |
| IBANs in text | A valid IBAN (checksum checked) | `****5432` |
| Your own keys and patterns | [`extra-keys` and `extra-patterns`](#properties) | `****` |

A name is split into words at capitals, digits, `_`, `-` and `.`, and matches when one word, or a
few words written together, is a listed word: `password`, `spring.datasource.password`,
`PASSWORD`, `userPin` and `X-Api-Key` all match, while `shipping` and `passenger` do not. A name
whose last word only describes the value (`count`, `type`, `length`, `size`, `expiry`, `expires`,
`enabled`, `required`, `consent` or `rotated`), such as `tokenCount`, is not masked. A name with no
listed word is not found, whatever it holds: add your own to `extra-keys`, which match the whole
name, compared without case, `_` or `-` on its last dot-separated part. A name is matched in a field, in an MDC
entry, and in text such as `password=x`, `"token":"x"`, `'token': 'x'`, escaped JSON
(`\"token\":\"x\"`), `?api_key=x&`, `password%3Dx` or Lombok's `User(email=x, …)`. An unquoted value in text runs until the next key, a comma, `;`, `&`, a closing
bracket or the end of the line. The values of `Cookie`, `Set-Cookie` and `Authorization` run to the
end of the line, so every cookie in the header is masked. A JSON array or object under a sensitive key,
as in `"token":[...]`, is masked up to its closing bracket. A card number next to another number, as
in `4111 1111 1111 1111 43`, is still found. Digits split by dots or underscores outside a card
layout, such as the timestamp `2026.10.29.07.39.22.124`, the file name
`export_20261029_073922_124.csv` or the two sides of a decimal point, are not read as one card
number; only a part that alone holds 13 to 19 digits and passes the Luhn check is masked. Card numbers,
email addresses and IBANs are all looked for in the same text, and two that overlap, such as digits
inside an email address, are masked as one.

Fields that Spring Boot, Stratum or the web module fill and that never hold personal data, such as
`@timestamp`, `log.level`, `service.name`, `error.type`, `traceId`, `httpRoute` or `userAgent`, are
written as they are, unless you name them in `extra-keys` or `extra-patterns`: your own rules
always win. Each field is matched by its whole path in the format Spring Boot writes, so your own
field `service.contact` is still masked. Every other field, including your own MDC entries
and key-value pairs, is masked by name and then searched. When the console and the log file use
different formats, only fields known in both are written as they are. In a stack trace, only the
lines that are wholly a frame (`at com.example.Orders.place(Orders.java:42)`) or an omission
(`... 12 more`) are written as they are; the exception's message and the `Caused by:`,
`Suppressed:` and `Wrapped by:` lines are searched.

Every value is also cut to `stratum.logging.max-field-length` (16KB by default) and ends with
`...[truncated]` when it was longer. The cut drops the word it falls in and any digits just before
it, so a token or card number that crosses the limit is never left half visible. If anything fails
while masking a value, such as a `toString()` that throws or a list changed while it is read,
`[REDACTION_FAILED]` is written for that value instead, and the rest of the line is still written;
the value is never written as it was. That includes an `extra-patterns` expression that
overflows the stack on long text. A `toString()` that returns `null` is written as `null`.

### Lists, maps and arrays

A list, set, map or array logged as a key-value pair keeps its first `stratum.logging.max-elements`
elements (10 by default), and one marker counts the rest. Each list, map or array inside it is
limited the same way. The elements after the limit are never read, so a huge list costs no more than
a short one:

```java
log.atInfo().addKeyValue("orderIds", orderIds).log("Batch paid"); // 200 order IDs
```

```json
"orderIds": ["A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9", "A10", "...[190 more]"]
```

A map ends with the entry `"...": "[190 more]"`, and the `text` format writes
`orderIds=[A1, A2, …, A10, ...[190 more]]` and `{A1=PAID, …, ...=[190 more]}`. To log more of a list,
turn it into one string, such as `String.join(",", orderIds)`: a string is only cut at
`max-field-length`. The limit applies to key-value pairs; MDC values are always strings, and a
list in the message, as in `log.info("ids {}", orderIds)`, is turned into text by SLF4J before
Stratum sees it, so it is only cut at `max-field-length`. You may lower the limit, never raise it
above 20.

A log file, written when you set `logging.file.name`, gets the same format and the same redaction
as the console.

## What is masked

Stratum's formats are always masked, `text` included, in every profile; no setting turns masking
off. Only Stratum's formats are masked. Do not use your own encoder, layout, pattern, formatter class or
logging file: with the module on, startup stops with SX-LOGGING-1003.

Outputs that Stratum does not write are not masked by it:

| Output | Masked by Stratum |
| --- | --- |
| The console and the log file, in any `stratum.logging.format`, with Logback or Log4j2 | Yes |
| Your own appender, encoder, layout, pattern, formatter class, Logback file or `logging.config` | Not allowed: startup stops with SX-LOGGING-1003 |
| The OpenTelemetry Java agent or OpenTelemetry's log appender | No: they read each event before any format. Mask in the OpenTelemetry Collector instead |
| A logging backend other than Logback or Log4j2 | Not allowed: startup stops |
| A Log4j2 configuration file | Not checked: Stratum cannot see it; do not add one |

The `text` format writes one line per event for people to read: time, level, thread, logger and
message, then each MDC entry and key-value pair as `key=value`. A stack trace follows on indented
lines, so no line of it can pass for a log line of its own. Line breaks inside a value are shown as
`\r` and `\n`, so one event stays one line:

```text
2026-11-02T10:15:30.123Z  INFO [main] com.acme.orders.OrderService : login password=**** api_key=****
```

Spring Boot's structured logging writes it, so `logging.pattern.*` and console colours do not apply
to it. `logging.charset.*` and `logging.structured.json.stacktrace.*` do; the other
`logging.structured.json.*` keys, such as `include`, `exclude`, `rename`, `add` and `context`, do
not.

## Properties

Every key this starter reads or gives a default. Stratum's own keys come first. For a Spring Boot
key, Stratum only sets a default: where Spring Boot has a key, Stratum has none of its own, and a
value you set wins, from any file, the environment, the command line or Java default properties. The one
exception: the two `logging.structured.format.*` keys accept only Stratum's formats.

| Property | Type | Stratum's default | Spring Boot's default | Description |
| --- | --- | --- | --- | --- |
| `stratum.logging.enabled` | boolean | `false` | no such key | Switches the whole starter on. Until it is `true`, the starter sets up nothing. |
| `stratum.logging.format` | `ecs`, `logstash`, `gelf`, `text` | `ecs` | no such key | The format of the console and of the log file. `text` is one masked line of text per event; the others are JSON. |
| `stratum.logging.max-field-length` | data size, `1KB` to `1MB` | `16KB` | no such key | The longest value written, also used as the stack trace limit. It counts characters; for English text, 16KB is 16,384 characters. Outside the range, startup stops with `SX-CORE-1003`, which names the rule `stratum.logging.max-field-length-in-range`. |
| `stratum.logging.max-elements` | integer, `1` to `20` | `10` | no such key | The most elements kept of each logged list, map or array, nested ones too; one marker such as `...[190 more]` counts the rest ([lists, maps and arrays](#lists-maps-and-arrays)). Outside the range, startup stops with `SX-CORE-1003`, which names the rule, such as `stratum.logging.max-elements breaks @Max(value=20)`. |
| `stratum.logging.redaction.extra-keys` | list | *(empty)* | no such key | More field and key names whose values are masked in full, added to the built-in names. |
| `stratum.logging.redaction.extra-patterns` | list of regex | *(empty)* | no such key | More patterns whose matches are masked in full. An invalid pattern stops startup with `SX-LOGGING-1002`; the message gives its position in the list, never the pattern. |
| `logging.structured.format.console` | `ecs`, `logstash`, `gelf`, or Stratum's text class; any other value, or an empty one, stops startup with SX-LOGGING-1003 | value of `stratum.logging.format`; for `text`, Stratum's masked text class for Logback or Log4j2 | unset, so plain text | The console's format. |
| `logging.structured.format.file` | `ecs`, `logstash`, `gelf`, or Stratum's text class; any other value, or an empty one, stops startup with SX-LOGGING-1003 | value of `stratum.logging.format`; for `text`, Stratum's masked text class for Logback or Log4j2 | unset, so plain text | The log file's format. |
| `logging.structured.ecs.service.environment` | string | value of `stratum.environment` | unset | `service.environment` in each line. |
| `logging.structured.ecs.service.node-name` | string | the [instance ID](../../docs/configuration/reference.md#instance-id), when the console or the file writes ECS | unset, so an empty `node` | `service.node.name` in each ECS line. |
| `logging.structured.json.add[instanceId]`, `logging.structured.json.add[_instanceId]` | string | the instance ID, under `instanceId` when the console or the file writes Logstash, or `_instanceId` for GELF; the console's format decides when they differ | unset | The instance ID as a field of its own in Logstash or GELF lines. Spring Boot adds it to every JSON output, so an ECS console next to a Logstash file carries it too. Your own `json.add` fields stay. |
| `logging.structured.json.stacktrace.max-length` | integer | value of `stratum.logging.max-field-length` plus 1KB (17KB by default), so that Stratum's own cut at `max-field-length`, which never leaves half a value, ends a long stack trace | unset, so no limit | The longest stack trace Spring Boot prints before Stratum cuts it, in every format, `text` included. If you set it, keep it above `max-field-length`: a lower value lets Spring Boot cut a value in half. |
| `logging.structured.json.stacktrace.root` | `first` or `last` | `first`: the root cause first, so a cut stack trace still shows why it failed | unset, so as Java prints it | `last` starts with the exception your code caught, as Java prints it. It applies to `text` too. |
| `spring.task.execution.propagate-context` | boolean | `true` | `false` | Whether `@Async` methods and Spring Boot's executors keep the workflow fields. |
| `spring.reactor.context-propagation` | `limited` or `auto` | `auto` | `limited` | Whether every Reactor operator keeps them, not only `handle` and `tap`. |

The shared [`stratum.environment`](../../docs/configuration/reference.md#shared-properties) sets
`service.environment`. You choose the instance ID with Spring Boot's
[instance ID key](../../docs/configuration/reference.md#instance-id). The shared boolean
`stratum.context-propagation.executor-beans` (default `true`) decides whether the pool beans you
declare keep the workflow fields.

## Examples

Production, with the service's own sensitive names, an employee-ID pattern and five elements of
each list:

```yaml
stratum:
  environment: prod
  logging:
    enabled: true
    max-elements: 5
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
stratum.logging.max-elements=5
stratum.logging.redaction.extra-keys=badge-no,member-id
stratum.logging.redaction.extra-patterns[0]=\\bEMP-\\d{6}\\b
```

> [!NOTE]
> In `.properties` files a backslash must be doubled (`\\d`). In YAML, single-quote the pattern
> and write each backslash once.

Readable logs on your laptop, still masked, in `application-local.yml` or
`application-local.properties`:

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

Add a field to every line of one piece of work with the MDC. It follows the work to `@Async`
methods, Spring Boot's executors, event listeners and Reactor's thread changes; `ReactiveMdcFields`
also covers reactive steps outside Reactor ([across threads](../../docs/guides/context-across-threads.md)):

```java
try (var ignored = MDC.putCloseable("tenantId", tenantId)) {
  orderService.place(order);
}
```

In a servlet service, add `stratum-starter-webmvc` and set `stratum.web.enabled=true` so each
request ends with a clean log context, or remove your own fields with `MDC.putCloseable`.

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
        .maxElements(5)
        .redaction(properties.redaction().toBuilder().extraKeys(List.of("iban-hash")).build())
        .build();
  }
}
```

List the class in `src/main/resources/META-INF/spring.factories`:

```properties
com.exepex.stratum.logging.LoggingPropertiesCustomizer=com.acme.orders.config.OrdersLoggingSettings
```

It needs a public no-argument constructor and may run more than once at startup, so it must return
the same settings every time and have no side effects. Declared as a
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
- **Not allowed:** your own formatter class, encoder, layout, pattern, Logback file or `logging.config`.
  Stratum could not mask their lines, so startup stops with SX-LOGGING-1003. Set levels, a log file and the
  format with `logging.*` and `stratum.logging.*` properties; in tests, set levels in the test's
  `application.properties`, not in a `logback-test.xml`.

## Switch it on or off

`stratum.logging.enabled=true` switches the starter on. Leaving it unset is the same as `false`,
which keeps Spring Boot's default plain-text logging, with no checks and no redaction.

## Planned

| Feature | What it adds |
| --- | --- |
| Workflows across services | The workflow fields sent to the service's own downstream services, planned with the HTTP client starter |
| Management endpoint | Secured, timed switches for DEBUG on one logger and timing lines for one workflow, each switching back by itself |
| Sensitive data during incidents | Masked values carry a keyed fingerprint, so the same value can be matched without being shown. Unmasking stays off unless the service allows it in configuration; it is turned on through a secured endpoint, with a reason, for 15 minutes by default and never more than 1 day, for the listed fields only. Every use is recorded, and unmasked lines go only to a separate destination, never to the normal log |
| Body logging on demand | Request and response bodies in the logs when you need them, planned for the web and HTTP client starters rather than this one |

## Troubleshooting

| Symptom | Likely cause and fix |
| --- | --- |
| Startup stops with `SX-LOGGING-1001` | Set `spring.application.name`. |
| Startup stops with `SX-CORE-1004` | A logging customizer is a `@Bean`; list its class in `META-INF/spring.factories` instead. |
| Startup stops with `SX-LOGGING-1002` | An `extra-patterns` entry is not a valid regular expression; the message gives its position, never the pattern. |
| Startup stops with `SX-LOGGING-1003` | Remove the file or key the message names, and choose the format with `stratum.logging.format`. |
| Logs are plain, unmasked text | `stratum.logging.enabled` is not `true`. |
| `logging.pattern.*` or console colours have no effect | With the module on, every format, `text` included, is written by Spring Boot's structured logging, which uses no pattern. |
| A value shows `\n` instead of a line break | Stratum shows line breaks inside a value as text, so that one event stays one line. |
| A secret still appears in logs | Add its key to `extra-keys` or its shape to `extra-patterns`, then rotate the secret. Report the format so it can be added to the built-in list. |
| A phone number or SSN in a message is not masked | Text is searched only for values Stratum can confirm. Log it under a field named `phone` or `ssn`, or add your own name to `extra-keys`. |
| `com.google.i18n.phonenumbers` classes are not found after an upgrade | Stratum no longer brings libphonenumber. Declare `com.googlecode.libphonenumber:libphonenumber` in your own POM, with its version. |
| A value shows `[REDACTION_FAILED]` | Masking that value failed, so it was hidden, for example because its `toString()` threw or a list changed while it was read. Fix the `toString()`, or report the log call. |
| A list ends with `...[190 more]` | Only the first `max-elements` elements are written. Log fewer, or join the list into one string ([lists, maps and arrays](#lists-maps-and-arrays)). |
