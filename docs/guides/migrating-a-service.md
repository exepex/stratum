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

- your `logback-spring.xml` or `logback.xml`, unless it does something Stratum does not;
- custom JSON encoders and MDC filters for trace IDs.

Check: log lines are JSON with `trace.id` and `service.name`.

## Step 3: observability

Add `stratum-starter-observability`. Remove:

- `management.*` exposure and probe settings that match Stratum's defaults;
- Micrometer and OpenTelemetry dependencies you added by hand;
- `MeterRegistryCustomizer` beans that only add the `application` tag.

Check: `/actuator/prometheus` works and traces reach your tracing backend. Dashboards that used old
metric names or tags may need updating.

## Step 4: web

Add `stratum-starter-web`. Remove:

- a global `@RestControllerAdvice` that only formats errors (keep handlers for your business
  exceptions, or turn those exceptions into `ErrorResponseException`);
- correlation ID filters.

Check: error bodies are now Problem Details. **This changes your API's error format.** Tell the
teams that call your service, or give them a release where both formats are documented.

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

Add the matching starters. Compare your current `spring.datasource.hikari.*`, `spring.jpa.*` and
`spring.kafka.*` settings with Stratum's defaults (listed on each module page) and delete those that
match. Keep any value that is deliberately different, and write down why in a comment.

> [!WARNING]
> `stratum-starter-data-jpa` sets `spring.jpa.open-in-view=false`. Code that loads lazy relations
> after the service method has returned will now fail with `LazyInitializationException`. Run your
> integration tests before deploying this step.

## Step 8: tests

Replace your own Testcontainers setup and JWT helpers with `stratum-test`. Rename integration
tests to `*IntegrationTest.java` so they run in `mvn verify`.

## What a migrated service usually removes

| Removed | Replaced by |
| --- | --- |
| Logback XML, encoders, MDC filters | `stratum-starter-logging` |
| Error advice, correlation filter | `stratum-starter-web` |
| `SecurityFilterChain`, `JwtDecoder`, converters | `stratum-starter-security` |
| `RestTemplate` beans, interceptors, timeout config | `stratum-starter-http-client` |
| Retry loops, circuit breaker config | `stratum-starter-resilience` |
| Test containers and JWT helpers | `stratum-test` |
| Dozens of `<version>` tags | `stratum-parent` or `stratum-bom` |
