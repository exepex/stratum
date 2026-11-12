# Data: statement time limits

[← Data module](data-jpa.md) · Property: `stratum.data.query-timeout`

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

Outside a transaction with a time limit, the code's own limit replaces the pool's, so one report
query can have longer: `jdbcTemplate.setQueryTimeout(600)`. Inside one, the statement ends by the
earliest of the three. A statement that starts after its transaction's deadline has passed fails at
once, without reaching the database.

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

- **Strict mode:** a web request that fails with it is answered with status 500, the code and its
  description, and no detail. The limit, the run time and the data source bean name are only in the
  exception's message, which goes to the log.
- **Backward-compatible mode** (`stratum.backward-compatible=true`): the answer stays Spring
  Boot's own, as before. The code appears in the log only.

A statement cancelled by your own code or by the database, such as PostgreSQL's
`statement_timeout`, keeps the driver's error and is not counted.

The counter `stratum.data.timeouts` counts each cancelled statement, with the tags `pool`, the
data source bean, and `cause`: `query-timeout`, `caller` or `transaction`. It needs Micrometer, which
the observability starter brings.

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

To set it in Java, see [Configure in Java](data-jpa.md#configure-in-java).

## Cost

Measured with JMH on 4 cores against PostgreSQL 18.6, each with and without the setting in the
same run (`StatementLimitsBenchmark`, `StatementSweeperBenchmark` and `StatementTimerBenchmark` in
the module's tests; the command is in [contributing](../contributing.md#benchmarking-the-jdbc-boundary)):

| What | 1 thread | 16 threads |
| --- | --- | --- |
| Stratum's limit, per statement, no database | +0.034 µs | +0.14 µs |
| Stratum's limit around `SELECT 1` | within noise | within noise |
| The driver's own timer around `SELECT 1` (not used) | +7.9 µs | within noise |
| The connection's slot, per borrow | +0.065 µs | **+2.7 µs** |

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
  there only the code's own limit and the pool's apply.
- The cancel is standard JDBC `cancel()`, tested on PostgreSQL only.
- A connection the service never closes stays registered while it stays borrowed; with HikariCP
  that is at most `maximum-pool-size` connections.
