# Data: the JDBC boundary, `stratum-data`

**Status: Partly built.** What every Stratum data starter shares: database errors that carry no
data values, from your code and from Flyway and Liquibase, a time limit on every SQL statement, a
check that the connection to the database is encrypted, and safe defaults for Spring Boot's
connection pool (HikariCP) and transactions.

[← Documentation home](../README.md) · Property prefix: `stratum.data`

## What it does

Database problems in production usually come from the same few settings: callers that wait 30
seconds for a busy pool, transactions with no time limit, and database errors that repeat the
data that caused them. `stratum-data` sets these the safe way for every service that reaches its
database through JDBC. It sets only Spring Boot's own keys, so every value your service sets wins.

You do not add `stratum-data` yourself: a data starter brings it. Two do:
[`stratum-starter-data-jpa`](data-jpa.md) for Spring Data JPA and Hibernate, and
[`stratum-starter-jdbc`](jdbc.md) for plain JDBC with `JdbcClient` or `JdbcTemplate`. Each page
shows how to add it. R2DBC is not covered.

## What you get

| Feature | Behaviour | Applies |
| --- | --- | --- |
| Clean database errors | No value from a database error, such as a duplicate email address, reaches a log, a span or a response; see [Clean database errors](#clean-database-errors) | Always |
| Statement time limits | Every SQL statement ends by its code's own limit, its transaction's deadline and its web request's deadline, and Stratum cancels it with `SX-DATA-1003`; see [Statement time limits](data-statement-limits.md) | Always |
| TLS check | Shortly after startup, one WARN for each data source whose connection to the database is not encrypted; see [The database TLS check](data-tls-check.md) | Always |
| Pool statement limit | A statement whose code sets no limit of its own may run 30 seconds (`stratum.data.query-timeout`) | Strict mode |
| Short pool wait | A caller waits at most 3 seconds for a free connection, then gets an error, instead of 30 seconds | Always |
| Login limit | A new connection to a PostgreSQL database that never answers fails after about 3 seconds, instead of waiting for good; see [The PostgreSQL login limit](data-login-limit.md) | Always |
| Leak alarm check | A `leak-detection-threshold` that HikariCP would silently turn off stops startup with `SX-DATA-1002`; the alarm itself stays off, see [Leak alarm](#leak-alarm) | Always |
| Transaction time limit | Every transaction that sets no limit of its own gets 60 seconds | Strict mode, without Spring Batch |

**Strict mode** is Stratum's default, `stratum.backward-compatible=false`. A service already
running with consumers sets `stratum.backward-compatible=true` while it moves to Stratum; then the
"Strict mode" rows are left at Spring Boot's own defaults, because they can change what the
service's callers see: a slower response or an error. The "Always" rows change nothing a caller
sees and apply in both modes
([ADR 0010](../adr/0010-modules-on-when-present-and-one-compatibility-switch.md)).

## Properties

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `stratum.data.enabled` | boolean | `true` | Whether the module is on. With `false`, Stratum sets none of the defaults below, nor a data starter's own, and Spring Boot's own apply, and it wraps no data source in the [clean errors boundary](#clean-database-errors). |
| `stratum.data.query-timeout` | duration | `30s`, or `0` with `stratum.backward-compatible=true` | How long one SQL statement may run when the code that runs it sets no time limit of its own; `0` for none. See [Statement time limits](data-statement-limits.md). |
| `stratum.data.deadline-margin` | duration | `150ms` | How much earlier than its web request's time limit a statement or connection borrow must end; `0` for none. See [In a web request](data-statement-limits.md#in-a-web-request). |

Spring Boot properties this module and the data JPA starter give a default (your values win):

| Spring Boot property | Type | Stratum default | Spring Boot's default | Meaning |
| --- | --- | --- | --- | --- |
| `spring.datasource.hikari.connection-timeout` | long (ms) | `3000` | `30000` (HikariCP) | How long a caller waits for a free connection. |
| `spring.transaction.default-timeout` | duration | `60s` (strict mode, without Spring Batch) | unset: no limit | The time limit of a transaction that sets none. |
| `spring.jpa.hibernate.ddl-auto` | string | `validate`, see [Schema check](data-jpa.md#schema-check); data JPA starter only | `none`, or `create-drop` for an in-memory database | What Hibernate does with the schema at startup. |
| `spring.jpa.properties.hibernate.jdbc.batch_size` | int | `50`; data JPA starter only | unset: no batching | How many rows Hibernate sends in one batch. |
| `spring.jpa.properties.hibernate.order_inserts` | boolean | `true`; data JPA starter only | `false` | Groups inserts by table, so they can be batched. |
| `spring.jpa.properties.hibernate.order_updates` | boolean | `true`; data JPA starter only | `false` | Groups updates by table, so they can be batched. |
| `spring.jpa.properties.hibernate.query.in_clause_parameter_padding` | boolean | `true`; data JPA starter only | `false` | Pads `IN` lists to the next power of two. |
| `spring.jpa.properties.hibernate.jdbc.log.errors` | boolean | `false`; data JPA starter only | `true` | Whether Hibernate logs each JDBC error itself, before it throws it. |
| `spring.jpa.properties.hibernate.jdbc.log.warnings` | boolean | `false`; data JPA starter only | depends on the dialect | Whether Hibernate logs each JDBC warning. |
| `spring.jpa.open-in-view` | boolean | `false` (strict mode); data JPA starter only | `true`, with a warning at startup | Whether a web request keeps its connection until the response is written. |
| `spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch` | boolean | `true` (strict mode); data JPA starter only | `false` | Whether paging a fetch join over a collection fails instead of paging in memory. |

### The pool

Stratum leaves HikariCP's other defaults as they are:

| Spring Boot property | HikariCP's default | Meaning |
| --- | --- | --- |
| `spring.datasource.hikari.maximum-pool-size` | `10` | The most connections the pool opens. |
| `spring.datasource.hikari.minimum-idle` | equal to the maximum: a fixed-size pool | Connections kept open when idle. |
| `spring.datasource.hikari.max-lifetime` | `1800000` (30 minutes) | When a connection is closed and replaced. |
| `spring.datasource.hikari.keepalive-time` | `120000` (2 minutes) | How often an idle connection is checked. |

> [!TIP]
> Size the pool from the database's limit, not from your traffic: `instances × maximum-pool-size`
> must stay below what the database allows. A small pool with short transactions serves more
> requests than a big pool with long ones.

## Clean database errors

A database error often repeats the data that caused it. PostgreSQL's message for a duplicate email
address, for example, ends with `Detail: Key (email)=(ada@example.com) already exists.` Without
Stratum, that address reaches your logs, your traces and, through a careless error handler, your
callers.

Stratum wraps every `DataSource` bean, and every JDBC object it hands out, in a boundary. Every
`SQLException` that reaches your code, Hibernate, `JdbcClient`, `JdbcTemplate` or any other library
through a `DataSource` bean is a clean copy of the driver's error:

- the same standard `java.sql` class, SQLState, vendor code and stack trace, so Spring and
  Hibernate translate it exactly as before;
- no driver text, except the first line of an error about the system rather than the data:
  SQLState classes `08` (connection), `28` (authorization), `40` (rollback, such as a deadlock),
  `53` (resources), `57` (operator, such as a cancelled query) and `58` (system), and the pool's
  own connection errors, such as `Connection is not available, request timed out after 3000ms`;
- every other error gets a fixed text, such as `SQLState 22P02, vendor code 0`;
- its causes, next exceptions and suppressed exceptions, and the warnings `getWarnings()` returns,
  are cleaned the same way.

On PostgreSQL, a violated constraint keeps its name, with no value:
`duplicate key value violates unique constraint "customers_email_key"`. So Hibernate's
`ConstraintViolationException.getConstraintName()` and Spring's `DuplicateKeyException` keep
working. With the data JPA starter, Hibernate's own logging of JDBC errors and warnings is off
(`hibernate.jdbc.log.errors=false`, `hibernate.jdbc.log.warnings=false`); turn it on again with
the keys in [Properties](#properties) if you need it.

**What it costs.** Stratum's limit for the boundary is 1 µs per JDBC call. JMH measured it against
the raw connection pool on an Intel Xeon at 2.10 GHz with 4 cores, JDK 25.0.5 and PostgreSQL 18.6
in a container:

- reading a value from a row already fetched: about 1 ns more per call;
- creating and closing a statement, which builds one wrapper: about 7 ns more for the two calls;
- a whole query, with its round trip to the database of about 100 µs: indicative only. The
  difference between the raw pool and the boundary was smaller than the variation between
  containers, so this benchmark could not measure it.

To run the benchmark yourself, see
[Benchmarking the JDBC boundary](../contributing.md#benchmarking-the-jdbc-boundary).

**Limits:**

- The copy is a standard `java.sql` class, so code that catches or casts a driver's class, such as
  PostgreSQL's `PSQLException`, no longer matches.
- A cause that is not an `SQLException`, such as a `SocketException`, becomes an `Exception`
  whose message names the original class only.
- Stratum sets no driver-specific error setting, such as PostgreSQL's `logServerErrorDetail`: only
  standard JDBC. The boundary already drops the driver's message.
- Code that calls `unwrap()` to reach the driver's own classes, such as PostgreSQL's `COPY`,
  driver metrics or Flyway's internals (its PostgreSQL support does so for a `COPY` statement in a
  migration), bypasses the boundary and handles that driver's errors
  itself. The driver's own logging, off by default, is outside the boundary too.
- R2DBC is not covered. A `DataSource` built outside Spring, not as a bean, is not wrapped, except
  the connection Spring Boot builds for Flyway or Liquibase ([Migrations](#migrations)).
- On a database other than PostgreSQL, `getConstraintName()` is `null`. The error is still clean.
- The wrappers are generated when the application starts, which a GraalVM native image does not
  support.
- You can no longer inject `HikariDataSource`: inject `DataSource` and call
  `dataSource.unwrap(HikariDataSource.class)`. Spring Boot's pool metrics and `db` health check
  still find the pool.
- With `spring.datasource.connection-fetch=lazy` and `spring.jmx.enabled=true`, Spring Boot no
  longer registers the pool as an MBean, because it checks the wrapper's class. HikariCP's own
  `spring.datasource.hikari.register-mbeans=true` still works.
- Hibernate and Spring messages can name an entity and its identifier, such as
  `[PlacedOrder#42]`. That is your own identifier, not a driver value: do not use personal data,
  such as an email address, as an entity's identifier.
- Code you place between two data source beans that adds a raw driver error to a clean one, with
  `addSuppressed` or `setNextException`, is not cleaned again. Spring's routing and lazy proxies
  and HikariCP add none.
- Because a cause loses its class, Spring no longer sees a `SocketTimeoutException` when it marks
  a connection read-only for a read-only transaction: it rethrows only a cause whose class name
  contains `Timeout`, so it now carries on. This happens only with the PostgreSQL driver setting
  `readOnlyMode=always`, not with its default.
- The boundary is tested on PostgreSQL only.

## Migrations

Flyway and Liquibase run your migrations at startup, and their errors are clean in the same way.
They reach the database in one of two ways:

- **Through your `DataSource` bean**, when you set no connection of their own. The bean already
  has the boundary.
- **Through a connection of their own** that Spring Boot builds when you set
  `spring.flyway.url` or `spring.flyway.user` (or `spring.liquibase.url` or
  `spring.liquibase.user`), or when `@ServiceConnection` or Docker Compose supplies the database.
  Stratum wraps that connection too: with a Flyway customizer, and with a bean post-processor that
  runs before Liquibase applies its changes. It sets no query timeout on it.

Stratum defines no `@FlywayDataSource` or `@LiquibaseDataSource` bean, so your
`spring.flyway.user` and `spring.liquibase.user` keep working, and it sets no `baseline-on-migrate`
default.

**Limits:**

- Stratum wraps only the connection Spring Boot builds, a `SimpleDriverDataSource`. A data source of
  another type that your own `FlywayConfigurationCustomizer` sets is not wrapped, such as the one
  Flyway builds itself for `configuration.dataSource(url, user, password)`. Stratum's customizer
  has no order of its own.
- A `SimpleDriverDataSource` bean that you declare with `@FlywayDataSource` or
  `@LiquibaseDataSource` is wrapped as a bean. When your `stratumJdbcBoundary` filter skips it, the
  migration tool still uses it with the boundary, because the migration wrapping looks at the type
  only.

## Bind parameters

Pass every value as a bind parameter, never as SQL or JPQL you build by joining strings:

| API | Placeholder |
| --- | --- |
| `JdbcTemplate` | `?` |
| `JdbcClient` | `?` or `:name` |
| JPQL, `@Query` | `:name` or `?1` |
| Spring Data query methods | the method's parameters |

```java
jdbcClient.sql("SELECT id FROM customers WHERE email = :email")
    .param("email", email)
    .query(Long.class)
    .optional();
```

Stratum has no runtime check for SQL built from input, because no cheap one exists.

## Statement time limits

Every SQL statement runs under a time limit, which Stratum enforces itself and cancels with
`SX-DATA-1003`: the pool's `stratum.data.query-timeout`, the code's own limit, the
transaction's deadline and the web request's. How the limit is chosen, what a cancelled request answers, the counter,
the cost and the limits are in [Statement time limits](data-statement-limits.md).

## The database TLS check

Shortly after startup, Stratum checks on its own thread whether each data source bean's connection
to PostgreSQL is encrypted, and logs one WARN for each that is not, in every profile. How to
encrypt the connection, how to silence the check, and its limits are in
[The database TLS check](data-tls-check.md).

## The PostgreSQL login limit

Every new connection to PostgreSQL, from a pool or from Flyway's or Liquibase's own connection, gets
the driver's `loginTimeout` from the pool's wait: 3 seconds by default. How to set your own, what
it costs and what happens against a database that never answers are in
[The PostgreSQL login limit](data-login-limit.md).

## Transactions

In strict mode, every transaction run by Spring Boot's transaction manager that sets no limit of
its own ends after 60 seconds with an error. Its statements end by its deadline too. Give one method more time with
`@Transactional(timeout = 300)`, in seconds, or change the default:

```properties
spring.transaction.default-timeout=300s
```

```yaml
spring:
  transaction:
    default-timeout: 300s
```

**Spring Batch.** When Spring Batch is on the classpath, Stratum sets no transaction time limit,
because a chunk of a batch job can run longer than any web request. A service that runs both
batch jobs and a web API sets `spring.transaction.default-timeout` itself. In Spring Batch 6, a
chunk step runs in the transaction manager you give it:

```java
new StepBuilder("importOrders", jobRepository)
    .<OrderLine, Order>chunk(500)
    .transactionManager(transactionManager)
    .reader(reader)
    .writer(writer)
    .build();
```

Without `transactionManager(...)`, the step uses a `ResourcelessTransactionManager`: its writes
are not in a database transaction.

## Long work: batch jobs and exports

### Leak alarm

HikariCP can log a warning, "Apparent connection leak detected", with the stack trace of the code
that took a connection and held it longer than a threshold. Stratum leaves it **off**, HikariCP's
default, because of what it costs: HikariCP builds an exception, with its stack trace, and
schedules a task on every borrow of a connection. Stratum's limit for such a cost is 1 µs per
borrow. Measured on the raw HikariCP pool, with a 70-second threshold, on an Intel Xeon at
2.10 GHz with 4 cores, JDK 25.0.5 and PostgreSQL 18.6 in a container
([Benchmarking](../contributing.md#benchmarking-the-jdbc-boundary)):

| Borrow and return a connection | Without the alarm | With the alarm |
| --- | --- | --- |
| On 1 thread | about 0.20 µs | about 1.46 µs |
| On 16 threads at once | about 0.86 µs | about 26.8 µs |

To turn it on, for example while you look for a leak, set HikariCP's key, in milliseconds:

```properties
spring.datasource.hikari.leak-detection-threshold=70000
```

```yaml
spring:
  datasource:
    hikari:
      leak-detection-threshold: 70000
```

- It must be at least `2000` and at most `spring.datasource.hikari.max-lifetime` (30 minutes,
  `1800000`, unless you set it), or `0`, which turns it off. HikariCP would turn any other value
  off when the pool starts and log only a warning, so Stratum stops startup with `SX-DATA-1002`
  instead, naming both keys. A `max-lifetime` of `0` means no limit.
- Choose a threshold above your longest transaction, such as the transaction time limit plus 10
  seconds: 70 seconds with Stratum's 60-second default.
- It applies to the whole pool. A long report that holds a connection outside a transaction logs
  the warning too. Raise the threshold, or run the report as a batch job, below.

### A pool for batch jobs

Run the jobs in their own application, or with their own profile, and set
`spring.transaction.default-timeout` to the longest chunk, such as `30m`. If you turn the leak
alarm on, set its threshold to that plus 10 seconds, and raise `max-lifetime` above it.

### Streaming and fetch size

- Export many rows with `JdbcClient` or a DTO projection, never as managed entities, which
  Hibernate keeps in memory until the transaction ends.
- Stratum sets no fetch size, in either mode. A query reads its result as the driver does by
  default: the PostgreSQL driver reads every row into memory before the first `next()`.
- To stream, set a fetch size yourself, only where you need it:
  - `spring.jdbc.template.fetch-size` for `JdbcTemplate` and `JdbcClient` (Spring Boot's default,
    `-1`, sets none);
  - `spring.jpa.properties.hibernate.jdbc.fetch_size` for every Hibernate query, or the
    `org.hibernate.fetchSize` hint on one JPA query;
  - `Statement.setFetchSize` in your own JDBC code.
- The PostgreSQL driver streams only when the fetch size is above 0, the result set is
  forward-only and not holdable, and a transaction is open. MySQL also needs
  `useCursorFetch=true` in the URL.
- With a fetch size, the rows after the first batch are read in `ResultSet.next()`, after the
  statement returned. Those reads are outside `stratum.data.query-timeout` and the transaction's
  deadline, and only the network timeout bounds each one
  ([Statement time limits](data-statement-limits.md#limits)).
- Stratum leaves Spring Data's page size as it is: at most 2000 rows a page
  (`spring.data.web.pageable.max-page-size`).

## Health and metrics

With Spring Boot's actuator, which the observability starter brings, the database health check
`db` is part of `/actuator/health`. It is not in the readiness group (`/actuator/health/readiness`),
and Stratum does not add it. The pool's metrics,
such as `hikaricp.connections.pending`, come from Spring Boot.

## Configure in Java

`stratum.data.query-timeout` and `stratum.data.deadline-margin` can also be set in Java, through a
`DataPropertiesCustomizer` class, and Java wins over the files:

```java
public class OrdersDataSettings implements DataPropertiesCustomizer {

  @Override
  public DataProperties customize(DataProperties properties) {
    return properties.toBuilder()
        .queryTimeout(Duration.ofSeconds(10))
        .deadlineMargin(Duration.ofMillis(300))
        .build();
  }
}
```

The customizer is not a bean: list the class in the service's `META-INF/spring.factories` under
`com.exepex.stratum.data.DataPropertiesCustomizer`. A customizer declared as a bean stops startup
with `SX-CORE-1004`.

Set any Spring Boot key above in Java as a default property, which wins over Stratum's default:

```java
var application = new SpringApplication(OrdersApplication.class);
application.setDefaultProperties(Map.of("spring.datasource.hikari.connection-timeout", 10000));
application.run(args);
```

## Customize

- Every `spring.datasource.*` and `spring.transaction.*` property works and wins.
- Your own `DataSource` bean replaces Spring Boot's pool, and with it the
  `spring.datasource.hikari.*` defaults.
- A bean named `stratumDatabaseTlsCheck` replaces the [TLS check](data-tls-check.md).
- A bean named `stratumPostgresLoginTimeout` replaces the [login limit](data-login-limit.md) of the pools.
- A bean named `stratumJdbcBoundary` replaces the post-processor that wraps every data source bean
  in the [clean errors boundary](#clean-database-errors). To leave one of your own data sources
  out, by its bean name, declare it with Stratum's `com.exepex.stratum.data.JdbcBoundaryPostProcessor` and a filter:

  ```java
  @Bean
  static BeanPostProcessor stratumJdbcBoundary() {
    return new JdbcBoundaryPostProcessor(beanName -> !beanName.equals("legacyDataSource"));
  }
  ```

  A data source you leave out has **no** boundary: its errors can carry data values into your
  logs, traces and responses. The filter sees bean names only. The method is `static` because the
  bean processes other beans.
- Flyway's and Liquibase's own connections ([Migrations](#migrations)) get the boundary of the one
  `JdbcBoundaryPostProcessor` bean that Spring would inject. Stratum's is a fallback bean, so one
  `JdbcBoundaryPostProcessor` bean of yours is the one used. When Spring finds no single one, these
  connections get **no** boundary: with none, such as when your `stratumJdbcBoundary` bean is of
  another type, with two or more of your own, or with two or more fallback beans and none of
  yours.
- A bean named `stratumFlywayBoundary`, of any type, replaces the customizer that wraps Flyway's own
  connection; a bean named `stratumLiquibaseBoundary` does the same for Liquibase.

## Switch it on or off

The module is on when a data starter is on the classpath. Leaving `stratum.data.enabled` unset, or
empty, is the same as `true`. `stratum.data.enabled=false` leaves Spring Boot's own defaults, and
no data source is wrapped, so database errors keep the driver's text. It turns off the data
starter's own defaults too.

## Troubleshooting

| Symptom | Likely cause and fix |
| --- | --- |
| `Connection is not available, request timed out after 3000ms` | The pool is exhausted: look for long transactions or slow queries first, then consider `spring.datasource.hikari.maximum-pool-size`. |
| "Apparent connection leak detected" in logs | A connection was held longer than `leak-detection-threshold`. The stack trace in the log shows where it was taken. |
| Startup fails with `SX-DATA-1002` | `spring.datasource.hikari.leak-detection-threshold` is under 2000 or above `max-lifetime`; see [Leak alarm](#leak-alarm). |
| `QueryTimeoutException` with `SX-DATA-1003`, or status 503 with `SX-DATA-1003` | A statement ran past its limit and Stratum cancelled it; the log's message names the limit and what set it. Make the statement faster, or give it more time; see [Statement time limits](data-statement-limits.md). |
| `PSQLException: Connection attempt timed out.` | The database accepted the connection and did not answer within the login limit; see [The PostgreSQL login limit](data-login-limit.md). |
| WARN "connects to the database without TLS" or "Could not check whether the data source ... uses TLS" | The connection is not encrypted, or the check could not ask the database; see [The database TLS check](data-tls-check.md). |
| `TransactionTimedOutException`, or a query timeout inside a transaction | A transaction ran longer than 60 seconds. Make it shorter, or give it a longer `@Transactional(timeout = ...)`. |
