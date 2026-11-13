# Data: the database TLS check

[← Data module](data.md) · Logger: `com.exepex.stratum.data.DatabaseTlsCheck`

A connection to the database that is not encrypted sends every query, every row and the password
in clear text across the network. Shortly after the service starts, the data starter checks
whether each data source bean's connection is encrypted, and logs one WARN for each that is not.

## What it does

Once the application is ready, one daemon thread, `stratum-database-tls-check`, borrows one
connection from each data source bean in turn, asks the database whether the session is encrypted,
returns the connection and ends.

- **Encrypted**: nothing is logged.
- **Not encrypted**: one WARN, for example
  `The data source 'orders' connects to the database without TLS. Set sslmode=verify-full in the JDBC URL of data source 'orders'.`
  For Spring Boot's own data source, `dataSource`, that URL is `spring.datasource.url`.
- **Could not be checked** (no connection, no permission, no answer): one WARN that names the data
  source and the error's class, never its message, since a driver's message can carry the host,
  the port or the URL.
- **A database other than PostgreSQL**: not checked; one DEBUG line.

The check never stops the service and never delays its startup or its readiness: it runs after the
application is ready, on its own thread, and catches every failure. Its lines carry no correlation
ID, since the check belongs to no request. It applies in both modes and in every profile, `local`
and `test` included.

The check opens each pool shortly after startup instead of at its first use: the pool's
`Starting...` line and its connections then appear in the log a moment after the service is up.

## Encrypt the connection to PostgreSQL

Set `sslmode=verify-full` in the JDBC URL, with the server's CA certificate, so the driver both
encrypts the connection and checks that it talks to the right server:

```properties
spring.datasource.url=jdbc:postgresql://db.example.com:5432/orders?sslmode=verify-full&sslrootcert=/etc/ssl/db-ca.pem
```

```yaml
spring:
  datasource:
    url: "jdbc:postgresql://db.example.com:5432/orders?sslmode=verify-full&sslrootcert=/etc/ssl/db-ca.pem"
```

- Never leave `sslmode` out, and never set `sslmode=prefer`: `prefer` is the driver's default, and
  it stays unencrypted, without a word, when the server offers no TLS.
- `sslmode=require` encrypts but does not check the server's certificate, so the check passes
  while a server in the middle could read the traffic. Use `verify-full`.
- `ssl=true` on its own means `verify-full`.

Other databases are checked once Stratum has a reader for them.

## Turn it off

- Set its logger to `error` in the profile where you want it quiet, such as your `local` profile's
  file. This logger name is stable.
- Declare your own bean named `stratumDatabaseTlsCheck` to replace the check.
- `stratum.data.enabled=false` turns the whole data module off, the check included.

```properties
# Silences the check only
logging.level.com.exepex.stratum.data.DatabaseTlsCheck=error
# Turns the whole data module off
stratum.data.enabled=false
```

```yaml
logging:
  level:
    # Silences the check only
    com.exepex.stratum.data.DatabaseTlsCheck: error
stratum:
  data:
    # Turns the whole data module off
    enabled: false
```

## How long a check takes

A check of a database that answers takes one borrow and one short query, limited to 5 seconds.
For a database that does not answer, the check's thread waits as long as the pool and the driver
wait, and the service is up all the while.

- On a pool that has already started, the wait is the pool's `connection-timeout`: 3 seconds for
  Spring Boot's `dataSource`, which Stratum sets, and HikariCP's own 30 seconds for a pool not bound
  to `spring.datasource.hikari.*`.
- On a pool the check starts, as at startup, the PostgreSQL driver's `loginTimeout` decides:
  Stratum's [login limit](data-login-limit.md), 3 seconds for Spring Boot's pool, or your own.
  With Stratum's limit, the check of a server that never answers took about 4 seconds in Stratum's
  tests.
- With `spring.datasource.connection-fetch=lazy`, the first borrow can take that time twice.
- A data source that is not a HikariCP pool waits as long as it waits.

## Limits

- The check covers each data source **bean** that exists once the application is ready, not every
  connection: a later change to the URL or to TLS is checked again only after a restart.
- A `@Lazy` data source that nothing has used yet is not created, and not checked.
- A routing data source reaches only its default target. A routing bean and its target beans are
  each checked, so one server can get two WARNs.
- The connections Flyway and Liquibase build from their own `url` are not beans, and are not
  checked.
- Behind PgBouncer, the check sees PgBouncer's connection to the server, not the service's
  connection to PgBouncer.
- A session encrypted by Kerberos instead of TLS (the driver's `gssEncMode`, whose default is
  `allow`) shows as not encrypted and gets the WARN. So does a connection over a Unix socket.
- A failed check never stops startup. Failing startup on an unencrypted connection may follow later,
  by an explicit decision.
