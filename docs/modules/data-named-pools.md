# Data: named pools

[← Data module](data.md) · Property prefix: `stratum.data.sources`

Spring Boot gives a service one connection pool, which `spring.datasource.*` sets. Some work needs
a pool of its own: a batch job that holds connections for minutes should not take them from the
web requests, and reports may read from a replica. A named pool is a second HikariCP pool that you
add with a few `stratum.data.sources.<name>.*` keys. Spring Boot's pool stays as it is, and Stratum
calls it the `main` pool.

Named pools need HikariCP, which every Stratum data starter brings through Spring Boot. A service
that removes HikariCP keeps the boundary and the time limits on its own data source beans, but
Stratum builds no named pool and checks no pool, though a `stratum.data.sources` value that breaks
its rules still stops startup with `SX-CORE-1003`.

## Add a pool

Give the pool a name and a URL. This adds the pool `batch`:

```properties
stratum.data.sources.batch.url=jdbc:postgresql://db.internal:5432/orders?sslmode=verify-full
stratum.data.sources.batch.username=batch_app
stratum.data.sources.batch.password=${BATCH_DB_PASSWORD}
stratum.data.sources.batch.hikari.maximum-pool-size=4
stratum.data.sources.batch.transaction-timeout=30m
```

```yaml
stratum:
  data:
    sources:
      batch:
        url: jdbc:postgresql://db.internal:5432/orders?sslmode=verify-full
        username: batch_app
        password: ${BATCH_DB_PASSWORD}
        hikari:
          maximum-pool-size: 4
        transaction-timeout: 30m
```

Take the password from a secret, as `${BATCH_DB_PASSWORD}` does: Stratum never logs it. The keys
and their defaults are in the data module's [property table](data.md#properties). A named pool
takes none of the main pool's `spring.datasource.*` settings: it has its own.

A name is lower-case letters and digits, starts with a letter, is at most 31 characters and is not
`main`. Its beans are named after it. Any other name, and any key a named pool does not have,
stops startup with `SX-DATA-1004`, which names the key. That is what keeps a misspelt key from
being dropped without a word.

From environment variables, Spring Boot reads each `_` as a dot, so a pool's name cannot hold one.
`STRATUM_DATA_SOURCES_BATCH_QUERY_TIMEOUT=5s` and `STRATUM_DATA_SOURCES_BATCH_HIKARI_MAXIMUM_POOL_SIZE=4`
set the pool `batch`. `STRATUM_DATA_SOURCES_REPORTING_DB_URL` would set a key `db-url` on a pool
`reporting`, so it stops startup instead.

## Use it

The pool's beans are `batchDataSource` and `batchTransactionManager`. Reach them with their
qualifier; everything without one keeps the main pool:

```java
@Service
class NightlyExport {

  private final JdbcClient batch;

  NightlyExport(@Qualifier("batchDataSource") DataSource batchDataSource) {
    this.batch = JdbcClient.create(batchDataSource);
  }

  @Transactional(transactionManager = "batchTransactionManager", readOnly = true)
  List<OrderLine> lines(LocalDate day) {
    return batch.sql("SELECT id, total FROM order_lines WHERE day = ?")
        .param(day)
        .query(OrderLine.class)
        .list();
  }
}
```

A named pool serves `JdbcClient`, `JdbcTemplate` and your own JDBC code this way. With the data
JPA starter, Spring Data JPA repositories can use it too: see
[Repositories on a pool](#repositories-on-a-pool).
A `@Qualifier("batchDataSource")` borrows its connection as soon as you ask for it, as before:
Spring Boot's lazy fetch applies to `dataSource` only.

## Repositories on a pool

With the data JPA starter, a Spring Data JPA repository can run on a named pool, such as reports
that read from a replica. Map the repositories by package, with `packages`:

```properties
stratum.data.sources.reporting.url=jdbc:postgresql://replica.internal:5432/orders?sslmode=verify-full
stratum.data.sources.reporting.packages=com.acme.reporting,com.acme.audit
```

```yaml
stratum:
  data:
    sources:
      reporting:
        url: jdbc:postgresql://replica.internal:5432/orders?sslmode=verify-full
        packages:
          - com.acme.reporting
          - com.acme.audit
```

From environment variables, `STRATUM_DATA_SOURCES_REPORTING_PACKAGES=com.acme.reporting,com.acme.audit`
or `STRATUM_DATA_SOURCES_REPORTING_PACKAGES_0=com.acme.reporting` set the same. Or name the pool on
one repository interface:

```java
@UseDataSource("reporting")
public interface MonthlyTotals extends JpaRepository<MonthlyTotal, Long> {}
```

Which pool a repository uses:

- `@UseDataSource` wins over any package. `@UseDataSource("main")` keeps a repository on the main
  pool inside a mapped package.
- Otherwise the most specific mapped package that holds the interface wins, subpackages included:
  `com.acme` holds `com.acme.reports`, never `com.acmex`.
- A repository nothing maps stays on the main pool, or on its transaction's pool, as the next
  section says.

Only JPA repositories are mapped. Other Spring Data repositories, such as Redis ones, are left as
they are. Code that uses the `EntityManager` or the plain `DataSource` itself runs on its
transaction's pool, else on the main pool. There is one entity manager factory, so every repository
shares one persistence context and one transaction manager, Spring Boot's own.

At startup, Stratum logs one INFO line per mapped pool, which names its packages and annotated
interfaces: `Pool 'reporting' serves repositories in 2 packages: com.acme.reporting, com.acme.audit`.
A line names three and counts the rest. Startup stops with `SX-DATA-1007` when an entry of
`packages` is not a Java package name, as one that is blank, starts or ends with a dot, or holds
`*`; when a package is mapped to two pools; when a mapped package holds no JPA repository, as a
typo would; when `@UseDataSource` names a pool that does not exist; or when a mapped pool's URL
names another database product than the main pool's. A URL that does not show its product, such as
`jdbc:tc:`, is not checked.

### One pool per transaction

A transaction uses one pool, the pool of its first repository call or statement, until it ends. A
save or change that Hibernate writes only at commit therefore reaches the same pool.

- A repository nothing maps, called after a mapped one in the same transaction, runs on the
  transaction's pool.
- A mapped repository called in a transaction that is already on another pool fails with
  `SX-DATA-1008` before it sends any statement. That happens after a call of a repository on the
  main pool, and after code that ran a statement on the main pool itself, such as a `JdbcTemplate`
  or `EntityManager` call. Call the other pool's repositories in a transaction of their own, such
  as one with `@Transactional(propagation = REQUIRES_NEW)`.
- A repository method that runs in a transaction of its own, such as one declared
  `@Transactional(propagation = REQUIRES_NEW)` on the repository interface, leaves the caller's
  transaction alone: it runs on its own pool, and the caller's transaction picks its pool later.
- Around mapped repositories, use `@Transactional` with Spring Boot's JPA transaction manager, the
  default. A named pool's `<name>TransactionManager` does not reach JPA, so a mapped repository in
  its transaction fails with `SX-DATA-1008`.

A connection is borrowed at the first statement, not when the transaction begins: the transaction
does not know its pool before then.

### What a mapping changes

A mapping adds the bean `stratumRoutingDataSource`, which picks the pool for each statement. It is
the only primary `DataSource` ([ADR 0022](../adr/0022-routing-data-source-is-the-only-primary.md)),
so Spring Boot's JPA and `JdbcTemplate` use it, and it picks the main pool for whatever no mapped
repository runs. It changes a few things:

- A plain `DataSource` is the routing data source, so `unwrap(HikariDataSource.class)` on it fails.
  Inject `@Qualifier("dataSource")` to reach the main pool, as before.
- Startup stops with `SX-DATA-1007` when the service defines its own `DataSource`, which makes
  Spring Boot create none, another primary `DataSource` or a bean named `stratumRoutingDataSource`.
  Use Spring Boot's `dataSource` and no other primary `DataSource`, or drop the mapping.
- A servlet web application stops with `SX-DATA-1009` while `spring.jpa.open-in-view` is on: the
  request's entity manager would hold one pool's connection for the whole request. Stratum turns
  it off by default; with `stratum.backward-compatible=true`, set `spring.jpa.open-in-view=false`.
- The `db` health check and the pool metrics list each pool, never the routing data source:
  Stratum sets Spring Boot's `management.health.db.ignore-routing-data-sources=true`.

### Limits

- Every mapped pool must hold the tables of every mapped entity, as a replica or a shard with the
  same schema does. Hibernate speaks one dialect, the main pool's, and checks the schema of the
  main pool only, so an entity's table must exist there too: startup otherwise stops with
  `SX-DATA-1001`.
- Leave Hibernate's second-level cache off, its default: it would share entities between pools.
- Reactive repositories, and choosing a `JdbcTemplate`'s pool by package, are not covered: use the
  pool's qualifier.

## Its beans

| Bean | Type | What it is |
| --- | --- | --- |
| `<name>DataSource` | `HikariDataSource`, wrapped in the [clean errors boundary](data.md#clean-database-errors) | The pool. |
| `<name>TransactionManager` | `JdbcTransactionManager`, or `DataSourceTransactionManager` with `spring.dao.exceptiontranslation.enabled=false` | The pool's transaction manager, built as Spring Boot builds the main pool's: Boot's `spring.transaction.*` keys apply to it, then the pool's own `transaction-timeout`, which wins. |
| `<name>JdbcConnectionDetails` | `JdbcConnectionDetails` | The pool's URL, user name and password, from its keys. |

None of them is a *default candidate*: Spring injects one only where you name it with
`@Qualifier`, and a plain `DataSource`, `PlatformTransactionManager` or `@Transactional` keeps
Spring Boot's own. So adding a pool changes nothing in code that does not ask for it.

Every data source bean gets the same care as the main pool: the boundary that cleans database
errors, the [statement time limits](data-statement-limits.md), the [login limit](data-login-limit.md)
on PostgreSQL and the [TLS check](data-tls-check.md).

### Your own beans

You can supply one of these beans yourself, with the same name, and Stratum keeps yours. It must not
be a default candidate, or it would take the place of Spring Boot's own bean everywhere code injects
one without a qualifier: startup then stops with `SX-DATA-1006`. Declare it like this:

```java
@Bean(defaultCandidate = false)
JdbcConnectionDetails batchJdbcConnectionDetails(VaultCredentials vault) {
  return new BatchConnection(vault.url("batch"), vault.user("batch"), vault.password("batch"));
}
```

A `<name>JdbcConnectionDetails` bean that is no default candidate adds the pool by itself, with no
key: its other settings take their defaults.

Such a bean also stops Spring Boot's `@ServiceConnection` in your tests: Boot connects a container
only while no `JdbcConnectionDetails` bean exists, whatever its name. The main pool then has no
connection in tests, unless the test gives it one; [In tests](#in-tests) shows how.

## Pool names

Each pool has one name, which its metrics, its spans, the timeout counter and its log lines show:
`main` for Spring Boot's pool, and the pool's own name, such as `batch`, for a named pool.

- Stratum names Spring Boot's pool `main` when you gave it no name, in both modes. A name you set
  with `spring.datasource.name` or `spring.datasource.hikari.pool-name` is kept.
- HikariCP's metrics, such as `hikaricp.connections.max`, carry it as their `pool` tag, and
  `stratum.data.timeouts` as its own `pool` tag.
- Each [query span](data-query-spans.md) carries it as `db.client.connection.pool.name`.
- The database health check `db` keeps Spring Boot's names, the bean names: `dataSource` for the
  main pool and `batchDataSource` for the pool `batch`.

Two pools with the same name would mix their metrics, so that stops startup with `SX-DATA-1004`.

## The cap on connections

Every connection a pool opens is a connection the database must allow, in every instance of the
service. `stratum.data.max-connections`, 30 by default, caps the pools of one instance together.
Once every bean exists and before the service is ready, Stratum adds up the `maximum-pool-size` of
each pool, the main pool included, and stops startup with `SX-DATA-1005` when the total is higher.
The message names the total and each pool's maximum.

With HikariCP's default of 10, the main pool and two named pools reach 30. A third pool needs a
smaller maximum somewhere or a higher cap. A data source that is no HikariCP pool counts 0.

Stratum logs one line for each pool at startup, with its name and maximum only, never its URL or
user name:

```text
Pool 'main' opens at most 10 connections
Pool 'batch' opens at most 4 connections
```

## A cold pool

A named pool keeps no idle connection (`hikari.minimum-idle` is 0) and opens at most 10
connections, unless you set `hikari.minimum-idle` and `hikari.maximum-pool-size`. That suits work
that runs now and then: the pool holds nothing between jobs. The price is the first borrow, and the
first after an idle connection is retired, which waits for a new connection, its login and its TLS
handshake. For a pool that serves requests, set `hikari.minimum-idle`.

HikariCP adds a pool's metrics only once the pool has started. The [TLS check](data-tls-check.md)
borrows one connection from each pool shortly after startup, so the metrics of a named pool appear
then.

## Time limits and fetch size

- `query-timeout` gives the pool's statements their own limit, in place of
  `stratum.data.query-timeout`. The connections' network timeout follows it: 30 seconds more. See
  [Statement time limits](data-statement-limits.md).
- `transaction-timeout` is the time limit of a transaction on the pool's transaction manager that
  sets none of its own. It replaces `spring.transaction.default-timeout`, which is 60 seconds in
  strict mode, for that pool only. A timeout your code sets still wins.
- `fetch-size` is set on each new statement of the pool before your code runs, so a fetch size your
  code sets afterwards wins. Unset, the driver keeps its own. The PostgreSQL driver uses it only
  inside a transaction, as [Streaming and fetch size](data.md#streaming-and-fetch-size) says.

## Configure in Java

A `DataPropertiesCustomizer` can add a pool, or change one the keys added. Java wins over the
files:

```java
public class ReportsPool implements DataPropertiesCustomizer {

  @Override
  public DataProperties customize(DataProperties settings) {
    var sources = new HashMap<>(settings.sources());
    sources.put("reports", PoolSettings.builder()
        .url("jdbc:postgresql://replica.internal:5432/orders?sslmode=verify-full")
        .username("reports")
        .password(System.getenv("REPORTS_DB_PASSWORD"))
        .hikari(Map.of("maximum-pool-size", "4"))
        .queryTimeout(Duration.ofMinutes(2))
        .packages(List.of("com.acme.reporting"))
        .build());
    return settings.toBuilder().sources(sources).build();
  }
}
```

List the class in `META-INF/spring.factories`, as [Configure in Java](data.md#configure-in-java)
shows.

## In tests

Spring Boot's `@ServiceConnection`, Docker Compose and cloud platform bindings connect the main pool
only: none of them supplies a named pool's connection. In a test, `NamedPoolConnections` from
`stratum-test` gives the pool its own PostgreSQL database in Docker, of the same image as
`PostgresContainerConfiguration`:

```java
@SpringBootTest
@Import(PostgresContainerConfiguration.class)
@ContextConfiguration(initializers = NightlyExportTest.BatchPool.class)
class NightlyExportTest {

  static class BatchPool implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
      NamedPoolConnections.postgres("batch").initialize(context);
    }
  }

  // your tests
}
```

A test that starts the application itself adds the same initializer with
`SpringApplicationBuilder.initializers(NamedPoolConnections.postgres("batch"))`.

It registers `batchJdbcConnectionDetails` after Spring Boot has connected the main pool's container.
A `@Bean` of that name in a test configuration would not work with `@ServiceConnection`: Boot gives
the main pool's container no connection while any `JdbcConnectionDetails` bean exists.

When the service itself declares `batchJdbcConnectionDetails`, as in [Your own beans](#your-own-beans),
Boot gives the main pool's container no connection either. The test then connects the main pool with
a bean of its own, built from the container, and needs no `NamedPoolConnections`:

```java
@SpringBootTest
@Import(PostgresContainerConfiguration.class)
class NightlyExportTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class MainPool {

    @Bean
    JdbcConnectionDetails mainConnectionDetails(PostgreSQLContainer postgresContainer) {
      return new ContainerConnection(
          postgresContainer.getJdbcUrl(),
          postgresContainer.getUsername(),
          postgresContainer.getPassword());
    }
  }

  // your tests
}
```

`ContainerConnection` is your own class that implements `JdbcConnectionDetails`, such as a Lombok
`@Getter` class with the three fields. The service's own bean still connects the pool `batch`, so the
test must reach what that bean reads, such as its vault.
