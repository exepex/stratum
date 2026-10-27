# Data: `stratum-starter-data-jpa`

**Status: Planned.** Relational database access with Spring Data JPA, a tuned connection pool, schema
migrations with Flyway, auditing columns and slow-query logging.

[← Documentation home](../README.md) · Property prefix: `stratum.data`

## What it does

Database problems in production usually come from the same few settings: a pool that is too big or
has no timeout, `open-in-view` keeping connections busy during the whole request, schemas changed
by hand, and slow queries nobody notices. This starter sets these the safe way in every service.

## Add it

```xml
<dependency>
  <groupId>com.exepex.stratum</groupId>
  <artifactId>stratum-starter-data-jpa</artifactId>
</dependency>
<!-- plus the JDBC driver for your database; Stratum manages its version -->
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>postgresql</artifactId>
  <scope>runtime</scope>
</dependency>
```

**Required:** the standard Spring Boot connection settings:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/orders
    username: orders
    password: ${ORDERS_DB_PASSWORD}
```

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/orders
spring.datasource.username=orders
spring.datasource.password=${ORDERS_DB_PASSWORD}
```

## What you get

| Feature | Behaviour |
| --- | --- |
| Connection pool | HikariCP with a bounded size, connection timeout and leak detection |
| No open-in-view | Connections are released when your service method ends, not when the HTTP response is written |
| Migrations | Flyway runs scripts from `src/main/resources/db/migration` at startup |
| Schema safety | Hibernate validates entities against the schema and never changes it (`ddl-auto=validate`) |
| Auditing | Entities extending `AuditableEntity` get `createdAt`, `updatedAt`, `createdBy`, `updatedBy` filled in |
| Slow queries | Queries slower than a threshold are logged at `WARN` |
| Metrics and health | Pool metrics and a database health check in the readiness group |

### Migrations in one minute

Each change to the schema is a numbered SQL file, applied once and in order:

```text
src/main/resources/db/migration/
├── V1__create_orders.sql
├── V2__add_order_status.sql
└── V3__index_orders_by_customer.sql
```

Never edit a file that has already run in any shared environment; add a new one instead.

### Auditing

```java
@Entity
class Order extends AuditableEntity {

  @Id
  private UUID id;

  private String status;
}
```

`createdBy` and `updatedBy` come from the authenticated caller (the token's subject) when the
security starter is present, and from `stratum.data.auditing.system-user` otherwise.

## Properties

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `stratum.data.enabled` | boolean | `true` | Switches the whole starter on or off. |
| `stratum.data.pool.max-size` | int | `10` | Maximum connections. More is rarely faster; see the tip below. |
| `stratum.data.pool.min-idle` | int | `2` | Connections kept open when idle. |
| `stratum.data.pool.connection-timeout` | duration | `3s` | Wait for a free connection before failing. |
| `stratum.data.pool.leak-detection-threshold` | duration | `30s` | Logs a warning if a connection is held longer. |
| `stratum.data.slow-query-threshold` | duration | `500ms` | Queries slower than this are logged at `WARN`. |
| `stratum.data.auditing.enabled` | boolean | `true` | Fills auditing columns. |
| `stratum.data.auditing.system-user` | string | `system` | `createdBy` value when no caller is authenticated. |
| `stratum.data.migrations.enabled` | boolean | `true` | Runs Flyway at startup. |
| `stratum.data.migrations.locations` | list | `classpath:db/migration` | Where migration scripts are found. |

Spring Boot properties this starter sets by default (your values win):

| Spring Boot property | Stratum default |
| --- | --- |
| `spring.jpa.open-in-view` | `false` |
| `spring.jpa.hibernate.ddl-auto` | `validate` |
| `spring.datasource.hikari.maximum-pool-size` | value of `stratum.data.pool.max-size` |
| `spring.datasource.hikari.connection-timeout` | value of `stratum.data.pool.connection-timeout` |

> [!TIP]
> Size the pool from the database's limit, not from your traffic: `instances × max-size` must stay
> below what the database allows. A small pool with short transactions serves more requests than
> a big pool with long ones.

## Examples

```yaml
stratum:
  data:
    pool:
      max-size: 20
      connection-timeout: 2s
    slow-query-threshold: 200ms
    migrations:
      locations:
        - classpath:db/migration
        - classpath:db/seed
```

```properties
stratum.data.pool.max-size=20
stratum.data.pool.connection-timeout=2s
stratum.data.slow-query-threshold=200ms
stratum.data.migrations.locations=classpath:db/migration,classpath:db/seed
```

## Configure in Java

**Planned.** You will also be able to set every property on this page in Java code, through this module's
customizer or builder, with the same names and defaults. This section will show how once that API is designed.

## Customize

- Any `spring.datasource.*`, `spring.jpa.*` and `spring.flyway.*` property works and wins.
- Your own `DataSource` bean replaces Stratum's pool settings completely.
- Your own `AuditorAware<String>` bean decides who `createdBy` is.

## Switch it off

`stratum.data.enabled=false` leaves Spring Boot's own JPA defaults (including `open-in-view=true`).

## Troubleshooting

| Symptom | Likely cause and fix |
| --- | --- |
| Startup fails: "Schema-validation: missing column" | An entity changed without a migration. Add a `V<n>__...sql` script. |
| `Connection is not available, request timed out after 3000ms` | The pool is exhausted: look for long transactions or slow queries first, then consider `pool.max-size`. |
| "Apparent connection leak detected" in logs | A connection was held over 30 s. The stack trace in the log shows where it was taken. |
| `LazyInitializationException` | With open-in-view off, load what the response needs inside the `@Transactional` service method (fetch join or DTO projection). |
