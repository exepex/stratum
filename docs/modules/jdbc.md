# Data: `stratum-starter-jdbc`

**Status: Partly built.** Plain JDBC, through Spring's `JdbcClient` and `JdbcTemplate`, on top of
the JDBC boundary of [`stratum-data`](data.md): safe defaults for Spring Boot's connection pool
(HikariCP) and transactions, database errors that carry no data values, from your code and from
Flyway and Liquibase, a time limit on every SQL statement, and a check that the connection to the
database is encrypted. It brings no ORM.

[← Documentation home](../README.md) · Property prefix: `stratum.data`

## What it does

A service that reads and writes its database with SQL, and no Hibernate, gets the same safe
settings as one on [`stratum-starter-data-jpa`](data-jpa.md): a short wait for a busy pool, time
limits on statements and transactions, and database errors that never repeat the data that caused
them. It sets only Spring Boot's own keys, so every value your service sets wins. Everything it
does comes from [`stratum-data`](data.md), which the starter brings.

## Add it

```xml
<dependency>
  <groupId>io.github.exepex.stratum</groupId>
  <artifactId>stratum-starter-jdbc</artifactId>
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

The starter brings Spring Boot's `spring-boot-starter-jdbc`: HikariCP, `JdbcTemplate` and
`JdbcClient`, and Stratum's `stratum-data`. It brings no migration tool: add
`spring-boot-starter-flyway` or `spring-boot-starter-liquibase` yourself; their errors are clean
too ([Data → Migrations](data.md#migrations)).

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

Everything in [Data → What you get](data.md#what-you-get), in the same modes: clean database
errors, statement time limits, the TLS check, the short pool wait, the PostgreSQL login limit, the
leak alarm check, the transaction time limit and [query spans](data-query-spans.md). Stratum sets
no fetch size, and no `spring.jpa.*`
key.

## With the data JPA starter too

A service may have both starters, such as one that uses JPA for most tables and `JdbcClient` for a
report. It then gets each Stratum bean and each default once, and the Hibernate defaults of
[`stratum-starter-data-jpa`](data-jpa.md). One switch, `stratum.data.enabled`, turns both off.

## Properties

The `stratum.data` properties, and every Spring Boot key Stratum gives a default, are in the one
table of [Data → Properties](data.md#properties). The rows marked "data JPA starter only" do not
apply to this starter.

## Configure in Java

Set any Spring Boot key in [Data → Properties](data.md#properties) in Java as a default property,
which wins over Stratum's default:

```java
var application = new SpringApplication(OrdersApplication.class);
application.setDefaultProperties(Map.of("spring.datasource.hikari.connection-timeout", 5000));
application.run(args);
```

`stratum.data.query-timeout` is set in Java as in
[Data → Configure in Java](data.md#configure-in-java).

## Customize

Every bean of the JDBC boundary, such as `stratumJdbcBoundary`, is replaced as
[Data → Customize](data.md#customize) shows.

## Troubleshooting

The pool, leak alarm, statement limit, login limit, TLS check and transaction symptoms are in
[Data → Troubleshooting](data.md#troubleshooting).
