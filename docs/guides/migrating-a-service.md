# Migrating a service to Stratum

**Status: Planned.** How to move an existing Spring Boot service onto Stratum in small, safe steps,
deleting your own configuration code as you go.

[← Documentation home](../README.md)

## Before you start

- The service must run on **Spring Boot 4.x and Java 25**, or be upgraded first. Moving from Spring
  Boot 3 to 4 is a separate step with its own guide from the Spring team; do it before adopting
  Stratum, not at the same time.
- Make sure the service has tests that cover its API. They are your safety net.

## The approach: one concern at a time

Each step below adds one starter, deletes the code it replaces, and is deployed on its own. If a step
causes trouble, you know exactly which one.

```mermaid
flowchart LR
    A[1. Parent or BOM] --> B[2. Logging] --> C[3. Observability] --> D[4. Web]
    D --> E[5. Security] --> F[6. HTTP clients] --> G[7. Data, cache, Kafka] --> H[8. Tests]
```

## Step 1: versions

Replace your parent with `stratum-parent`, or import `stratum-bom` (see
[Installation](../getting-started/installation.md)). If your parent is your company's framework,
keep it and follow [Using Stratum with a company framework](company-framework.md). Then **remove every `<version>`** from
dependencies the BOM manages. Run `mvn dependency:tree` before and after, and compare.

## Step 2: logging

Add `stratum-starter-logging`. Remove:

- your `logback-spring.xml`, `logback.xml` or `logging.config`: with the logging module on, Stratum refuses them;
- custom JSON encoders and MDC filters for trace IDs.

Check: log lines are JSON with `service.name`. After step 3, lines written inside a traced operation
also carry `traceId`, `spanId` and `correlationId`.

## Step 3: observability

Add `stratum-starter-observability`. Remove:

- `management.*` exposure and probe settings that match Stratum's defaults;
- Micrometer and OpenTelemetry dependencies you added by hand;
- `MeterRegistryCustomizer` beans that only add the `application` tag.

Check: metrics and traces reach your OpenTelemetry Collector and its backends. Dashboards that used old
metric names or tags may need updating.

## Step 4: web

Add `stratum-starter-webmvc`, or `stratum-starter-webflux` for a reactive service. Remove:

- a global `@RestControllerAdvice` that only formats errors (keep handlers for your business
  exceptions, or turn those exceptions into `ErrorResponseException`);
- correlation ID filters.

Set `stratum.backward-compatible=true` in the release that adds the starter, because it is `false`
by default. Check: your existing consumers still get the error bodies they got before. In this mode,
errors that Spring answers keep Spring Boot's body, and a consumer gets Problem Details only when it
sends `Accept: application/problem+json`.
Your own `StratumException` errors, timeouts and bodies over the limit always get Problem Details,
and so does an existing exception you mark with `FailureWithErrorCode`. Map its code to the status
it has today and tell its consumers before you mark it; see
[Marking an existing exception](../modules/web-compatible-mode.md#marking-an-existing-exception).
Once every consumer reads Problem Details, which the counter
`stratum.web.requests.without.standard.errors` shows by staying at 0, set
`stratum.backward-compatible=false`; see
[Backward-compatible mode](../modules/web-compatible-mode.md), which also leaves the time and
body size limits off until you set them. The mode protects only what consumers see: logs, spans and
redaction follow Stratum in both modes.

## Step 5: security

Add `stratum-starter-security`, set `stratum.security.resource-server.*`, then remove your
`SecurityFilterChain`, `JwtDecoder` and authority converter beans. Move special rules into a
`StratumSecurityCustomizer` (see [Security](../modules/security.md#customize)).

Check: your security tests pass, and health probes still answer without a token.

## Step 6: HTTP clients

Add `stratum-starter-http-client` (and `stratum-starter-oauth2-client`, `stratum-starter-resilience`
if you need them). For each downstream service:

1. Turn the hand-written client class into an interface with `@GetExchange`, `@PostExchange`, ...
2. Register it with `@ImportHttpServices(group = "<name>", ...)`.
3. Move its URL and timeouts to `stratum.http-client.clients.<name>.*`.
4. Delete the old `RestTemplate` or `RestClient` bean and its interceptors.

Check: calls carry `traceparent` and `X-Correlation-Id`; timeouts are what you expect.

## Step 7: data, cache, Kafka

Add the matching starters. Compare your current `spring.datasource.hikari.*`, `spring.jpa.*`,
`spring.cache.redis.*`, `spring.data.redis.*` and `spring.kafka.*` settings with Stratum's defaults (listed on each module page) and delete those that
match. Keep any value that is deliberately different, and write down why in a comment.

`stratum-starter-data-jpa` sets these defaults even with `stratum.backward-compatible=true`,
because no caller sees them:

- a caller waits at most 3 seconds for a free connection
  (`spring.datasource.hikari.connection-timeout=3000`), so a pool that was often exhausted now
  fails fast instead of queuing;
- Hibernate checks at startup that the entities match the schema and never changes it
  (`spring.jpa.hibernate.ddl-auto=validate`), unless the service already chose a schema setting;
- Hibernate sends inserts and updates in ordered batches of 50 rows and pads `IN` lists;
- database errors carry no data values: Stratum wraps every `DataSource` bean, so code that
  catches or casts a driver's class, such as PostgreSQL's `PSQLException`, no longer matches, and
  you inject `DataSource` instead of `HikariDataSource` (call
  `dataSource.unwrap(HikariDataSource.class)` for the pool itself);
- Hibernate no longer logs JDBC errors and warnings itself
  (`spring.jpa.properties.hibernate.jdbc.log.errors=false` and `...log.warnings=false`).

Search your code for driver exception classes and for `HikariDataSource` before this step. The
other effects are listed under [Data → Clean database errors](../modules/data-jpa.md#clean-database-errors),
in its Limits.

Before deploying this step, run the service once with `validate` in development or CI against a
copy of the production schema. A mismatch stops startup with `SX-DATA-1001`, naming the table and
column; see [Data → Schema check](../modules/data-jpa.md#schema-check).

> [!WARNING]
> With `stratum.backward-compatible=false`, `stratum-starter-data-jpa` also sets
> `spring.jpa.open-in-view=false`. Code that loads lazy relations after the service method has
> returned will then fail with `LazyInitializationException`. Run your integration tests before
> turning the mode off.

`stratum-starter-cache` switches Spring's caching on. With `spring-boot-starter-data-redis`, it also
gives Spring Boot's Redis keys these defaults, in both modes, because no caller sees them
([Cache → An existing service](../modules/cache.md#an-existing-service)):

- the 1 s command timeout and 2 s connect timeout (`spring.data.redis.timeout` and
  `spring.data.redis.connect-timeout`) reach every Redis call of the service, `RedisTemplate`
  included;
- entries expire after 10 minutes by default (`spring.cache.redis.time-to-live`);
- keys start with the service's name (`spring.cache.redis.key-prefix`), so the old keys are left
  unread;
- startup stops with `SX-CACHE-1001` when the service has no `spring.application.name`;
- during a Redis outage a cached method now runs and returns its result, where the call used to
  fail: after the first failure every Redis cache skips Redis for 5 seconds, then one call tries
  again, so that load goes to your database or downstream service
  ([Cache → When Redis is down](../modules/cache-redis-down.md)).

Once the new version runs, delete the old keys, which start with the cache name under Spring Boot's
default, with SCAN by pattern, never with `FLUSHDB` on a Redis shared with other data. For the cache
`products`:

```bash
redis-cli --scan --pattern 'products::*' | xargs -r redis-cli unlink
```

### A database that already has a schema

Stratum sets no Flyway or Liquibase default for a database that already has tables. When you add
a migration tool to such a database, mark the existing schema as the starting point once, before
the tool's first run:

- **Flyway** refuses to migrate a non-empty schema that has no history table. Let it record the
  existing schema as version `1` and run only the scripts above it, in `application.properties`:

  ```properties
  spring.flyway.baseline-on-migrate=true
  spring.flyway.baseline-version=1
  ```

  or in `application.yml`:

  ```yaml
  spring:
    flyway:
      baseline-on-migrate: true
      baseline-version: 1
  ```

  Name your first script for the existing schema `V1__...` and new changes `V2__...` onwards.
  Remove `baseline-on-migrate` once every environment has its history table, so that a wrong
  database URL fails instead of being baselined.
- **Liquibase** has no Spring Boot key for this. Run Liquibase's `changelogSync` command once
  against each existing database: it records every change set as applied without running it.

## Step 8: tests

Replace your own Testcontainers setup and JWT helpers with `stratum-test`. Rename integration
tests to `*IntegrationTest.java` so they run in `mvn verify`.

## What a migrated service usually removes

| Removed | Replaced by |
| --- | --- |
| Logback XML, encoders, MDC filters | `stratum-starter-logging` |
| Error advice, correlation filter | `stratum-starter-webmvc` or `stratum-starter-webflux` |
| `SecurityFilterChain`, `JwtDecoder`, converters | `stratum-starter-security` |
| `RestTemplate` beans, interceptors, timeout config | `stratum-starter-http-client` |
| Retry loops, circuit breaker config | `stratum-starter-resilience` |
| Test containers and JWT helpers | `stratum-test` |
| Dozens of `<version>` tags | `stratum-parent` or `stratum-bom` |
