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

With the observability module on, the correlation ID is the `correlationId` entry of the
tracing baggage when one is set, and otherwise the trace ID, which every service in the same trace
shares ([observability module](../modules/observability-traces.md#correlation-id)).
Only the [allowed baggage entries](../modules/observability-traces.md#baggage) are sent, but they go
on every outgoing call made with Spring's HTTP clients, `WebClient` in reactive code included, to
every host, so never put a secret or personal data in the correlation ID. With
the web module on, the web module also takes the ID from the caller's `X-Correlation-Id`
header and returns it in the response
([web module](../modules/web.md#correlation-id)). Sending baggage only to the service's own hosts is
planned ([#171](https://github.com/exepex/stratum/issues/171)).

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
With the observability module and a web starter, a request that fails with it has the same code in
the `error.code` tag of its timer and on its span
([traces](../modules/observability-traces.md#error-codes-on-requests)).

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
them to `StratumException`. This changes what their consumers get, even in backward-compatible
mode: map each code to the status the exception has today, and tell its consumers, before you mark
it ([Marking an existing exception](../modules/web-compatible-mode.md#marking-an-existing-exception)).
Make each one implement `FailureWithErrorCode`:

```java
public class PaymentDeclinedException extends RuntimeException implements FailureWithErrorCode {

  private final String orderId;

  public PaymentDeclinedException(String orderId) {
    super("payment declined");
    this.orderId = orderId;
  }

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
change this on either stack: the status mapped to the code wins.

When another exception wraps it as its cause, the code is used only where Spring has no status for
the wrapper, which would otherwise answer `500`. A plain `RuntimeException` around it gets the code's
status. A wrapper with a status of its own keeps Spring's answer, so existing callers see no change:
a `ResponseStatusException(NOT_FOUND)` with it as the cause still answers `404` with `SX-WEB-1003`,
and unreadable JSON still answers `400` with `SX-WEB-1002`. The logging module writes its code
in `error.code`. A warning code gets `500` and
`SX-WEB-1005`, because a warning is never thrown. New code throws `StratumException`; this
interface is only for moving existing exceptions over time.

## When startup stops

An error that stops startup appears in Spring Boot's failure report, with the code and message as
the description and the first sentences of the Fix column below as the action:

```text
***************************
APPLICATION FAILED TO START
***************************

Description:

SX-CORE-1003: Invalid settings: stratum.web.timeout.status breaks @Max(value=599)

Action:

Correct the named properties. See SX-CORE-1003 in https://github.com/exepex/stratum/blob/main/docs/guides/error-codes.md.
```

The report covers every Stratum code that stops startup. Your service's own codes are left to
Spring Boot's usual report.

## stratum-core

Each of these but `SX-CORE-1005` is an error that stops the application at startup.
`SX-CORE-1005` is a warning, logged once at startup; the application keeps running.

| Code | Meaning | Fix |
| --- | --- | --- |
| `SX-CORE-1001` | A module name given to `@ConditionalOnStratumModule` is not one lower-case, kebab-case word, such as `http-client`. The message names the annotated class, or the method as `Class#method`. | Rename the module in the annotation. This is a fault in a starter, not in your service's configuration. |
| `SX-CORE-1002` | A `StratumPropertiesCustomizer` is broken. The message names the customizer and says whether it returned `null` or settings of another type, or failed with a `ClassCastException`. | Return the settings it received to keep them unchanged, and implement the module's customizer interface, such as `LoggingPropertiesCustomizer`. If the cast that failed is in the customizer's own code, the cause shows where. |
| `SX-CORE-1003` | A module's settings break their rules, after the files and the Java configuration are combined. The message names each property key and rule, never the value. | Correct the named properties. See [Validation](../configuration/basics.md#validation-wrong-values-fail-fast). |
| `SX-CORE-1004` | A module's `StratumPropertiesCustomizer` is declared as a bean. Stratum reads customizers only from `META-INF/spring.factories`, so the bean would be ignored. This holds for a bean with or without its settings type. The message names each bean and the customizer interface it implements, such as `webCustomizer (WebPropertiesCustomizer)`. | Implement the module's customizer interface, such as `LoggingPropertiesCustomizer`, in a class with a public no-argument constructor, list it in `META-INF/spring.factories` and remove the bean. See [Set the same values in Java](../configuration/overriding-defaults.md#set-the-same-values-in-java). |
| `SX-CORE-1005` | Warning: the running Spring Boot version is outside the line this Stratum release supports, such as `4.1.x`. The service may fail later in ways that are hard to trace, such as `NoSuchMethodError`. The message names the version found and the supported line. Any patch release of the line, such as `4.1.9`, is quiet. | Set the Spring Boot version in your parent POM, such as your company parent, to the supported line, or use the Stratum release that supports your version. See the [compatibility table](upgrading.md#compatibility) and [ADR 0007](../adr/0007-spring-boot-version-warning.md). |
| `SX-CORE-1006` | A module is on, but its switch was `false` when the modules' startup steps ran, as with `false` in `application.yml` and `true` in remote configuration added later by an `ApplicationContextInitializer`. The module's beans would run without its startup defaults and checks, such as structured, redacted logs. The message names the switch and the property source that turns it on. | Set the switch in one place that Spring Boot reads early: `application.properties` or `application.yml`, an environment variable or the command line. A source added after the configuration files is read too late for it. See [Properties](../configuration/basics.md). |
| `SX-CORE-1007` | A module is off, but its switch was not `false` when the modules' startup steps ran, as with `false` set only in a `@PropertySource` file. The module's startup defaults, such as its log format, would apply without its beans. The message names the switch and the property source that turns it off. | Set the switch in `application.properties` or `application.yml`, an environment variable or the command line. A `@PropertySource` file loads too late for it. See [Properties](../configuration/basics.md). |

Each is a `StratumException`. For `SX-CORE-1003`, `getReport().getExternal().details()` also lists
each broken rule, with the property key as `field` and the rule, such as `@NotBlank`, as `message`.

## stratum-starter-logging

Each of these is an error that stops the application at startup, and only while the module is on,
which it is unless `stratum.logging.enabled=false`. The logging module reports no warnings with a code. See the
[logging module](../../starters/stratum-starter-logging/README.md).

| Code | Meaning | Fix |
| --- | --- | --- |
| `SX-LOGGING-1001` | `spring.application.name` is not set. The module writes it on every line as `service.name`, so lines from different services can be told apart. | Set `spring.application.name` in `application.properties` or `application.yml`, or turn the module off with `stratum.logging.enabled=false`. |
| `SX-LOGGING-1002` | A pattern in `stratum.logging.redaction.extra-patterns` is not a valid regular expression. The message gives the pattern's position in the list, never the pattern or any part of it. | Correct the pattern. In `.properties` files, write each backslash twice. |
| `SX-LOGGING-1003` | A log output does not use one of Stratum's formats: an own format or formatter class, an empty format, a Logback file, `logging.config`, or no Logback or Log4j2. The message names the key or file, never its value. | Remove your own log format, Logback configuration file or `logging.config`, or add Logback or Log4j2, and choose the format with `stratum.logging.format`. To keep your own format, turn the module off with `stratum.logging.enabled=false`. |

A `stratum.logging.max-field-length` outside 1KB to 1MB stops startup with `SX-CORE-1003`, like any
other invalid setting. The message names the rule `stratum.logging.max-field-length-in-range`.

## stratum-starter-observability

These apply only while the module is on, which it is unless `stratum.observability.enabled=false`. `SX-OBSERVABILITY-1001` is an error that
stops the application at startup; `SX-OBSERVABILITY-1002` and `SX-OBSERVABILITY-1003` are warnings. See the
[observability module](../../starters/stratum-starter-observability/README.md).

| Code | Meaning | Fix |
| --- | --- | --- |
| `SX-OBSERVABILITY-1001` | With the module on, in any profile, metrics or traces are sent over OTLP but their address is not set, so they would be lost. The message names each missing property: `management.otlp.metrics.export.url`, `management.opentelemetry.tracing.export.otlp.endpoint` or both. | Set the named property to your OpenTelemetry Collector, such as `http://otel-collector:4318/v1/metrics`, or turn that export off with `management.otlp.metrics.export.enabled=false` or `management.tracing.export.enabled=false`, or turn the module off with `stratum.observability.enabled=false`. On a laptop without a Collector, use `http://localhost:4318/...`; the data is then dropped without a failed-send log. |
| `SX-OBSERVABILITY-1002` | Warning, logged once per metric: a metric was dropped because one of its tags is named like an ID, such as `userId`, `trace_id`, `user.id` or a name in `stratum.observability.tags.extra-forbidden`. Each ID value would create a new time series. The message names the metric and the tag, never a value. | Remove the tag from the metric and put the ID in a log line or a span attribute instead. If the tag really holds a small, known set of values, list its name in `stratum.observability.tags.allowed`. |
| `SX-OBSERVABILITY-1003` | Warning, logged once per cap: a new meter was dropped because the registry already holds `stratum.observability.metrics.max-meters` meters, or the HTTP client meters already have `stratum.observability.metrics.max-client-names` hosts in their `client.name` tag. A tag probably holds unbounded values. The message names the meter and the key, never a value. | Find the tag with unbounded values, such as an ID, a raw path or a host per customer, and remove it or bound it. Raise the named key only after checking that the metrics backend can store that many series ([meter caps](../modules/observability-metrics.md)). |

## stratum-starter-data-jpa

This is an error that stops the application at startup, and only while the module is on, which it
is unless `stratum.data.enabled=false`. See the [data module](../modules/data-jpa.md).

| Code | Meaning | Fix |
| --- | --- | --- |
| `SX-DATA-1001` | Hibernate checked the entities against the database schema at startup, which Stratum turns on with `ddl-auto=validate`, and an entity does not match it, as with a column that no migration added. The message is Hibernate's, such as `Schema validation: missing column [customer_id] in table [orders]`: it names the table and the column, never a value. | Fix the entity mapping or add the missing migration, or, to stop Hibernate checking the schema, set `spring.jpa.hibernate.ddl-auto=none`. See [Schema check](../modules/data-jpa.md#schema-check). |

## stratum-starter-cache

`SX-CACHE-1001`, `SX-CACHE-1007` and `SX-CACHE-1008` are errors that stop the application at startup;
`SX-CACHE-1002` to `SX-CACHE-1006` are warnings, logged while the calls carry on. These apply only
while the module is on, which it is unless `stratum.cache.enabled=false`. See the [cache module](../modules/cache.md).

| Code | Meaning | Fix |
| --- | --- | --- |
| `SX-CACHE-1001` | Spring Data Redis is on the class path and the cache module would start every Redis cache key with the service's name, through Stratum's default `spring.cache.redis.key-prefix`, but `spring.application.name` is not set or is blank. Without a name, services sharing one Redis would read each other's entries. | Set `spring.application.name`, or set your own `spring.cache.redis.key-prefix`, or turn the module off with `stratum.cache.enabled=false`. A service that turns key prefixes off with `spring.cache.redis.use-key-prefix=false` needs no name. See [Cache → Key prefix](../modules/cache.md#key-prefix). |
| `SX-CACHE-1002` | Warning, logged once per cache per `stratum.cache.warning-interval`: a cache operation failed, such as a read or write while Redis is down, and the call ran without the cache. The message names the cache and the operation, never a key or value, and the failure is counted in `stratum.cache.failures`. | Check Redis and the cache client; the method still runs, so the call did not fail. To fail the call on a cache error instead, declare a `CachingConfigurer` with an error handler that rethrows. See [Cache → When Redis is down](../modules/cache-redis-down.md). |
| `SX-CACHE-1003` | Warning, logged once per interval: a cache name was refused because the service already uses `stratum.cache.max-caches` caches (100 by default). Calls for the refused name run uncached, and no cache is created for it; each refusal is counted in `stratum.cache.skips` under `cache=_over-limit`. | Name the service's caches explicitly, so they stay few, or raise `stratum.cache.max-caches`. See [Cache → Limits](../modules/cache.md#limits). |
| `SX-CACHE-1004` | Warning, logged once per cache per `stratum.cache.warning-interval`: a `@Cacheable` method's return type cannot be read from a Redis cache, so the method runs without the cache and nothing is read from or written to Redis for it. The type is `Object`, an interface or an abstract class (collections and maps apart), or comes from a package that is neither the service's own, nor `java`, nor listed in `stratum.cache.serialization.allowed-packages`. The message names the cache, the type and the reason; each call is counted in `stratum.cache.skips` with `reason=type-not-concrete` or `reason=type-not-allowed`. | Return a concrete type, or add its package to `stratum.cache.serialization.allowed-packages`. See [Cache → Stored values](../modules/cache-values.md). |
| `SX-CACHE-1008` | A Caffeine cache sets its own `stratum.cache.caches.<name>.max-entries` or `time-to-live`, which cannot apply: the Caffeine builder has no `maximumSize` (or bounds weight instead), or no `expireAfterWrite`, or `spring.cache.cache-names` lists the caches and leaves this one out. The message names the cache and the setting. | Give the Caffeine caches a `maximumSize` and an `expireAfterWrite`, such as in `spring.cache.caffeine.spec`, list the cache in `spring.cache.cache-names`, or remove the cache's own setting. See [Cache → Caffeine](../modules/cache-caffeine.md). |
| `SX-CACHE-1005` | Warning, logged once per cache per `stratum.cache.warning-interval`: a value stored in Redis could not be read as the method's return type, usually because the class changed since it was stored, or it is larger than `stratum.cache.max-value-size`. It was treated as a miss: the method ran and its result replaced the entry. The message names the cache and the type, never a key or value; each one is counted in `stratum.cache.skips` with `reason=unreadable`. | Nothing fails. When a class change makes many entries unreadable, delete the cache's old keys or give the cache a new name, such as `orders-v2`. See [Cache → Stored values](../modules/cache-values.md). |
| `SX-CACHE-1006` | Warning, logged at most once per `stratum.cache.warning-interval`: Redis could not be reached, or a Redis command did not answer within `spring.data.redis.timeout`, `stratum.cache.redis-failure-threshold` times in a row (3 by default), so every Redis cache of the service skips Redis for `stratum.cache.redis-retry-interval` (5 seconds by default); then one call tries again. While Redis is skipped, reads miss, so the methods run, and writes and evictions are dropped. The gauge `stratum.cache.redis.available` reads 0 from then until Redis answers again, and each time Redis starts being skipped because it could not be reached is counted in `stratum.cache.redis.connect-failures`. | Check that Redis is up and reachable from the service, or why its commands are slow. Nothing fails meanwhile: the methods still run, without the cache. See [Cache → When Redis is down](../modules/cache-redis-down.md). |
| `SX-CACHE-1007` | Spring Boot builds its Redis cache manager, and the cache module would store its values as JSON, but Jackson (`tools.jackson.core:jackson-databind`) is not on the class path. | Add `org.springframework.boot:spring-boot-starter-json`, or declare your own `RedisCacheConfiguration` bean, or turn the module off with `stratum.cache.enabled=false`. See [Cache → Stored values](../modules/cache-values.md). |

## stratum-starter-http-client

Each of these is an error that stops the application at startup, and only while the module is on,
which it is unless `stratum.http-client.enabled=false`. See the [HTTP client module](../modules/http-client.md).

| Code | Meaning | Fix |
| --- | --- | --- |
| `SX-HTTPC-1001` | A Spring Boot setting under `spring.http.serviceclient.<name>`, such as `base-url` or `read-timeout`, is set for the name of a Stratum client. A Stratum client does not read it, so the setting would do nothing. The message names the client, never the value. | Move the setting to `stratum.http-client.clients.<name>`, such as `url` instead of `base-url` and `timeout` instead of `read-timeout`. See [HTTP client → Properties](../modules/http-client.md#properties). |
| `SX-HTTPC-1002` | A method of a `@StratumClient` interface returns `Mono` or `Flux`, which the blocking HTTP client starter cannot send. The message names the interface and the method. | Return the value itself, such as `Stock` instead of `Mono<Stock>`; the reactive HTTP client starter is planned. |
| `SX-HTTPC-1003` | A `@StratumClient` name is not lowercase letters, digits and hyphens starting with a letter, so it cannot be a part of `stratum.http-client.clients.<name>`. The message names the interface. | Rename the client, such as `inventory` or `payments-v2`, and its settings with it. |
| `SX-HTTPC-1004` | A `@StratumClient` has no address: `stratum.http-client.clients.<name>.url` is not set. The message names the key. | Set `stratum.http-client.clients.<name>.url`, such as `http://inventory:8080`. See [HTTP client → Use it](../modules/http-client.md#use-it). |
| `SX-HTTPC-1007` | A client's `stratum.http-client.clients.<name>.interfaces` names a type that is not on the classpath, or that is a class rather than an interface. The message names the key and the type. | Name an interface by its full name, such as com.partner.OrdersApi, and check that its jar is on the classpath. See [HTTP client → Clients Stratum did not build](../modules/http-client.md#clients-stratum-did-not-build). |
| `SX-HTTPC-1008` | An interface marked `@StratumClient` for one client is also named in another client's `interfaces` setting, which would make one type two clients. The message names the interface and both clients. | Keep the interface in one client: remove it from the other client's interfaces, or change its @StratumClient name. See [HTTP client → Clients Stratum did not build](../modules/http-client.md#clients-stratum-did-not-build). |

## stratum-starter-webmvc and stratum-starter-webflux

These apply only while the module is on, which it is unless `stratum.web.enabled=false`. A caller gets all but `SX-WEB-1006` and `SX-WEB-1009` in the error
body of a request that failed outside your own error handling, such as an unknown path, a request
that broke validation or one that ran out of time. `SX-WEB-1006` and `SX-WEB-1009` stop the application at startup. Both web starters send the same
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
| `SX-WEB-1009` | Startup stopped: a path pattern in `stratum.web.timeout.routes`, `stratum.web.body-limit.routes` or `stratum.web.request-log.skip-paths` is not a valid Spring path pattern, such as `/api/{id` or `/api/**/orders`. The message names the key, the pattern and Spring's reason. | Correct the pattern; Spring's path patterns allow {name}, * and **, and ** only at the end. |
