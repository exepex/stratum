# Data: statement time limits

[← Data module](data.md) · Properties: `stratum.data.query-timeout`, `stratum.data.deadline-margin`

A SQL statement that never ends holds its connection, and a few of them empty the pool for every
other request. The data starter gives every statement a time limit and cancels it when the limit
passes, in every service and through every library: Hibernate, `JdbcTemplate`, `JdbcClient`,
Spring Data and your own JDBC code.

## The limit of a statement

Each statement ends by the first of these limits that applies:

| Limit | Where it comes from | Applies |
| --- | --- | --- |
| The pool's limit | `stratum.data.query-timeout`: `30s` by default | Strict mode, or when you set it |
| The code's own limit | `setQueryTimeout` on the statement, or the query timeout of `JdbcTemplate`, Hibernate or Spring Data | Always |
| The transaction's deadline | The time left in the transaction, such as `@Transactional(timeout = 10)` or `spring.transaction.default-timeout` | Always |
| The web request's deadline | The time left of the web request the statement serves, minus `stratum.data.deadline-margin` | In a web request with a time limit; see [In a web request](#in-a-web-request) |

Outside a transaction with a time limit, the code's own limit replaces the pool's, so one report
query can have longer: `jdbcTemplate.setQueryTimeout(600)`, also in a web request. Inside one, the
statement ends by the earliest of them all. A statement that starts after a deadline has passed
fails at once, without reaching the database.

Stratum cancels the statement itself, within 50 milliseconds after the limit and never before it,
to the millisecond rather than the whole second. The driver never gets the limit, so a driver's
own timer never runs; that timer costs more per statement than Stratum's (see
[the measurements](#cost)).

Each connection also gets a network timeout 30 seconds longer than its statement's limit, so a
statement whose database stops answering ends too: from the borrow on, the pool's limit plus 30
seconds, raised before a statement with a longer limit and never lowered. HikariCP restores its
own value when the connection goes back to the pool.

## When a statement is cancelled

The statement fails with `SX-DATA-1003`, a `java.sql.SQLTimeoutException` with SQLState `57014`.
Spring and Hibernate turn it into their `QueryTimeoutException`, as for any cancelled statement.

- **Strict mode:** a web request that fails with it is answered with status 503, the code and its
  description, and no detail. To change the status, set
  `stratum.web.error.statuses.SX-DATA-1003`, as in [Database failures](web-database-errors.md). The limit, the run time and the data source bean name are only in the
  exception's message, which goes to the log.
- **Backward-compatible mode** (`stratum.backward-compatible=true`): the answer stays Spring
  Boot's own, as before. The code appears in the log only.

A statement cancelled by your own code or by the database, such as PostgreSQL's
`statement_timeout`, keeps the driver's error and is not counted.

The counter `stratum.data.timeouts` counts each cancelled statement, and each borrow that failed
because its web request had no time left, with the tags `pool`, the data source bean, and `cause`:
`query-timeout`, `caller`, `transaction` or `request`. It needs Micrometer, which the
observability starter brings.

## In a web request

In a web request with a time limit, set by `stratum.web.timeout.*` or `@ResponseTimeout`, each
statement also ends by the request's time left minus `stratum.data.deadline-margin`, 150ms by
default. The statement is then cancelled, and the request answered with `SX-DATA-1003`, before the
web module's own limit has to interrupt a thread that waits on the database. That interrupt can
break the connection: on a virtual thread, the driver's read fails at once and HikariCP has to
replace the connection.

The margin covers the time a cancel takes: up to 50 milliseconds before the sweeper sees the
limit, then the cancel's own round trip. Measured on PostgreSQL 18.6 on 4 cores, a statement
ended at most 58 ms after its limit when cancelled alone, and at most 132 ms with 64 cancelled at
once. Over a real network, add a round trip and TLS to each cancel.

A connection borrow is checked too, with the same request time left minus the margin: before the
pool is asked, and again once it has handed out the connection. A borrow with no time left fails
with `SX-DATA-1003`; a connection borrowed too late goes back to the pool before any SQL is sent.

- The request's deadline reaches the threads the web module carries it to: the request's own
  thread, WebFlux operators, and `@Async` work on executors that carry the context. See
  [where the deadline reaches](web-timeouts.md#read-the-time-left). Work outside a web request,
  such as a Kafka listener, a batch job or a scheduled task, gets no request limit.
- A request whose limit is the margin or less, such as `@ResponseTimeout(value = 100, unit =
  MILLISECONDS)`, leaves its statements no time: each fails at once, counted with
  `cause=request`. Keep every request limit well above the margin.
- On Spring MVC, a response that has started still ends its statements by the limit minus the
  margin, until the limit has passed; see [Downloads and streams](web-timeouts.md#downloads-and-streams).

Set the margin:

```properties
stratum.data.deadline-margin=300ms
```

```yaml
stratum:
  data:
    deadline-margin: 300ms
```

`0` means no margin: statements end at the request's limit itself, as the web module interrupts
the request. A negative value stops startup with `SX-CORE-1003`. To set it in Java, see
[Configure in Java](data.md#configure-in-java).

## Set the limit

```properties
stratum.data.query-timeout=10s
```

```yaml
stratum:
  data:
    query-timeout: 10s
```

`0` means no pool limit; the code's own limits and the transactions' deadlines still apply. A
negative value stops startup with `SX-CORE-1003`. The limit applies to every data source bean.
Flyway's and Liquibase's own connections get none.

To set it in Java, see [Configure in Java](data.md#configure-in-java).

## Cost

Measured with JMH on 4 cores against PostgreSQL 18.6, each with and without the setting in the
same run (`StatementLimitsBenchmark`, `StatementSweeperBenchmark` and `StatementTimerBenchmark` in
the module's tests; the command is in [contributing](../contributing.md#benchmarking-the-jdbc-boundary)).
The web request's time left is read from `ThreadDeadline`, a stand-in for the web module's bean:

| What | 1 thread | 16 threads |
| --- | --- | --- |
| Stratum's limit, per statement, no database | +0.034 µs | +0.14 µs |
| Stratum's limit around `SELECT 1` | within noise | within noise |
| The driver's own timer around `SELECT 1` (not used) | +7.9 µs | within noise |
| The connection's slot, per borrow | +0.065 µs | **+2.7 µs** |
| The web request's time left, per borrow, read before and after it | +0.068 µs | +0.16 µs, within noise |
| The web request's time left, per statement, around `SELECT 1` | within noise | within noise |

At 16 threads borrowing at once on 4 cores, each borrow costs 2.7 µs more, because every borrow
registers the connection with the thread that cancels statements. The owner accepted this cost;
issue #365 tracks a cheaper registration.

## Limits

- A statement is cancelled up to 50 milliseconds after its limit.
- A limit under 50 milliseconds can be missed: the cancel can come before the driver has sent the
  statement, and the statement then runs to the connection's network timeout.
- Rows read from a `ResultSet` after the statement returned are bounded by the network timeout
  only.
- One connection runs one statement at a time, as JDBC requires. Two threads that run statements
  on the same connection at once leave the earlier statement with only the network timeout.
- A routing data source over several data source beans finds no transaction under those beans, so
  there only the code's own limit, the pool's and the web request's deadline apply.
- The cancel is standard JDBC `cancel()`, tested on PostgreSQL only.
- A connection the service never closes stays registered while it stays borrowed; with HikariCP
  that is at most `maximum-pool-size` connections.
