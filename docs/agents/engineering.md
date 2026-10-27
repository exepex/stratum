# Engineering rules

Part of the agent rules in [AGENTS.md](../../AGENTS.md); every agent follows them. They are adapted from the
CodeGuard AI conventions (`CG-CONV-01` to `CG-CONV-21`). Each rule has an ID so that reviews and judge verdicts can
cite it.

## Design

- **ST-ENG-01 One responsibility.** A class does one job and a starter owns one cross-cutting concern. Split a class
  that does several jobs, and do not let a starter take over another starter's concern.
- **ST-ENG-02 Extend, don't modify.** Add new behaviour as a new class behind an existing interface (a strategy, a
  customizer, a contributor). Do not grow an `if` or `switch` in an existing class.
- **ST-ENG-03 Small interfaces.** Keep interfaces focused. An implementation must support everything its interface
  declares; throwing "unsupported" for part of it means the interface is too wide.
- **ST-ENG-04 Depend on interfaces.** Every external system (HTTP service, database, broker, cloud service, identity
  provider) sits behind an interface, so replacing it takes one new class and its wiring.
- **ST-ENG-05 Keep it simple.** Choose the simplest design that meets the requirement and scales. Do not add a
  pattern, abstraction or setting until there is a second real use for it.
- **ST-ENG-06 No duplication.** Do not copy logic. Before writing cross-cutting behaviour (headers, HTTP clients,
  error responses, retries, logging, test containers), find the Stratum module that already provides it and use it.
- **ST-ENG-07 Patterns where they remove complexity.** Strategy for behaviour that varies by type, Factory for
  conditional construction, Template Method for a shared sequence with varying steps, Facade for a subsystem,
  Builder for objects with several fields. Use one only when it removes branching or coupling.
- **ST-ENG-08 Clean code.** Names say what a thing does. A method does one thing. Avoid deep nesting, long parameter
  lists and boolean parameters that switch behaviour. Remove dead and commented-out code. Comments explain a reason
  or constraint, never what the next line does. Public API has Javadoc.
- **ST-ENG-09 Formatting.** Java is formatted with google-java-format through Spotless and checked by Checkstyle.
  Do not hand-format around the formatter, add `spotless:off` or add Checkstyle suppressions.

## Cross-cutting concerns

- **ST-ENG-10 One concern per starter.** Logging, observability, security, HTTP clients, resilience, data access,
  caching and messaging each have their own `stratum-starter-*`. A service only gets what it adds.
- **ST-ENG-11 Starters meet through Spring.** When one starter needs another's behaviour, it uses the public extension
  points of Spring and Micrometer, which the other starter configures. It never imports another starter's internal
  classes. Example: the HTTP client starter propagates traces through Micrometer's observation API, not through
  classes of the observability starter.
- **ST-ENG-12 Defined once.** The log format, correlation and trace headers, metric names and tags, and the error
  response format are each defined in one place and used by every module.

## Auto-configuration

Every starter follows these rules:

- **ST-ENG-20** Auto-configuration classes are annotated `@AutoConfiguration` and listed in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. Starters do not use component
  scanning.
- **ST-ENG-21** Every bean has conditions: `@ConditionalOnMissingBean` so a service can supply its own,
  `@ConditionalOnClass` when it needs an optional library, and `@ConditionalOnProperty(prefix = "stratum.<module>",
  name = "enabled", matchIfMissing = true)` so the whole module can be turned off.
- **ST-ENG-22** Ordering between auto-configurations is declared with `before` and `after` on `@AutoConfiguration`,
  never left to classpath order.
- **ST-ENG-23** The `autoconfigure` package only creates and wires beans. Behaviour lives in ordinary classes that can
  be unit tested without Spring.
- **ST-ENG-24** Services adjust behaviour through customizer beans (Spring's, such as `RestClientCustomizer`, or
  Stratum's own `*Customizer` interfaces) instead of replacing whole beans.
- **ST-ENG-25** The defaults are safe for production: timeouts set, sensitive values redacted, endpoints secured,
  resources bounded. A service needs no configuration to be safe.

## Configuration

- **ST-ENG-30** Each module's settings are one `@ConfigurationProperties(prefix = "stratum.<module>")` type,
  preferably a record, with Javadoc on every field and defaults in code. Do not read settings with scattered
  `@Value` annotations.
- **ST-ENG-31** `spring-boot-configuration-processor` generates metadata for every property so IDEs can complete
  `stratum.*` keys. Properties are validated with Bean Validation (`@Validated`), so a bad value stops the
  application at startup rather than failing on the first request.
- **ST-ENG-32** Every property works the same from `application.properties` and `application.yml`. Property names use
  kebab-case, Spring Boot's canonical form. Tests for property binding cover both formats.
- **ST-ENG-33** Every tunable value (pool size, timeout, limit, TTL, retry count) is a property. None is hardcoded.

## Java 25 and Spring Boot 4

- **ST-ENG-40** Use current Java: records for immutable values, sealed types for closed hierarchies, pattern matching
  in `switch` and `instanceof`, text blocks, and `var` for local variables whose type is clear from the right-hand
  side.
- **ST-ENG-41** Every package has a `package-info.java` annotated `@NullMarked` (JSpecify). Values that may be null are
  annotated `@Nullable`.
- **ST-ENG-42** Use Lombok instead of hand-written boilerplate: `@Slf4j` for loggers, `@RequiredArgsConstructor` and
  `@AllArgsConstructor` for constructors, `@Getter` and `@Setter` for accessors, `@Builder` for builders and
  `@UtilityClass` for classes of static helpers. Prefer a record over a Lombok value class. Never put `@Data` or
  `@ToString` on a type that holds a secret or personal data.
- **ST-ENG-43** When a type has a builder, construct it with the builder, not with `new`. Records without a builder,
  exceptions, collections and framework types designed for constructors may use `new`.
- **ST-ENG-44** A fixed set of values is an `enum`, not a set of strings. Error codes are the exception: they stay
  `String` so new codes do not break clients.
- **ST-ENG-45** No magic strings or numbers. Property keys, header names, metric names and any literal used more than
  once live in a `<Area>Constants` utility class, built from a prefix (`PREFIX = "stratum.web"`,
  `ENABLED = PREFIX + ".enabled"`).
- **ST-ENG-46** A lambda is a single expression or a method reference. When it needs more than one statement, extract
  a named method and reference it.
- **ST-ENG-47** Logic and state belong in injected beans, not static methods. Static methods are only for pure helpers
  and constants.
- **ST-ENG-48** Every `AutoCloseable` that a method opens is closed with try-with-resources. Do not close resources
  owned by the caller or managed by Spring.
- **ST-ENG-49** Use the Spring Boot 4 and Spring Framework 7 APIs, such as Jackson 3, `RestClient` and HTTP interface
  clients. Do not reuse a Spring Boot 2 or 3 example without checking the Spring Boot 4 migration notes.

## Performance, scale and resilience

- **ST-ENG-50 Timeouts on every remote call.** Every HTTP, database, broker and cloud call has connect and read
  timeouts. Stratum sets the defaults; a service can change them per client.
- **ST-ENG-51 Retry only when safe.** Retry only idempotent operations, only on transient failures (timeouts, HTTP
  503, connection resets), with a limit on attempts and backoff with jitter. Retrying a non-idempotent write is a
  bug. Use a circuit breaker for a dependency that can fail.
- **ST-ENG-52 Bound everything.** Pools, queues, caches, batch sizes, request bodies and page sizes all have limits.
  Never build an unbounded collection from external input.
- **ST-ENG-53 Work with virtual threads.** Do not block event-loop threads. Do not cache in `ThreadLocal`s that grow
  with each virtual thread. When work moves to another thread, pass the logging and tracing context explicitly.
- **ST-ENG-54 Keep request paths cheap.** No reflection, regex compilation, large allocations or quadratic loops per
  request. Build expensive objects once, at startup.
- **ST-ENG-55 Make it observable.** Important operations record metrics and spans with low-cardinality tags. A
  failure is logged once, with the correlation ID, by the layer that handles it.

## Dependencies

- **ST-ENG-60** All versions are set in `stratum-bom`, which `stratum-parent` imports. A module POM never sets the
  version of a managed dependency. No version ranges and no `LATEST`.
- **ST-ENG-61** Spring Boot manages most library versions. Override one only with a comment in the POM saying why.
- **ST-ENG-62** A new third-party dependency needs a reason in the pull request: what it provides that Spring Boot or
  an existing dependency does not.
