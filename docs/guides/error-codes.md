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
  made from it, so no service can make its errors look like Stratum's.
- **Each module numbers its own codes.** The errors and warnings of one module share one range of
  numbers, so a number is never used twice in a module.
- **The code does not say how serious the problem is.** Each report has a separate `severity`
  field, `ERROR` or `WARNING`, which comes from the kind of code.
- **Info messages have no code.** Only errors and warnings need to be looked up.

Each module declares its codes in Java, in one enum for errors and one for warnings, such as
`CoreErrorCode` and `CoreWarningCode`. The description of each code is a constant in that enum.
No properties file holds them, so no configuration can change what a Stratum code means.

## The two error objects

Stratum describes every error or warning with two objects. Both are in `stratum-core`, in the
package `com.exepex.stratum.core.error`.

| Object | Who sees it | What it holds |
| --- | --- | --- |
| `ErrorReport` | Your own logs only | Code, severity, message, field details, the trace, span and correlation IDs, and the cause with its stack trace |
| `ExternalError` | Anyone outside the application: an API caller, a command-line user, a batch job's result, a user interface | Code, severity, message, field details, the trace ID and the correlation ID |

`ExternalError` has the same shape in every kind of application. It has no field for a stack
trace, the cause or the span ID, so none of them can leak to a caller, whatever sends it.
`ErrorReport.toExternal()` gives the part of a report that may leave the application.

An `ExternalError` written as JSON looks like this:

```json
{
  "code": "ORD-0001",
  "severity": "ERROR",
  "message": "Not enough stock for SKU-1.",
  "details": [
    { "field": "quantity", "message": "must be greater than 0" }
  ],
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "correlationId": "order-42"
}
```

The message is the code's description with its placeholders filled in. A placeholder is written
`{}`, as in a log message, and the arguments fill them in order. It is shown to callers, so
never fill a placeholder with a secret, personal data, a class name or any other internal detail.
Stratum's own `SX-CORE` errors stop the application at startup and never reach a caller.

## The trace, span and correlation IDs

You never pass these IDs by hand. A report reads them, when it is made, from the logging context
(SLF4J's MDC) of the current thread:

| ID | Logging context key | Set by | Ties together |
| --- | --- | --- | --- |
| Trace ID | `traceId` | Tracing (Micrometer Tracing) | Every step of one request, across services |
| Span ID | `spanId` | Tracing (Micrometer Tracing) | One operation within the request |
| Correlation ID | `correlationId` | Stratum (planned: the web and logging modules) | Every step of one business workflow, such as one order or one batch job, across many requests |

A report keeps an ID only if it is 1 to 128 characters long and holds nothing but letters, digits,
`-`, `_`, `.` and `:`. Any other ID is left out, so that a value sent by a caller cannot forge a log
line or break a response. **Planned:** the web module will read the correlation ID from the
`X-Correlation-Id` header, or create one, and pass it on to the services it calls.

## Using codes in your service

Declare your codes in an enum, with your own prefix. Use `ErrorCode` for errors and `WarningCode`
for warnings:

```java
@Getter
@RequiredArgsConstructor
public enum OrderErrorCode implements ErrorCode {

  /** The order asks for more items than are in stock. */
  OUT_OF_STOCK("ORD-0001", "Not enough stock for {}.");

  private final String code;
  private final String description;
}
```

Throw an error, or log a warning:

```java
throw new StratumException(OrderErrorCode.OUT_OF_STOCK, sku);

var warning = ErrorReport.of(OrderWarningCode.DELAYED, days);
log.warn(warning.getCodeAndMessage());
```

The exception's message starts with the code, such as `ORD-0001: Not enough stock for SKU-1.`, and
`exception.getReport()` gives its `ErrorReport`, whose cause is the exception itself. Log a report
with its cause, so the log shows the stack trace:

```java
log.error(report.getCodeAndMessage(), report.getCause());
```
 To add field details, build the report first:

```java
var report =
    ErrorReport.of(OrderErrorCode.OUT_OF_STOCK, sku)
        .withDetails(List.of(new ErrorDetail("quantity", "must be greater than 0")));
throw new StratumException(report);
```

A warning cannot be thrown: `new StratumException(report)` rejects a report whose severity is
`WARNING`.

## stratum-core

### Errors

Each of these stops the application at startup.

| Code | Meaning | Fix |
| --- | --- | --- |
| `SX-CORE-1001` | A module name given to `@ConditionalOnStratumModule` is not one lower-case, kebab-case word, such as `http-client`. | Rename the module in the annotation. This is a fault in a starter, not in your service's configuration. |
| `SX-CORE-1002` | A `StratumPropertiesCustomizer` returned `null`. The message names the customizer. | Return the settings the customizer received to leave them unchanged. |
| `SX-CORE-1003` | A `StratumPropertiesCustomizer` returned settings of another type. It is a customizer for another module, or it was registered without its type. | Declare it as `StratumPropertiesCustomizer<TheSettings>`, for example as the return type of a `@Bean` method. |
| `SX-CORE-1004` | A `StratumPropertiesCustomizer` failed with a `ClassCastException`. | If it is a customizer for other settings, or registered without its type, fix it as for `SX-CORE-1003`. Otherwise the failed cast is in the customizer's own code; the cause shows where. |
| `SX-CORE-1005` | A module's settings break their rules, after the files and the Java configuration are combined. The message names each property key and rule, never the value. | Correct the named properties. See [Validation](../configuration/basics.md#validation-wrong-values-fail-fast). |

`SX-CORE-1005` is a `ConstraintViolationException` with the code at the start of its message, so
tools that read the broken constraints still can. The others are a `StratumException`.

### Warnings

`stratum-core` reports no warnings yet.
