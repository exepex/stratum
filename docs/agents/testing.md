# Testing rules

Mandatory for every change to Stratum code. Index and summary: [AGENTS.md](../../AGENTS.md). Rule IDs
(`ST-TEST-nn`) let reviews and the judge cite them.

## What every change needs

- **ST-TEST-01** Every behaviour change comes with tests that fail when the behaviour is broken. A test that still
  passes when the code under test is deleted or inverted is not a test; the judge checks this by planting faults.
- **ST-TEST-02** For each behaviour: the success case, a failure case, a boundary case (empty, minimum, maximum,
  duplicate) and, for configuration, the "property not set" and "feature switched off" cases.
- **ST-TEST-03** A bug fix starts with a regression test that fails before the fix and passes after it.
- **ST-TEST-04** No test is deleted, skipped (`@Disabled`) or loosened to make a build pass. Removing or weakening a
  test needs the judge's PASS with a stated reason.

## Unit tests

- **ST-TEST-10** Named `*Test.java`, run by Surefire, no Spring context and no network or containers.
- **ST-TEST-11** JUnit 5 and AssertJ; Mockito only at real boundaries (an external client, a clock). Do not mock the
  class under test or plain value types.
- **ST-TEST-12** Test method names are camelCase (`returnsDefaultTimeoutWhenNotConfigured`). Every test class,
  `@Nested` class and test method has a `@DisplayName` that reads as a sentence.
- **ST-TEST-13** Shared fixtures come from `<Domain>TestData` object mothers in a `testsupport` package: utility
  classes returning pre-filled builders that a test overrides selectively. No copy-pasted object setup.
- **ST-TEST-14** Tests are deterministic: inject a `Clock`, fix random seeds, never `Thread.sleep` to wait for work
  (use Awaitility with a timeout).

## Auto-configuration tests (every starter)

- **ST-TEST-20** Every auto-configuration is tested with `ApplicationContextRunner` (or `WebApplicationContextRunner`)
  for: beans created by default; beans replaced when the service defines its own (`@ConditionalOnMissingBean`);
  module switched off with `stratum.<module>.enabled=false`; behaviour when an optional library is missing
  (`FilteredClassLoader`); and property binding, including a bad value failing at startup.
- **ST-TEST-21** Architecture rules are tested with ArchUnit (`*ArchTest`): the `autoconfigure` package only wires,
  starters do not depend on each other's internals, no cycles between packages.

## Integration tests

- **ST-TEST-30** Named `*IT.java`, run by Failsafe in the `verify` phase, kept in an `it` package.
- **ST-TEST-31** `@SpringBootTest` against a minimal test application, so the test proves what a real service gets
  by adding the starter.
- **ST-TEST-32** Real dependencies (PostgreSQL, Kafka, Redis, an OAuth2 server) run in Testcontainers from
  `stratum-test`, with images and tags pinned centrally, never `latest`. Containers are shared across tests in a
  module where safe, to keep the build fast.
- **ST-TEST-33** Connection details reach Spring through `@ServiceConnection` or `@DynamicPropertySource`, never
  hardcoded hosts or ports.
- **ST-TEST-34** Downstream HTTP services are stubbed (for example WireMock); tests cover their timeouts, error
  responses and retries, not only the happy path.

## Coverage and quality

- **ST-TEST-40** Coverage is a signal, not the goal: every branch that decides behaviour (a condition, a default, an
  error path) has a test. Once the build runs JaCoCo, new code must not lower the module's coverage.
- **ST-TEST-41** Tests follow the same engineering rules as production code, including the 400-line limit. Split a
  large test class by behaviour with `@Nested` classes or separate files.
