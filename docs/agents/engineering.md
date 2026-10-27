# Engineering rules

Mandatory for every agent and every change to Stratum code. Index and summary: [AGENTS.md](../../AGENTS.md).
Adapted from the CodeGuard AI conventions (`CG-CONV-*`) for an SDK that many services build on.

Each rule has an ID (`ST-ENG-nn`) so reviews and the judge can cite it.

## Design principles

- **ST-ENG-01 Single responsibility.** A class does one job; a starter owns one cross-cutting concern. A class doing
  several jobs, or a starter reaching into another starter's concern, is split.
- **ST-ENG-02 Open/closed through extension points.** New behaviour goes into a new class behind an existing
  interface (a strategy, a customizer, a contributor), not into a growing `if`/`switch` in an existing class.
- **ST-ENG-03 Liskov and interface segregation.** Small, focused interfaces. An implementation never throws
  "unsupported" for half of its interface.
- **ST-ENG-04 Dependency inversion.** Depend on interfaces. Every external system (HTTP service, database, broker,
  cloud service, identity provider) sits behind one, so swapping it means one new class plus its wiring.
- **ST-ENG-05 KISS.** The simplest design that meets the need and scales. Never add a pattern, abstraction or
  configuration option without a second real use.
- **ST-ENG-06 DRY and reuse.** No copy-pasted logic. Before writing cross-cutting behaviour (headers, HTTP client,
  error envelope, retries, logging, test containers), look for the Stratum module that already does it and use it.
- **ST-ENG-07 Design patterns, deliberately.** Strategy for behaviour that varies by type, Factory for conditional
  construction, Template method for a shared skeleton, Facade for a subsystem, Builder for objects with many fields.
  Choose the simplest pattern that removes the branching or coupling.
- **ST-ENG-08 Clean code.** Names say what things do. Methods are short and do one thing. No deep nesting, no long
  parameter lists, no boolean parameters that select behaviour, no dead or commented-out code. Comments explain
  *why* (a trade-off, a constraint), never *what*. Public API carries Javadoc.
- **ST-ENG-09 Size.** No file over its limit (400 lines for code, 500 for text; see AGENTS.md). Split by
  responsibility, never squeeze.

## Cross-cutting concerns: how Stratum provides them

Stratum exists to provide cross-cutting concerns once, so each one is a separate, opt-in module:

- **ST-ENG-10 One concern, one starter.** Logging, observability, security, HTTP clients, resilience, data, caching
  and messaging each live in their own `stratum-starter-*`. A service pulls in only what it uses.
- **ST-ENG-11 Starters cooperate through Spring, not through each other's internals.** When the HTTP client needs
  trace propagation, it uses Micrometer's and Spring's public extension points, which the observability starter
  configures. A starter never imports another starter's internal classes.
- **ST-ENG-12 Same behaviour everywhere.** Log format, correlation and trace headers, metric names and tags, and the
  error response envelope are defined once and used by every module. Header names, property keys and metric names
  are constants, never repeated literals.

## Auto-configuration (every starter)

- **ST-ENG-20** Auto-configuration classes use `@AutoConfiguration` and are registered in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. Never component scanning in a
  starter.
- **ST-ENG-21** Every bean is conditional: `@ConditionalOnMissingBean` so a service can replace it,
  `@ConditionalOnClass` for optional libraries, and `@ConditionalOnProperty(prefix = "stratum.<module>", name =
  "enabled", matchIfMissing = true)` so a whole module can be switched off.
- **ST-ENG-22** Order between auto-configurations is explicit (`before`/`after` on `@AutoConfiguration`), never
  left to classpath order.
- **ST-ENG-23** The `autoconfigure` package only wires beans. Behaviour lives in ordinary classes that can be unit
  tested without Spring.
- **ST-ENG-24** Customization goes through customizer beans (`RestClientCustomizer`, Stratum's own
  `*Customizer` interfaces) rather than by asking services to redefine a whole bean.
- **ST-ENG-25** Defaults are safe for production: timeouts set, secrets redacted, secure endpoints, bounded resources.
  A service must not need any configuration to be safe.

## Configuration

- **ST-ENG-30** Every setting is a typed `@ConfigurationProperties(prefix = "stratum.<module>")` class, preferably a
  record, with Javadoc on each field and defaults in code. No scattered `@Value`.
- **ST-ENG-31** `spring-boot-configuration-processor` generates the metadata, so IDEs complete `stratum.*` keys.
  Validate properties with Bean Validation (`@Validated`) so a bad value fails at startup, not on first request.
- **ST-ENG-32** YAML only (`application.yml`); no `application.properties` in Stratum or its samples and tests.
- **ST-ENG-33** Tunables (pool sizes, timeouts, limits, TTLs, retry counts) are properties, never hardcoded.

## Java 25 and Spring Boot 4 conventions

- **ST-ENG-40** Use the current language: records for immutable values, sealed types for closed hierarchies, pattern
  matching for `switch` and `instanceof`, text blocks, `var` for locals whose type is obvious from the initializer.
- **ST-ENG-41** Null-safety with JSpecify: each package has a `package-info.java` with `@NullMarked`; nullable values
  are marked `@Nullable`.
- **ST-ENG-42** Lombok only for real boilerplate (`@Slf4j`, `@RequiredArgsConstructor`, `@Builder` on classes with
  many optional fields, `@UtilityClass`). Prefer records over `@Value`/`@Data`. Never `@Data` or `@ToString` on a
  type holding secrets or personal data.
- **ST-ENG-43** Objects with many fields are built with a builder, not positional constructors. Records, exceptions,
  collections and framework types with constructor APIs are fine with `new`.
- **ST-ENG-44** A closed set of values is an `enum`, not loose strings. Error codes stay `String` so they can grow
  without a breaking change.
- **ST-ENG-45** Literals that appear more than once, property keys and header names live in a `<Area>Constants`
  utility class, composed from a prefix (`PREFIX = "stratum.web"`, `ENABLED = PREFIX + ".enabled"`).
- **ST-ENG-46** Lambdas stay a single expression or a method reference. A multi-statement body becomes a named
  method referenced from the stream.
- **ST-ENG-47** Few static methods: business behaviour and anything with state is an injected bean. Pure helpers and
  constants may be static.
- **ST-ENG-48** Every `AutoCloseable` the method opens is closed by try-with-resources.
- **ST-ENG-49** Use Spring Boot 4 and Spring Framework 7 APIs (for example Jackson 3, `RestClient` and HTTP interface
  clients, JSpecify). Check the Spring Boot 4 migration notes before reusing a Boot 2 or 3 example.

## Performance, scale and resilience

Services built on Stratum run at high volume and high throughput, so:

- **ST-ENG-50 Timeouts everywhere.** Every outbound HTTP, database, broker and cloud call has a connect and read
  timeout, set by Stratum's defaults and overridable per client.
- **ST-ENG-51 Retry only what is safe.** Retry only idempotent operations, only on transient failures (timeouts,
  503, connection reset), with bounded attempts and backoff with jitter. Retrying a non-idempotent write is a bug.
  Circuit breakers where a dependency can fail.
- **ST-ENG-52 Bound everything.** Pools, queues, caches, batch sizes, request bodies and page sizes all have limits.
  No unbounded collection built from external input.
- **ST-ENG-53 Virtual-thread friendly.** No blocking work on event-loop threads; no `ThreadLocal` caches that grow
  per virtual thread; context (MDC, trace) propagated explicitly where work crosses threads.
- **ST-ENG-54 Hot paths stay cheap.** No per-request reflection, regex compilation, large allocations or O(n²) work
  on request paths. Build expensive objects once, at startup.
- **ST-ENG-55 Observable.** Meaningful operations emit metrics and spans with low-cardinality tags; failures are
  logged once, with correlation, at the layer that handles them.

## Dependencies and versions

- **ST-ENG-60** Versions are managed in one place (`stratum-bom`, inherited by `stratum-parent`). Module POMs never
  declare a version for a managed dependency. No dynamic versions (`LATEST`, ranges).
- **ST-ENG-61** Spring Boot manages most third-party versions; override one only with a reason in the POM comment.
- **ST-ENG-62** A new third-party dependency needs a reason in the pull request: what it gives that Spring Boot or an
  existing dependency does not.
- **ST-ENG-63** Upgrading Spring Boot or a major library is its own pull request, with the migration notes it
  required recorded in the release notes.
