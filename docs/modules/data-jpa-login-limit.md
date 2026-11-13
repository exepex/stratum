# Data: the PostgreSQL login limit

[← Data module](data-jpa.md) · Driver property: `loginTimeout`

The PostgreSQL driver waits with no limit, by default, for a database to answer a new connection.
A database that accepts the connection and then never answers, such as a hung server, would hold
the caller for good: a request thread, the pool's own thread, or the service's startup. The data
starter gives every new connection to PostgreSQL a login limit, in both modes and in every profile.

## What it does

- **Spring Boot's pool**, and every other HikariCP pool bean with a `jdbc:postgresql:` URL, gets the
  driver's `loginTimeout` from the pool's own `connection-timeout`, in whole seconds: 3 seconds with
  Stratum's default of 3000 ms, and 30 seconds for a pool of yours that keeps HikariCP's default.
- **The connection Spring Boot builds for Flyway or Liquibase**, from `spring.flyway.url`,
  `spring.liquibase.url`, or only their `user` or `password`, gets the limit of Spring Boot's pool,
  from `spring.datasource.hikari.connection-timeout`: 3 seconds by default.
- A pool whose `connection-timeout` is `0`, which HikariCP reads as no limit, gets no login limit.
- Other databases are left as they are.

Against a database that never answers, Stratum's tests measured:

- the first borrow from Spring Boot's pool fails after about 4 seconds: the 3-second attempt, then
  HikariCP's 1-second pause before it gives up starting the pool. Each later borrow tries again and
  fails the same way until the pool starts;
- a migration stops startup after about 3 seconds.

On a pool that has started, a borrow still waits at most its `connection-timeout`, and the pool's
own attempt to add a connection ends after the login limit instead of never.

## Set your own limit

Your own `loginTimeout` always wins, in the URL or in the pool's data-source properties. `0` means
no limit.

```properties
spring.datasource.url=jdbc:postgresql://db.example.com:5432/orders?loginTimeout=10
# or, for the pool only
spring.datasource.hikari.data-source-properties.loginTimeout=10
```

```yaml
spring:
  datasource:
    url: "jdbc:postgresql://db.example.com:5432/orders?loginTimeout=10"
    hikari:
      data-source-properties:
        # or, for the pool only
        loginTimeout: 10
```

- The driver reads the name `loginTimeout` only, in exactly that case. Set it in the URL, in
  `application.properties` or in YAML, never as an environment variable:
  `SPRING_DATASOURCE_HIKARI_DATASOURCEPROPERTIES_LOGINTIMEOUT` reaches the driver as
  `logintimeout`, which it ignores, so Stratum's limit applies.
- A migration connection takes its own limit from its URL:

  ```properties
  spring.flyway.url=jdbc:postgresql://db.example.com:5432/orders?loginTimeout=10
  ```

  ```yaml
  spring:
    flyway:
      url: "jdbc:postgresql://db.example.com:5432/orders?loginTimeout=10"
  ```

- A bean named `stratumPostgresLoginTimeout` replaces the post-processor that limits the pools.

## What it costs

- With a limit, the driver opens each new connection on a thread of its own, named
  `PostgreSQL JDBC driver connection thread`, and the caller waits for it. That is once per new
  connection, not per borrow or per statement. On Stratum's test database, opening a connection
  took a median of 7.5 ms with the limit and 7.0 ms without it; starting and ending one such
  thread took a median of about 0.1 ms.
- Any line the driver logs during a connection attempt is logged from that thread, so it carries no
  correlation ID. The error your code gets comes on its own thread.

## A database that never answers

When the limit passes, the driver gives up the attempt, but its thread stays blocked reading the
connection the database never answers, and that connection stays open. Both end only when the
database or the network closes the connection. Stratum's test saw one more live thread after each
failed borrow. How fast they grow is bounded by how often HikariCP tries:

- before the pool has started, borrows take turns, so at most one new thread every 4 seconds per
  pool;
- once the pool has started, only its one `connection-adder` thread connects: at most one new
  thread every 3 seconds at first, and every 8 seconds once HikariCP's pause between attempts has
  grown to its 5-second cap.

That is at most 900 threads, each with one open connection, per pool per hour. A database that does
not accept the connection at all is different: the driver's `connectTimeout`, 10 seconds by
default, ends each attempt's thread, so at most a few are alive per pool.

The driver also takes a `connectExecutor` property, the name of your own executor class for these
attempts, instead of a new thread for each. It is **not tested by Stratum**. The driver requires
that the executor "must support thread interrupts and clear interrupt flags after task execution".
An abandoned attempt holds its executor's thread until its read returns, so against a database that
never answers a fixed-size executor can fill up. It is no cure for the threads above.

## Limits

- A pool that has already started when Stratum sees it, such as one built with
  `new HikariDataSource(config)`, gets no limit; one DEBUG line names its bean.
- A data source bean that is not a HikariCP pool gets no limit, a `@FlywayDataSource` or
  `@LiquibaseDataSource` bean included. The one exception is a `SimpleDriverDataSource` that Flyway
  or Liquibase uses, which gets the migration limit.
- A pool that another post-processor has wrapped before Stratum sees it gets no limit.
- `stratum.data.enabled=false` turns the limit off with the rest of the data module.
