# Data: `stratum-starter-data-jpa`

**Status: Partly built.** Spring Data JPA, with safe defaults for Spring Boot's connection pool
(HikariCP), Hibernate and transactions. Clean database exceptions, query timeouts, a default fetch
size and Stratum's connection leak alarm are **planned**.

[← Documentation home](../README.md) · Property prefix: `stratum.data`

## What it does

Database problems in production usually come from the same few settings: callers that wait 30
seconds for a busy pool, `open-in-view` keeping a connection busy during the whole web request,
transactions with no time limit, entities that no longer match the schema, and inserts sent one
row at a time. This starter sets these the safe way in every service. It sets only Spring Boot's
own keys, so every value your service sets wins.

## Add it

```xml
<dependency>
  <groupId>com.exepex.stratum</groupId>
  <artifactId>stratum-starter-data-jpa</artifactId>
</dependency>
<!-- plus the JDBC driver for your database; Spring Boot manages its version -->
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>postgresql</artifactId>
  <scope>runtime</scope>
</dependency>
```

The starter brings Spring Boot's `spring-boot-starter-data-jpa`: HikariCP, Hibernate and Spring
Data JPA. It brings no migration tool: add `spring-boot-starter-flyway` or
`spring-boot-starter-liquibase` yourself, and Spring Boot runs it before Hibernate starts.

The module is on as soon as the starter is on the classpath; no line is needed. To turn it off,
set in `application.properties`:

```properties
stratum.data.enabled=false
```

or in `application.yml`:

```yaml
stratum:
  data:
    enabled: false
```

**Required:** the standard Spring Boot connection settings, in `application.properties`:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/orders
spring.datasource.username=orders
spring.datasource.password=${ORDERS_DB_PASSWORD}
```

or in `application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/orders
    username: orders
    password: ${ORDERS_DB_PASSWORD}
```

## What you get

| Feature | Behaviour | Applies |
| --- | --- | --- |
| Short pool wait | A caller waits at most 3 seconds for a free connection, then gets an error, instead of 30 seconds | Always |
| Schema check | Hibernate checks at startup that your entities match the schema, and never changes the schema (`validate`) | Always |
| Batched writes | Hibernate sends inserts and updates in ordered batches of 50 rows | Always |
| Padded `IN` lists | `IN (...)` lists are padded to the next power of two, so the database reuses fewer query plans | Always |
| No open-in-view | A web request releases its connection when your service method ends, not when the response is written | Strict mode |
| Safe paging | A query that pages a fetch join over a collection fails, instead of loading every row and paging in memory | Strict mode |
| Transaction time limit | Every transaction that sets no limit of its own gets 60 seconds | Strict mode, without Spring Batch |

**Strict mode** is Stratum's default, `stratum.backward-compatible=false`. A service already
running with consumers sets `stratum.backward-compatible=true` while it moves to Stratum; then the
"Strict mode" rows are left at Spring Boot's own defaults, because they can change what the
service's callers see: a slower response, an error, or data loaded differently. The "Always" rows
change nothing a caller sees and apply in both modes
([ADR 0010](../adr/0010-modules-on-when-present-and-one-compatibility-switch.md)).

## Properties

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `stratum.data.enabled` | boolean | `true` | Whether the module is on. With `false`, the starter sets none of the defaults below, and Spring Boot's own apply. |

Spring Boot properties this module gives a default (your values win):

| Spring Boot property | Type | Stratum default | Spring Boot's default | Meaning |
| --- | --- | --- | --- | --- |
| `spring.datasource.hikari.connection-timeout` | long (ms) | `3000` | `30000` (HikariCP) | How long a caller waits for a free connection. |
| `spring.jpa.hibernate.ddl-auto` | string | `validate`, see [Schema check](#schema-check) | `none`, or `create-drop` for an in-memory database | What Hibernate does with the schema at startup. |
| `spring.jpa.properties.hibernate.jdbc.batch_size` | int | `50` | unset: no batching | How many rows Hibernate sends in one batch. |
| `spring.jpa.properties.hibernate.order_inserts` | boolean | `true` | `false` | Groups inserts by table, so they can be batched. |
| `spring.jpa.properties.hibernate.order_updates` | boolean | `true` | `false` | Groups updates by table, so they can be batched. |
| `spring.jpa.properties.hibernate.query.in_clause_parameter_padding` | boolean | `true` | `false` | Pads `IN` lists to the next power of two. |
| `spring.jpa.open-in-view` | boolean | `false` (strict mode) | `true`, with a warning at startup | Whether a web request keeps its connection until the response is written. |
| `spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch` | boolean | `true` (strict mode) | `false` | Whether paging a fetch join over a collection fails instead of paging in memory. |
| `spring.transaction.default-timeout` | duration | `60s` (strict mode, without Spring Batch) | unset: no limit | The time limit of a transaction that sets none. |

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

## Schema check

Stratum sets Hibernate's schema setting to `validate` when your service set none of these:

1. `spring.jpa.properties.hibernate.hbm2ddl.auto`;
2. `spring.jpa.hibernate.ddl-auto`;
3. `spring.jpa.properties.jakarta.persistence.schema-generation.database.action`;
4. `spring.jpa.generate-ddl=true`.

Any of them, even with the value `none`, keeps your choice. In-memory databases keep Boot's
default: create-drop, or none when Flyway or Liquibase manages the schema
(HibernateDefaultDdlAutoProvider:30-37). A team that points Flyway at its own database with
`spring.flyway.url` keeps `create-drop` for an in-memory database.

When an entity does not match the schema, startup stops with `SX-DATA-1001`:

```text
Description:

SX-DATA-1001: An entity does not match the database schema: Schema validation: missing column [customer_id] in table [orders]

Action:

Fix the entity mapping or add the missing migration, or, to stop Hibernate checking the schema, set spring.jpa.hibernate.ddl-auto=none. ...
```

## Transactions

In strict mode, every transaction run by Spring Boot's transaction manager that sets no limit of
its own ends after 60 seconds with an error. Give one method more time with
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

HikariCP can log a warning, "Apparent connection leak detected", with the stack trace of the code
that took a connection and held it too long. Stratum's own default for it is **planned** and off
today; until then, set HikariCP's key yourself, in milliseconds:

```properties
spring.datasource.hikari.leak-detection-threshold=70000
```

```yaml
spring:
  datasource:
    hikari:
      leak-detection-threshold: 70000
```

- It must be at least `2000` and at most `spring.datasource.hikari.max-lifetime`. Otherwise
  HikariCP turns it off when the pool starts and logs one warning: "leakDetectionThreshold is less
  than 2000ms or more than maxLifetime, disabling it".
- It applies to the whole pool. A long report that holds a connection outside a transaction logs
  the warning too. Raise the threshold, or run the report as a batch job, below.
- **A pool for batch jobs:** run the jobs in their own application, or with their own profile, and
  set `spring.transaction.default-timeout` to the longest chunk, such as `30m`, and the leak
  threshold to that plus 10 seconds, raising `max-lifetime` above it.

### Streaming and fetch size

- Export many rows with `JdbcClient` or a DTO projection, never as managed entities, which
  Hibernate keeps in memory until the transaction ends.
- PostgreSQL streams rows only inside a transaction, with a fetch size set; otherwise the driver
  reads every row into memory first. MySQL also needs `useCursorFetch=true` in the URL.
- Set the fetch size with Spring Boot's `spring.jdbc.template.fetch-size` for `JdbcTemplate` and
  `JdbcClient`, and with `spring.jpa.properties.hibernate.jdbc.fetch_size` for Hibernate. Stratum's
  own default fetch size is **planned**.
- Stratum leaves Spring Data's page size as it is: at most 2000 rows a page
  (`spring.data.web.pageable.max-page-size`).

## Health and metrics

With Spring Boot's actuator, which the observability starter brings, the database health check
`db` is part of `/actuator/health`. It is not in the readiness group (`/actuator/health/readiness`),
and Stratum does not add it. The pool's metrics,
such as `hikaricp.connections.pending`, come from Spring Boot.

## Configure in Java

This module has no `stratum.data.*` setting besides its switch, so there is no customizer yet; a
`DataPropertiesCustomizer` is **planned** for the settings to come. Set any Spring Boot key above
in Java as a default property, which wins over Stratum's default:

```java
var application = new SpringApplication(OrdersApplication.class);
application.setDefaultProperties(Map.of("spring.datasource.hikari.connection-timeout", 10000));
application.run(args);
```

A Hibernate key can also be set with your own `HibernatePropertiesCustomizer` bean, which runs
after Stratum's and wins.

## Customize

- Every `spring.datasource.*`, `spring.jpa.*` and `spring.transaction.*` property works and wins.
- Your own `DataSource` bean replaces Spring Boot's pool, and with it the
  `spring.datasource.hikari.*` defaults.
- A bean named `stratumSchemaValidationDefault` replaces Stratum's schema default.

## Switch it on or off

The module is on when the starter is on the classpath. Leaving `stratum.data.enabled` unset, or
empty, is the same as `true`. `stratum.data.enabled=false` leaves Spring Boot's own defaults,
including `open-in-view=true`, and Stratum reports no `SX-DATA-1001`.

## Troubleshooting

| Symptom | Likely cause and fix |
| --- | --- |
| Startup fails with `SX-DATA-1001`: "Schema validation: missing column" | An entity changed without a migration. Add the migration, or fix the mapping. |
| `Connection is not available, request timed out after 3000ms` | The pool is exhausted: look for long transactions or slow queries first, then consider `spring.datasource.hikari.maximum-pool-size`. |
| "Apparent connection leak detected" in logs | A connection was held longer than `leak-detection-threshold`. The stack trace in the log shows where it was taken. |
| `LazyInitializationException` | With open-in-view off, load what the response needs inside the `@Transactional` service method (fetch join or DTO projection). |
| `HibernateException: setFirstResult() or setMaxResults() specified with collection fetch join` | Page the parent rows first, then fetch their collections, or use a DTO projection. |
| `TransactionTimedOutException`, or a query timeout inside a transaction | A transaction ran longer than 60 seconds. Make it shorter, or give it a longer `@Transactional(timeout = ...)`. |
