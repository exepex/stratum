# Error and warning codes

Every error and warning that Stratum reports has a code, such as `SX-CORE-1002`. You can search for
the code in your logs, and look it up on this page to see what it means and how to fix it. Your
service can use the same codes, error objects and exception for its own errors.

[← Documentation home](../README.md)

## What a code looks like

```text
SX-CORE-1002
│  │    └── a four-digit number, unique within the module
│  └─────── the module that reports it, such as CORE or WEB
└────────── SX: the code belongs to Stratum
```

- **`SX-` is reserved for Stratum.** Your service uses its own prefix, such as `PAY-0001` or
  `ORD-0001`. If a service declares a code that starts with `SX-`, Stratum rejects every report
  made from it, so a service can't declare one by mistake. Stratum accepts an `SX-` code only
  from a class in its own package, `com.exepex.stratum`.
- **Each module numbers its own codes.** Errors and warnings share one range of numbers, so a
  number is never used twice in a module.
- **The code does not say how serious the problem is.** Each code has a separate `severity`,
  `ERROR` or `WARNING`, which every report made from it carries.
- **Info messages have no code.** Only errors and warnings need to be looked up.

Each module declares all its codes, errors and warnings together, in one Java enum, such as
`CoreErrorCode`. The severity and description of each code are constants in that enum. No
properties file holds them, so no configuration can change what a Stratum code means.

## The two error objects

Stratum describes every error or warning with two objects. Both are in `stratum-core`, in the
package `com.exepex.stratum.core.error`.

| Object | Who sees it | What it holds |
| --- | --- | --- |
| `ErrorReport` | Your own logs only | Code, severity, message, field details, the trace, span and correlation IDs, and the cause with its stack trace |
| `ExternalError` | Anyone outside the application: an API caller, a command-line user, a batch job's result, a user interface | Code, severity, message, field details, the trace ID and the correlation ID |

`ExternalError` has the same shape in every kind of application. It has no field for a stack
trace, the cause or the span ID, so none of them can leak to a caller, whatever sends it.
`ErrorReport.getExternal()` gives the part of a report that may leave the application.

An `ExternalError` written as JSON looks like this:

```json
{
  "code": "ORD-0001",
  "severity": "ERROR",
  "message": "Not enough stock: SKU-1",
  "details": [
    { "field": "quantity", "message": "must be greater than 0" }
  ],
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "correlationId": "order-42"
}
```

A code's description is fixed text with no placeholders. The message is that description, or the
description, a colon and one detail that the code passes when it reports the problem, such as
`Not enough stock: SKU-1`. The message is shown to callers, so never put a secret, personal data,
a class name or any other internal detail in the detail. Pass an exception as the cause, never in
the detail. Line breaks and other control characters in the detail are replaced with spaces, so a
value from a request cannot forge a log line.
Stratum's own `SX-CORE` errors stop the application at startup and never reach a caller.
`SX-CORE-1005` is a warning that is only logged.

## The trace, span and correlation IDs

You never pass these IDs by hand. A report reads them, when it is made, from the logging context
(SLF4J's MDC) of the current thread:

| ID | Logging context key | Set by | Ties together |
| --- | --- | --- | --- |
| Trace ID | `traceId` | Tracing (Micrometer Tracing) | Every step of one request, across services |
| Span ID | `spanId` | Tracing (Micrometer Tracing) | One operation within the request |
| Correlation ID | `correlationId` | The observability module, while an operation is traced | Every step of one business workflow, such as one order or one batch job, across many requests |

A report keeps an ID only if it is 1 to 128 characters long and holds nothing but letters, digits,
`-`, `_`, `.` and `:`. Any other ID is left out, so that a value sent by a caller cannot forge a log
line or break a response.

With `stratum.observability.enabled=true`, the correlation ID is the `correlationId` entry of the
tracing baggage when one is set, and otherwise the trace ID, which every service in the same trace
shares ([observability module](../../starters/stratum-starter-observability/README.md#correlation-id)).
Baggage is sent on every outgoing call made with Spring's HTTP clients, `WebClient` in reactive
code included, to every host, so never put a secret or personal data in the correlation ID. With
`stratum.web.enabled=true`, the web module also takes the ID from the caller's `X-Correlation-Id`
header and returns it in the response
([web module](../modules/web.md#correlation-id)). **Planned:** the logging module will limit the
baggage to the service's own hosts.

## Using codes in your service

Declare your codes in one enum that implements `ErrorCode`, with your own prefix. Give each code
its severity and a fixed description:

```java
@Getter
@RequiredArgsConstructor
public enum OrderErrorCode implements ErrorCode {

  /** The order asks for more items than are in stock. */
  OUT_OF_STOCK("ORD-0001", Severity.ERROR, "Not enough stock"),

  /** The order was accepted, but it will ship late. */
  DELAYED("ORD-0002", Severity.WARNING, "The order will ship late");

  private final String code;
  private final Severity severity;
  private final String description;
}
```

Throw an error, or log a warning:

```java
throw new StratumException(OrderErrorCode.OUT_OF_STOCK, sku);

var warning = ErrorReport.of(OrderErrorCode.DELAYED, "3 days");
log.warn(warning.getCodeAndMessage());
```

The detail is optional: `new StratumException(code)` and `ErrorReport.of(code)` use the description
alone. So does a detail that is blank or only control characters, so the message never ends in
a dangling colon. `new StratumException(code, detail, cause)` adds the exception behind the failure.

The exception's message starts with the code, such as `ORD-0001: Not enough stock: SKU-1`, and
`exception.getReport()` gives its `ErrorReport`. Log the exception itself, so the log shows its
stack trace and cause:

```java
log.error(exception.getMessage(), exception);
```

With the logging module on, the line then also has the code in its own `error.code` field, which
you can search and count by ([logging module](../../starters/stratum-starter-logging/README.md#what-you-get)).

To add field details, build the report first:

```java
var report =
    ErrorReport.of(OrderErrorCode.OUT_OF_STOCK, sku)
        .withDetails(List.of(new ErrorDetail("quantity", "must be greater than 0")));
throw new StratumException(report);
```

A warning cannot be thrown: `new StratumException(report)` rejects a report whose severity is
`WARNING`.

### Existing exceptions: a migration path

A service that already has its own exceptions can let Stratum answer and log them before it moves
them to `StratumException`. Make each one implement `FailureWithErrorCode`:

```java
public class PaymentDeclinedException extends RuntimeException implements FailureWithErrorCode {

  private final String orderId;

  @Override
  public ErrorCode getErrorCode() {
    return PaymentErrorCode.DECLINED;
  }

  @Override
  public String getErrorDetail() {
    return "order " + orderId; // never a secret or personal data; "" for none
  }
}
```

The web modules then answer it as they answer a `StratumException` with the same code and detail:
the status mapped in `stratum.web.error.statuses`, the same body without `details`, and the same
log line. An `@ResponseStatus` on the exception, or extending `ResponseStatusException`, does not
change this on either stack: the status mapped to the code wins. The logging module writes its code
in `error.code`. A warning code gets `500` and
`SX-WEB-1005`, because a warning is never thrown. New code throws `StratumException`; this
interface is only for moving existing exceptions over time.

## stratum-core

Each of these but `SX-CORE-1005` is an error that stops the application at startup.
`SX-CORE-1005` is a warning, logged once at startup; the application keeps running.

| Code | Meaning | Fix |
| --- | --- | --- |
| `SX-CORE-1001` | A module name given to `@ConditionalOnStratumModule` is not one lower-case, kebab-case word, such as `http-client`. | Rename the module in the annotation. This is a fault in a starter, not in your service's configuration. |
| `SX-CORE-1002` | A `StratumPropertiesCustomizer` is broken. The message names the customizer and says whether it returned `null` or settings of another type, or failed with a `ClassCastException`. | Return the settings it received to keep them unchanged, and implement the module's customizer interface, such as `LoggingPropertiesCustomizer`. If the cast that failed is in the customizer's own code, the cause shows where. |
| `SX-CORE-1003` | A module's settings break their rules, after the files and the Java configuration are combined. The message names each property key and rule, never the value. | Correct the named properties. See [Validation](../configuration/basics.md#validation-wrong-values-fail-fast). |
| `SX-CORE-1004` | A module's `StratumPropertiesCustomizer` is declared as a bean. Stratum reads customizers only from `META-INF/spring.factories`, so the bean would be ignored. This holds for a bean with or without its settings type. The message names the beans. | Implement the module's customizer interface, such as `LoggingPropertiesCustomizer`, in a class with a public no-argument constructor, list it in `META-INF/spring.factories` and remove the bean. See [Set the same values in Java](../configuration/overriding-defaults.md#set-the-same-values-in-java). |
| `SX-CORE-1005` | Warning: the running Spring Boot version is outside the line this Stratum release supports, such as `4.1.x`. The service may fail later in ways that are hard to trace, such as `NoSuchMethodError`. The message names the version found and the supported line. Any patch release of the line, such as `4.1.9`, is quiet. | Set the Spring Boot version in your parent POM, such as your company parent, to the supported line, or use the Stratum release that supports your version. See the [compatibility table](upgrading.md#compatibility) and [ADR 0007](../adr/0007-spring-boot-version-warning.md). |

Each is a `StratumException`. For `SX-CORE-1003`, `getReport().getExternal().details()` also lists
each broken rule, with the property key as `field` and the rule, such as `@NotBlank`, as `message`.

## stratum-starter-logging

Each of these is an error that stops the application at startup, and only when
`stratum.logging.enabled=true`. The logging module reports no warnings with a code. See the
[logging module](../../starters/stratum-starter-logging/README.md).

| Code | Meaning | Fix |
| --- | --- | --- |
| `SX-LOGGING-1001` | `spring.application.name` is not set. The module writes it on every line as `service.name`, so lines from different services can be told apart. | Set `spring.application.name` in `application.properties` or `application.yml`. |
| `SX-LOGGING-1002` | A pattern in `stratum.logging.redaction.extra-patterns` is not a valid regular expression. The message gives the pattern's position in the list, never the pattern or any part of it. | Correct the pattern. In `.properties` files, write each backslash twice. |

A `stratum.logging.max-field-length` outside 1KB to 1MB stops startup with `SX-CORE-1003`, like any
other invalid setting. The message names the rule `stratum.logging.max-field-length-in-range`.

## stratum-starter-observability

These apply only when `stratum.observability.enabled=true`. `SX-OBSERVABILITY-1001` is an error that
stops the application at startup; `SX-OBSERVABILITY-1002` is a warning. See the
[observability module](../../starters/stratum-starter-observability/README.md).

| Code | Meaning | Fix |
| --- | --- | --- |
| `SX-OBSERVABILITY-1001` | With the module on, in any profile, metrics or traces are sent over OTLP but their address is not set, so they would be lost. The message names each missing property: `management.otlp.metrics.export.url`, `management.opentelemetry.tracing.export.otlp.endpoint` or both. | Set the named property to your OpenTelemetry Collector, such as `http://otel-collector:4318/v1/metrics`, or turn that export off with `management.otlp.metrics.export.enabled=false` or `management.tracing.export.enabled=false`. On a laptop without a Collector, use `http://localhost:4318/...`; the data is then dropped quietly. |
| `SX-OBSERVABILITY-1002` | Warning, logged once per metric: a metric was dropped because one of its tags is named like an ID, such as `userId`, `trace_id`, `user.id` or a name in `stratum.observability.tags.extra-forbidden`. Each ID value would create a new time series. The message names the metric and the tag, never a value. | Remove the tag from the metric and put the ID in a log line or a span attribute instead. If the tag really holds a small, known set of values, list its name in `stratum.observability.tags.allowed`. |

## stratum-starter-webmvc and stratum-starter-webflux

These apply only when `stratum.web.enabled=true`. A caller gets all but `SX-WEB-1006` in the error
body of a request that failed outside your own error handling, such as an unknown path, a request
that broke validation or one that ran out of time. `SX-WEB-1006` stops the application at startup. Both web starters send the same
codes. See the
[web MVC starter](../../starters/stratum-starter-webmvc/README.md) and the
[WebFlux starter](../../starters/stratum-starter-webflux/README.md).

| Code | Meaning | Fix |
| --- | --- | --- |
| `SX-WEB-1001` | The request broke validation rules, such as `@Valid` on a body or a constraint on a parameter. The status is `400`, and `details` lists each field with its rule's message. A message that would quote the rejected value is replaced with `is not valid`, and a map key in a field path is shown as `[*]`. | The caller corrects the fields named in `details`. |
| `SX-WEB-1002` | The request could not be read, such as a malformed JSON body, a parameter of the wrong type, or a WebFlux multipart upload with more parts than `spring.webflux.multipart.max-parts` allows. The status is `400`. | The caller fixes the request. On Spring MVC, to see Spring's reason in the service's log, set `spring.mvc.log-resolved-exception=true`. |
| `SX-WEB-1003` | Nothing answers at the request's path. The status is `404`. | The caller checks the path and the method. |
| `SX-WEB-1004` | The request failed with the status shown for another reason, such as a method the path does not allow (`405`) or an exception annotated `@ResponseStatus`. | Depends on the status. A `ResponseStatusException` gets the code of its status, and its reason is not shown. To give the caller your own code, throw a `StratumException` and map its code in `stratum.web.error.statuses`. |
| `SX-WEB-1005` | The service failed unexpectedly. The status is `500`, and the message is fixed, so no internal detail reaches the caller. | Search the service's log for the `traceId` in the body; the exception and its stack trace are there. |
| `SX-WEB-1006` | Startup stopped: `stratum.web.client-location.database` names a file that is missing or is not a MaxMind `.mmdb` database. | Point the property at your GeoIP2 or GeoLite2 City or Country file, or remove it to log no country or city. |
| `SX-WEB-1007` | The request ran out of time before its response started. The status is `stratum.web.timeout.status`, `503` by default, and the service logs a warning with the limit. | The service's team finds the slow call in the trace, adds a timeout to it, or gives the API more time with `stratum.web.timeout.routes` or `@ResponseTimeout`. The caller may retry an idempotent request later. |
| `SX-WEB-1008` | The request body is larger than the API accepts. The status is `413`, and the service logs a warning: with the limit for Stratum's own limit, or saying that one of Spring's multipart or form limits was passed. | The caller sends a smaller body. If the API should accept more, the service's team raises its limit with `stratum.web.body-limit.routes` or `@RequestBodyLimit`. |
