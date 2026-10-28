# Engineering rules

Part of the agent rules in [AGENTS.md](../../AGENTS.md); every agent follows them. Where the summary in AGENTS.md
and this file differ, this file wins. They are adapted from the
CodeGuard AI conventions (`CG-CONV-01` to `CG-CONV-21`). Each rule has an ID so that reviews and judge verdicts can
cite it.

## Design

- **ST-ENG-01 One responsibility.** A class does one job and a starter owns one cross-cutting concern. Split a class
  that does several jobs, and do not let a starter take over another starter's concern.
- **ST-ENG-02 Extend, don't modify.** Add new behaviour as a new class behind an existing interface (a strategy, a
  customizer, a contributor). Do not grow an `if` or `switch` in an existing class. An exhaustive `switch` over a
  sealed type is allowed, because the compiler forces every case to be handled.
- **ST-ENG-03 Small interfaces.** Keep interfaces focused. An implementation must support everything its interface
  declares; throwing "unsupported" for part of it means the interface is too wide.
- **ST-ENG-04 Depend on interfaces.** Every external system (HTTP service, database, broker, cloud service, identity
  provider) sits behind an interface, so replacing it takes one new class and its wiring.
- **ST-ENG-05 Simplest code that works.** Write the plainest code that meets today's need and scales. Add no type,
  interface, helper, option or branch without a use that exists today. Prefer one type with a field to a hierarchy,
  inline code to a one-call helper, and the JDK, Spring or Lombok to hand-written code. No clever tricks, and no
  handling for cases that cannot happen. When a rule or tool pushes toward more structure, ask the owner first.
  Tunable values are still properties (ST-ENG-33).
- **ST-ENG-06 No duplication.** Do not copy logic. Before writing cross-cutting behaviour (headers, HTTP clients,
  error responses, retries, logging, test containers), find the Stratum module that already provides it and use it.
- **ST-ENG-07 Patterns where they remove complexity.** Strategy for behaviour that varies by type, Factory for
  conditional construction, Template Method for a shared sequence with varying steps, Facade for a subsystem,
  Builder for objects with several fields. Use one only when it removes branching or coupling.
- **ST-ENG-08 Clean code.** A method does one thing. Avoid deep nesting, long parameter lists and boolean
  parameters that switch behaviour. Remove dead and commented-out code. Comments inside a method explain a reason or
  constraint, never what the next line does. Names and Javadoc follow ST-ENG-13 to ST-ENG-15.
- **ST-ENG-09 Formatting.** Java is formatted with google-java-format through Spotless and checked by Checkstyle.
  Stratum's libraries and starters are also checked against Google's own Checkstyle configuration
  (`google_checks.xml`, shipped inside Checkstyle). Do not hand-format around the formatter, add `spotless:off`, or
  silence a check with `@SuppressWarnings`, a `CHECKSTYLE` comment or a suppression file.

## Cross-cutting concerns

- **ST-ENG-10 One concern per starter.** Logging, observability, security, HTTP clients, resilience, data access,
  caching and messaging each have their own `stratum-starter-*`. A service only gets what it adds. Bundle starters,
  such as `stratum-starter-microservice`, contain no code and only list the single starters one kind of application
  needs; no starter depends on a starter its concern does not need (ADR 0003).
- **ST-ENG-11 Starters meet through Spring.** When one starter needs another's behaviour, it uses the public extension
  points of Spring and Micrometer, which the other starter configures. It never imports another starter's internal
  classes. Example: the HTTP client starter propagates traces through Micrometer's observation API, not through
  classes of the observability starter.
- **ST-ENG-12 Defined once.** The log format, correlation and trace headers, metric names and tags, and the error
  response format are each defined in one place and used by every module.

## Names and Javadoc

Stratum is an SDK: its names and Javadoc are what service developers read first, so they describe what a thing does
for the service, not how it is built.

- **ST-ENG-13 Functional names.** Every class, interface, method, parameter, local variable, field, constant and
  property is named for its purpose in the domain of the SDK. Write `InventoryClient`, `connectTimeout`,
  `retryAttempts` and `redactSensitiveHeaders()`, not `RestClientWrapper`, `t1`, `cfg`, `data`, `handle()` or
  `process()`. Avoid `Manager`, `Helper`, `Util` and `Impl` unless the name still says what the class does.
  The rule covers loop variables, lambda parameters and caught exceptions: no `i`, `j`, `k`, `x` or `e`. Only generic
  type parameters such as `<T>` keep one letter, as Java convention and Google's Checkstyle require.
- **ST-ENG-14 Javadoc on everything.** Every class, interface, enum, record, method (public, protected, package-private
  and private) and every configuration property field has Javadoc. Lombok-generated members are documented on the
  field they come from. A private method needs only a short summary; `@param` and `@return` are required on
  public, protected and package-private methods. In test code, the `@DisplayName` on a test class, `@Nested` class
  or test method replaces Javadoc (ST-TEST-12); test support classes such as `<Domain>TestData` still have
  Javadoc. The build checks this for Stratum's libraries and starters through `stratum-library-parent`. Services
  built on Stratum are not checked for Javadoc: that is their team's choice.
- **ST-ENG-15 Short, functional Javadoc.** One or two plain sentences on what the element does for the service and,
  if it isn't obvious, why. Name units and defaults. Don't explain internals, restate the name or signature, or
  justify design choices; a note a maintainer needs goes in a short `//` comment.

## Auto-configuration and beans

Every starter follows these rules:

- **ST-ENG-20** Auto-configuration classes are annotated `@AutoConfiguration` and listed in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. Starters do not use component
  scanning.
- **ST-ENG-21** Every bean has conditions: `@ConditionalOnMissingBean` so a service or another framework can supply
  its own, `@ConditionalOnClass` when it needs an optional library, and every auto-configuration of a module carries
  `@ConditionalOnStratumModule("<module>")` from `stratum-core`, so the whole module stays off until the service sets
  `stratum.<module>.enabled=true` (ADR 0003). The missing-bean condition checks the type that services inject, such
  as Resilience4j's `CircuitBreakerRegistry`, not a Stratum class. Every bean is also marked `@Fallback`, so that
  when another framework's bean of the same type exists, Spring injects that one
  ([ADR 0001](../adr/0001-coexistence-with-company-frameworks.md)). For a type that Spring Boot already
  auto-configures, such as `RestClient.Builder`, Stratum does not define its own bean: it adjusts Boot's bean through
  a customizer (ST-ENG-24). A Stratum auto-configuration is ordered before a Spring Boot one only when it must
  replace a bean Boot would otherwise create, such as the default `SecurityFilterChain`, and its module page says so.
- **ST-ENG-22** Ordering between auto-configurations is declared with `before` and `after` on `@AutoConfiguration`,
  never left to classpath order. Every Stratum auto-configuration also has an `@AutoConfigureOrder` value later than
  the default, defined once in `stratum-core`, so that auto-configurations from other frameworks that declare no
  order run first and their beans win (ADR 0001).
- **ST-ENG-23** The `autoconfigure` package only creates and wires beans. Behaviour lives in ordinary classes that can
  be unit tested without Spring.
- **ST-ENG-24** Services adjust behaviour through customizer beans (Spring's, such as `RestClientCustomizer`, or
  Stratum's own `*Customizer` interfaces) instead of replacing whole beans.
- **ST-ENG-25** The defaults are safe for production: timeouts set, sensitive values redacted, endpoints secured,
  resources bounded. A service needs no configuration to be safe.
- **ST-ENG-26** Dependencies are injected through constructors (in auto-configuration, through `@Bean` method
  parameters). Never field injection (`@Autowired` on a field) or setter injection. Constructor injection makes
  dependencies explicit, keeps fields `final` and makes Spring report circular dependencies at startup. In test
  classes, field injection and test-method parameters are allowed, because a test class is not a bean that other beans
  depend on. Prefer constructor injection where it is practical, and always use it in test-support classes that are
  beans.

## Configuration

- **ST-ENG-30** Each module's settings are one `@ConfigurationProperties(prefix = "stratum.<module>")` type,
  preferably a record annotated with Lombok's `@Builder(toBuilder = true)`, with Javadoc on every field and
  defaults in code. The builder is what a service's Java configuration uses to change one value and keep the rest
  (ST-ENG-34). Do not read settings with scattered `@Value` annotations. A component's name, in kebab-case, is its
  property key, so components are not renamed with Spring Boot's `@Name`: the startup error of ST-ENG-31 names
  each property from its component name.
- **ST-ENG-31** `spring-boot-configuration-processor` generates metadata for every property so IDEs can complete
  `stratum.*` keys. Properties carry Bean Validation constraints, so a bad value stops the application at startup
  rather than failing on the first request. The settings record is not `@Validated`: `StratumPropertiesResolver`
  validates the settings after the Java configuration has run, so Java can correct or supply a value (ST-ENG-34).
  `stratum-core` depends on `spring-boot-starter-validation` for this, so every starter that depends on
  `stratum-core` brings Hibernate Validator and Spring Boot's `Validator` bean; a starter does not declare it again.
  A field that holds nested settings, such as a pool or a map of clients, is annotated `@Valid`; without it the
  nested constraints are never checked. The startup error names each property by its key, such as
  `stratum.http-client.pool.max-size`, and each rule with its limits, such as `@Size(max=10)`. It never shows a
  value, a map key, a list index or a constraint message, because any of them can be a secret. A cross-field check on
  a method, such as `@AssertTrue boolean isPoolLargeEnough()`, is named after the method (`pool-large-enough`),
  which is not a key a service sets; name such methods for the rule they check.
- **ST-ENG-32** Every property works the same from `application.properties` and `application.yml`. Property names use
  kebab-case, Spring Boot's canonical form. Tests for property binding cover both formats.
- **ST-ENG-33** Every tunable value (pool size, timeout, limit, TTL, retry count) is a property. None is hardcoded.
- **ST-ENG-34** Every setting can also be set in Java, for services whose configuration files would grow too large.
  Each module offers a customizer bean or a builder on its public API that sets the same options, with the same
  names and defaults as the properties. When a setting is given in both places, the Java configuration wins, and
  the module's documentation says so. The shared mechanism is in `stratum-core`: a service declares
  `StratumPropertiesCustomizer<P>` beans for the module's settings record `P`, and the module's auto-configuration
  builds its beans only from `StratumPropertiesResolver.resolve(bound, customizers.orderedStream())`, never from the
  bound record directly. Spring matches a customizer to its module by the generic type, so tests and examples
  register customizers with their full type, as the return type of a `@Bean` method.

## Java 25 and Spring Boot 4

- **ST-ENG-39** Use what the JDK already provides before writing code: the functional interfaces in
  `java.util.function` (`Supplier`, `Function`, `Predicate`, `Consumer`) instead of custom single-method interfaces,
  streams, `Optional` for return values that may be absent (never for fields or parameters), immutable and sequenced
  collections (`List.of`, `Stream.toList()`, `SequencedCollection`), and `java.time` for dates and durations.
  Use streams instead of `for` and `while` loops. Keep a loop only where a stream would be harder to read, and say
  why in a comment.
- **ST-ENG-40** Use current Java: records for immutable values, sealed types for closed hierarchies, pattern matching
  in `switch` and `instanceof`, text blocks, and `var` for local variables whose type is clear from the right-hand
  side.
- **ST-ENG-41** Every package has a `package-info.java` annotated `@NullMarked` (JSpecify). Values that may be null are
  annotated `@Nullable`.
- **ST-ENG-42** Do not hand-write code that a library can generate. Use Lombok for loggers (`@Slf4j`), constructors
  (`@RequiredArgsConstructor`, `@AllArgsConstructor`), accessors (`@Getter`, `@Setter`), builders (`@Builder`) and
  classes of static helpers (`@UtilityClass`). Use MapStruct for mapping one type to another. Prefer a record over a
  Lombok value class. Never put `@Data` or `@ToString` on a type that holds a secret or personal data. In Stratum's
  libraries and starters, PMD fails the build on a hand-written getter, setter, builder, logger, `equals`,
  `hashCode` or `toString`, and on a constructor that only assigns its parameters to fields
  (`stratum/pmd-lombok.xml`). A getter, setter or constructor with logic in it is not reported.
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
  and constants. One exception: `ErrorReport.of` reads the trace, span and correlation IDs from SLF4J's `MDC`, so
  that any code, injected or not, can report an error without passing the IDs by hand.
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

- **ST-ENG-60** Every version Stratum chooses is a property in the root `pom.xml`, and no other POM writes a
  version number (ADR 0005). `stratum-bom`, which `stratum-parent` imports, lists Stratum's modules and the
  libraries Spring Boot does not manage. Spring Boot's versions come from the parent: the root `pom.xml`, and so
  `stratum-parent`, inherits `spring-boot-starter-parent`, and the installed `stratum-bom` has no parent and does
  not import Spring Boot's BOM, so a service under a company parent gets one Spring Boot version (ADR 0001). No
  version ranges and no `LATEST`.
- **ST-ENG-61** Do not override a version that Spring Boot manages, in `stratum-bom` or in `stratum-parent`. Such an
  override would not reach services that import the BOM under their own parent. When a security fix cannot wait for
  a Spring Boot release, the release notes tell services how to override that version themselves.
- **ST-ENG-62** A new third-party dependency needs a reason in the pull request: what it provides that Spring Boot or
  an existing dependency does not.
- **ST-ENG-63** Do not write a utility that an established library already provides. Look first in the JDK, then
  Spring, then a widely used, maintained library such as Apache Commons or Guava, and use it instead of your own
  version.
- **ST-ENG-64** Every dependency and Maven plugin comes from Maven Central. No other repository in any POM, no
  JitPack, no jars downloaded from GitHub and no source copied from another project. The library must be actively
  maintained and its licence compatible with Apache 2.0.
