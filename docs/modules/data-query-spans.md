# Data: query spans

[← Data module](data.md) · Turned on by: tracing (Micrometer Tracing's OpenTelemetry bridge)

In a traced request, each SQL statement your service runs through a data source bean gets a client
span. The trace then shows which statement took the request's time, and on which database. The span
carries no SQL text, no value and no table name.

## What it does

When a statement executes inside a request whose trace is sampled, the JDBC boundary starts a span
just before the driver call and ends it when the call returns or fails. The span is a child of the
span that was current, such as the request's own span.

| Field | Value | Where it comes from |
| --- | --- | --- |
| Kind | `CLIENT` | |
| Name | The operation, such as `SELECT` | The first keyword of the statement's SQL |
| `db.system.name` | `postgresql`, `mysql`, `mariadb`, `h2`, `oracle.db`, `microsoft.sql_server`; `other_sql` for any other database | The driver's product name |
| `db.namespace` | The database's name, such as `orders`; left out when the driver cannot give it | The connection's catalog |
| `db.operation.name` | The same operation as the name | The first keyword of the statement's SQL |
| Error | The boundary's clean copy of the error, when the statement fails | [Clean database errors](data.md#clean-database-errors) |

The operation is the first word of the SQL after white space and comments, when it is one of
`SELECT`, `INSERT`, `UPDATE`, `DELETE`, `MERGE`, `WITH`, `CALL`, `CREATE`, `ALTER`, `DROP`,
`TRUNCATE`, `BEGIN`, `COMMIT`, `ROLLBACK`, `SET`, `SHOW`, `VALUES`, `LOCK`, `COPY` and `EXPLAIN`.
Anything else is `UNKNOWN`, such as a JDBC escape like `{call archive(?)}`.

- A prepared statement takes its operation from the SQL it was prepared with; a plain statement from
  the SQL each `execute` gets.
- A batch gets one span for its `executeBatch`. A plain batch whose statements start with different
  operations gets `BATCH`. Until the batch runs or is cleared, the boundary keeps a reference to each
  SQL text added, which the driver holds too, so nothing is copied; it reads them only when the
  trace is sampled.
- A statement that its [time limit](data-statement-limits.md) refuses to start still gets its span,
  which records `SX-DATA-1003`.
- No meter is added: the span is not a Micrometer Observation, so it creates no timer.

## Why there is no SQL text

The SQL of a statement can hold values: a literal written into the SQL, such as an email address,
reaches every trace backend and everyone who can read traces. Stratum puts no part of the SQL on the
span except its first keyword, checked against the list above. The table names are left out too,
because reading them means parsing the SQL, which costs far more than the span itself and can take
seconds on a hostile statement.

## When spans are on

Spans need all of these; there is no property of their own:

- the data module is on (`stratum.data.enabled`, on by default);
- Micrometer Tracing's OpenTelemetry bridge is on the class path, which the
  [observability starter](../../starters/stratum-starter-observability/README.md) brings, and the service has a `Tracer` bean;
- the statement runs in a trace that is sampled, see [Sampling](observability-traces.md#sampling).

A statement outside any request, such as one at startup or in a scheduled job with no span of its
own, gets no span. Neither does a statement in a request that is not sampled. A service with the
Brave bridge instead of the OpenTelemetry one gets no spans.

To turn spans off, keep fewer traces with `management.tracing.sampling.probability`, or turn the
data module off with `stratum.data.enabled=false`, which also removes the rest of the JDBC boundary.

## Cost

Measured with JMH on the build machine, with OpenTelemetry's SDK and an exporter that drops every
span, so the cost of sending spans to a backend is not included:

| Case | Extra time per statement | Extra memory per statement |
| --- | --- | --- |
| No request, or a request that is not sampled | under 10 ns | none |
| A sampled request | about 0.6 µs | about 1.1 KB |

These come from `StatementSpansBenchmark`, which runs the execute through the boundary with no
database round trip, so the figures are the span's own cost. A round trip to PostgreSQL takes tens
of microseconds, so on a real database the span is a small share of each statement. On
PostgreSQL (`QuerySpanCallBenchmark`), a sampled request's statement allocates about 2 KB more than
before query spans; an unsampled one allocates no more.

A request that runs many statements, such as a loop or a lazy-loading N+1 pattern, gets one span per
statement, so a sampled trace can hold thousands of spans. Stratum puts no limit on the number of
spans in a trace: the sample rate sets the volume. When spans are made faster than they can be sent,
the OpenTelemetry SDK's export queue fills and drops them, and Stratum counts each dropped span in
`stratum.observability.spans.dropped`; see [Dropped spans](observability-traces.md#dropped-spans)
and [Span size](observability-traces.md#span-size).

## Limits

- A statement through a data source that is not a bean, or a bean the boundary skips, gets no span.
- The connections Flyway and Liquibase build from their own `url` get no spans.
- On PostgreSQL, the driver reads the database name once per physical connection with
  `select current_catalog` and keeps it; in a transaction that has already failed, that read fails
  and the span has no `db.namespace`.
- R2DBC is not covered.
