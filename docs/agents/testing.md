# Testing rules

Part of the agent rules in [AGENTS.md](../../AGENTS.md); every agent follows them. Adapted from the CodeGuard AI test
conventions. Each rule has an ID so that reviews and judge verdicts can cite it.

## Every change

- **ST-TEST-01** Each change in behaviour has a test that fails when that behaviour is broken. Check this yourself:
  break the code on purpose (invert a condition, remove a bean, change a default), confirm the test fails, then
  restore the code. The judge repeats this with its own faults.
- **ST-TEST-02** For each behaviour, test the normal case, a failure case and the boundaries (empty, minimum, maximum,
  duplicate). For configuration, also test the property left unset and the module turned off.
- **ST-TEST-03** A bug fix starts with a test that reproduces the bug. It fails before the fix and passes after.
- **ST-TEST-04** Never delete, skip (`@Disabled`) or weaken a test to make a build pass.

## Unit tests

- **ST-TEST-10** Unit test classes end in `Test` and run under Surefire. They start no Spring context, network or
  containers.
- **ST-TEST-11** Use JUnit 5 and AssertJ. Mock with Mockito only at real boundaries, such as an external client or a
  clock. Do not mock the class under test or simple value types.
- **ST-TEST-12** Test method names are camelCase, for example `returnsDefaultTimeoutWhenNotConfigured`. Every test
  class, `@Nested` class and test method has a `@DisplayName` written as a sentence.
- **ST-TEST-13** Shared test data comes from `<Domain>TestData` classes in a `testsupport` package. Each is a utility
  class that returns pre-filled builders, and a test changes only the fields it cares about. Do not repeat the same
  object setup across tests.
- **ST-TEST-14** Tests are deterministic. Inject a `Clock`, fix random seeds, and wait for asynchronous work with
  Awaitility and a timeout, never `Thread.sleep`.

## Auto-configuration tests

- **ST-TEST-20** Every auto-configuration has `ApplicationContextRunner` tests (or `WebApplicationContextRunner` for
  web starters) that cover:
  - the beans created by default;
  - each bean backing off when the service defines its own, and when an auto-configuration with no declared order
    defines one, as a company framework would (ADR 0001 in `docs/adr/`). An auto-configuration ordered before
    a Spring Boot one (ST-ENG-21) instead has a test showing which bean wins, and its module page says so;
  - the module off when `stratum.<module>.enabled` is unset or `false`, and on when it is `true`;
  - an optional library missing from the classpath (`FilteredClassLoader`);
  - property binding from both `.properties` and `.yml`, including a bad value stopping startup;
  - the same settings applied through the Java configuration (ST-ENG-34), and the Java value winning when both are
    set.
- **ST-TEST-21** ArchUnit tests (`*ArchTest`) check that the `autoconfigure` package only wires beans, that no
  starter uses another starter's internal classes, and that packages have no cycles.

## Integration tests

- **ST-TEST-30** Integration test classes end in `IT`, sit in an `it` package and run under Failsafe in the `verify`
  phase.
- **ST-TEST-31** They start a minimal test application with `@SpringBootTest`, so they show what a real service gets
  by adding the starter.
- **ST-TEST-32** Real dependencies (PostgreSQL, Kafka, Redis, an OAuth2 server) run in Testcontainers provided by
  `stratum-test`. Image tags are pinned in one place; `latest` is never used. Containers are shared across a module's
  tests where that is safe, to keep builds fast.
- **ST-TEST-33** Connection details reach Spring through `@ServiceConnection` or `@DynamicPropertySource`, never as
  hardcoded hosts or ports.
- **ST-TEST-34** Downstream HTTP services are stubbed, for example with WireMock. Tests cover their timeouts, error
  responses and retries as well as the normal case.

## Coverage

- **ST-TEST-40** Every branch that decides behaviour (a condition, a default, an error path) has a test. JaCoCo
  reports coverage, and a change must not lower a module's coverage. Coverage alone does not prove a test is good;
  ST-TEST-01 does.
- **ST-TEST-41** Test code follows the same rules as production code, including the 400-line limit, with two
  exceptions: `@DisplayName` replaces Javadoc on tests (ST-ENG-14), and field injection is allowed where constructor
  injection is not practical (ST-ENG-26). Split a large test class by behaviour into `@Nested` classes or separate
  files.
