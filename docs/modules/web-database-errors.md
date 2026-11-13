# Web: database failures

**Status: Built**, in `stratum-starter-webmvc` and `stratum-starter-webflux`, unless
`stratum.web.enabled=false`, and only while `stratum.backward-compatible` is `false`, its default.

[← Web](web.md) · [Documentation home](../README.md) · Property prefix: `stratum.web.error`

## What it does

When a request fails because of the database, and none of your own `@ExceptionHandler` methods
answers it, the caller learns what kind of failure it was:

- **The database is busy or too slow:** `503` with the code `SX-WEB-1010`. The caller may try
  again later.
- **The request conflicts with the stored data:** `409` with the code `SX-WEB-1011`. The caller
  reads the data again before it retries.

Any other database failure is still answered with `500` and `SX-WEB-1005`, as every unexpected
failure is. The body never contains SQL, a constraint name or a value: its message is the code's
fixed text. The whole exception, with its stack trace, goes to your log only.

## Which failures get which answer

Stratum looks at the failure and each of its causes, outermost first, and the first one it knows
decides:

| Among the failure and its causes | Status | `code` |
| --- | --- | --- |
| A `StratumException` or `FailureWithErrorCode`, such as `SX-DATA-1003` when the [data module](data-statement-limits.md) cancelled a statement | its mapped status: `503` for `SX-DATA-1003` | its own code |
| `java.sql.SQLTimeoutException`, such as a query timeout set through JDBC | `503` | `SX-WEB-1010` |
| `java.sql.SQLTransientConnectionException`, such as HikariCP's after a caller waited `spring.datasource.hikari.connection-timeout` for a connection, also when the database is down | `503` | `SX-WEB-1010` |
| A database error with SQLState `57014` (statement cancelled, such as by PostgreSQL's `statement_timeout`), `55P03` (lock not available, such as by `lock_timeout`) or `40P01` (deadlock) | `503` | `SX-WEB-1010` |
| Spring's `TransactionTimedOutException` | `503` | `SX-WEB-1010` |
| Spring's `OptimisticLockingFailureException`, or any subclass, such as `ObjectOptimisticLockingFailureException` for an `@Version` entity changed meanwhile | `409` | `SX-WEB-1011` |
| Hibernate's `StaleStateException`, or any subclass, which is also beneath the JPA `OptimisticLockException` of an explicit `flush()` | `409` | `SX-WEB-1011` |
| A database error with SQLState `23505`, a unique key that already exists, as PostgreSQL reports it | `409` | `SX-WEB-1011` |

Spring's and Hibernate's classes are matched by name, so the web starters work the same with or
without them.

These keep `500` and `SX-WEB-1005`:

- any other integrity error, such as a foreign key (`23503`), a `NOT NULL` column (`23502`) or a
  check (`23514`), which is a fault in the request or the service, not a conflict;
- a duplicate key reported with the generic SQLState `23000`, as MySQL, Oracle and SQL Server do,
  and their deadlocks and lock timeouts, which they report with other SQLStates or vendor codes;
- a serialization failure (`40001`) and a connection lost in the middle of a statement (`08006`);
- a failure of an R2DBC driver, whose exceptions are not `java.sql` ones;
- on Spring MVC, a database failure thrown by a servlet filter rather than by a controller.

## What the caller and the logs see

The caller gets the [error body](../../starters/stratum-starter-webmvc/README.md#the-error-body)
every error gets, with the trace ID to quote to support:

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "The request conflicts with the current data; read it again before retrying",
  "code": "SX-WEB-1011",
  "severity": "ERROR",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "correlationId": "checkout-777"
}
```

The service logs the failure once, with its stack trace: a `503` at ERROR and a `409` at WARN. Any
caller can cause a `409`, such as by sending the same new order twice, so each one writes a WARN
line. With the data JPA or JDBC starter, the logged exception has no values from the database; see
[Clean database errors](data.md#clean-database-errors).

## Change a status

Map the code to another status in `stratum.web.error.statuses`, as any code. In
`application.properties`:

```properties
stratum.web.error.statuses.SX-WEB-1010=504
stratum.web.error.statuses.SX-WEB-1011=422
```

or in `application.yml`:

```yaml
stratum:
  web:
    error:
      statuses:
        SX-WEB-1010: 504
        SX-WEB-1011: 422
```

The status is any from `400` to `599`. A `5xx` is logged at ERROR and a `4xx` at WARN. Each key,
with its type and default, is listed in [Web → Properties](web.md#properties).

## Configure in Java

As every setting, the statuses can be set in a `WebPropertiesCustomizer`, and Java wins over the
files:

```java
public class OrdersWebSettings implements WebPropertiesCustomizer {

  @Override
  public WebProperties customize(WebProperties properties) {
    var statuses = new HashMap<>(properties.error().statuses());
    statuses.put("SX-WEB-1011", 422);
    return properties.toBuilder().error(new WebProperties.ErrorSettings(statuses)).build();
  }
}
```

Start from the statuses already set, as above: a new map replaces them all, with the starters'
defaults such as `SX-DATA-1003`. List the class in `META-INF/spring.factories`, as the
[web MVC starter's README](../../starters/stratum-starter-webmvc/README.md#configure-in-java) shows.

## Your own handling wins

Your own `@ExceptionHandler` or `@RestControllerAdvice` for these exceptions always wins, as for
any failure. An exception that sets its own status, such as a `ResponseStatusException` that wraps
the database failure, keeps that status.

While `stratum.backward-compatible` is `true`, nothing on this page applies: a database failure
keeps the answer Spring Boot gives, `500` with Spring Boot's body, so existing consumers see no
change. See [Backward-compatible mode](web-compatible-mode.md).

## Retries

A `503` tells the caller that a later try can work. The work may still have been partly done: a
request that committed one transaction and then timed out in a second one keeps the first. Make the
requests a caller retries idempotent.
