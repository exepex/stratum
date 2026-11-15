# Data: named pools

[← Data module](data.md) · Property prefix: `stratum.data.sources`

Spring Boot gives a service one connection pool, which `spring.datasource.*` sets. Some work needs
a pool of its own: a batch job that holds connections for minutes should not take them from the
web requests, and reports may read from a replica. A named pool is a second HikariCP pool that you
add with a few `stratum.data.sources.<name>.*` keys. Spring Boot's pool stays as it is, and Stratum
calls it the `main` pool.

Named pools need HikariCP, which every Stratum data starter brings through Spring Boot. A service
that removes HikariCP keeps the boundary and the time limits on its own data source beans, but gets
no named pool and no check of the pools: Stratum ignores `stratum.data.sources`.

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

A named pool serves `JdbcClient`, `JdbcTemplate` and your own JDBC code. Spring Data repositories
and Hibernate keep the main pool: giving a repository a named pool is planned.

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

## Planned

Giving a Spring Data repository or Hibernate a named pool, and choosing the pool by package, are
planned for a later release.
