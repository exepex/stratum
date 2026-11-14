# Data: `stratum-starter-data-jpa`

**Status: Partly built.** Spring Data JPA, with safe defaults for Hibernate, on top of the JDBC
boundary of [`stratum-data`](data.md): safe defaults for Spring Boot's connection pool (HikariCP)
and transactions, database errors that carry no data values, from your code and from Flyway and
Liquibase, a time limit on every SQL statement, and a check that the connection to the database is
encrypted.

[← Documentation home](../README.md) · Property prefix: `stratum.data`

## What it does

Database problems in production usually come from the same few settings: callers that wait 30
seconds for a busy pool, `open-in-view` keeping a connection busy during the whole web request,
transactions with no time limit, entities that no longer match the schema, and inserts sent one
row at a time. This starter sets these the safe way in every service. It sets only Spring Boot's
own keys, so every value your service sets wins. The pool, transaction and error parts come from
[`stratum-data`](data.md), which the starter brings; this page covers what it adds for Hibernate.

## Add it

```xml
<dependency>
  <groupId>io.github.exepex.stratum</groupId>
  <artifactId>stratum-starter-data-jpa</artifactId>
</dependency>
<!-- plus the JDBC driver for your database; Spring Boot manages its version -->
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>postgresql</artifactId>
  <scope>runtime</scope>
</dependency>
```

> [!NOTE]
> Until this starter is fully built, it is not published to Maven Central or listed in `stratum-bom`, so
> this dependency does not resolve yet ([Publishing](../contributing.md#publishing)).

The starter brings Spring Boot's `spring-boot-starter-data-jpa`: HikariCP, Hibernate and Spring
Data JPA, and Stratum's `stratum-data`. It brings no migration tool: add `spring-boot-starter-flyway` or
`spring-boot-starter-liquibase` yourself, and Spring Boot runs it before Hibernate starts. Their
errors are clean too; see [Data → Migrations](data.md#migrations). For a database that already has a schema, see
[Migrating a service](../guides/migrating-a-service.md#a-database-that-already-has-a-schema).

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

Everything [`stratum-data`](data.md#what-you-get) gives: clean database errors, statement time
limits, the TLS check, the short pool wait, the PostgreSQL login limit, the leak alarm check, the
transaction time limit and [query spans](data-query-spans.md). And for Hibernate:

| Feature | Behaviour | Applies |
| --- | --- | --- |
| Schema check | Hibernate checks at startup that your entities match the schema, and never changes the schema (`validate`) | Always |
| Batched writes | Hibernate sends inserts and updates in ordered batches of 50 rows | Always |
| Padded `IN` lists | `IN (...)` lists are padded to the next power of two, so the database reuses fewer query plans | Always |
| Quiet JDBC errors | Hibernate logs no JDBC error or warning itself; the [clean error](data.md#clean-database-errors) reaches your code | Always |
| No open-in-view | A web request releases its connection when your service method ends, not when the response is written | Strict mode |
| Safe paging | A query that pages a fetch join over a collection fails, instead of loading every row and paging in memory | Strict mode |

**Strict mode** is Stratum's default, `stratum.backward-compatible=false`. With
`stratum.backward-compatible=true`, the "Strict mode" rows are left at Spring Boot's own defaults,
because they can change what the service's callers see: a slower response, an error, or data
loaded differently. The "Always" rows change nothing a caller sees and apply in both modes
([ADR 0010](../adr/0010-modules-on-when-present-and-one-compatibility-switch.md)).

## Properties

The `stratum.data` properties, and every Spring Boot key Stratum gives a default, are in the one
table of [Data → Properties](data.md#properties). This starter's Hibernate keys are marked "data
JPA starter only" there. `stratum.data.enabled=false` turns off this starter's defaults too.

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

## Configure in Java

Set any Spring Boot key in [Data → Properties](data.md#properties) in Java as a default property, which wins over Stratum's default:

```java
var application = new SpringApplication(OrdersApplication.class);
application.setDefaultProperties(Map.of("spring.jpa.properties.hibernate.jdbc.batch_size", 20));
application.run(args);
```

A Hibernate key can also be set with your own `HibernatePropertiesCustomizer` bean, which runs
after Stratum's and wins. `stratum.data.query-timeout` is set in Java as in
[Data → Configure in Java](data.md#configure-in-java).

## Customize

- Every `spring.jpa.*` property works and wins; the pool and transaction keys are in
  [Data → Customize](data.md#customize).
- A bean named `stratumSchemaValidationDefault` replaces Stratum's schema default.
- The beans of the JDBC boundary, such as `stratumJdbcBoundary`, are replaced as
  [Data → Customize](data.md#customize) shows.

## Switch it on or off

The module is on when the starter is on the classpath. Leaving `stratum.data.enabled` unset, or
empty, is the same as `true`. `stratum.data.enabled=false` leaves Spring Boot's own defaults,
including `open-in-view=true`, Stratum reports no `SX-DATA-1001`, and no data source is
wrapped, so database errors keep the driver's text.

## Troubleshooting

The pool, leak alarm, statement limit, login limit, TLS check and transaction symptoms are in
[Data → Troubleshooting](data.md#troubleshooting).

| Symptom | Likely cause and fix |
| --- | --- |
| Startup fails with `SX-DATA-1001`: "Schema validation: missing column" | An entity changed without a migration. Add the migration, or fix the mapping. |
| `LazyInitializationException` | With open-in-view off, load what the response needs inside the `@Transactional` service method (fetch join or DTO projection). |
| `HibernateException: setFirstResult() or setMaxResults() specified with collection fetch join` | Page the parent rows first, then fetch their collections, or use a DTO projection. |
