# Testing: `stratum-test`

**Status: Partly built, not released.** The base module exists and passes `mvn verify`: it brings
Spring Boot's test starter and the two helpers under [What is built](#what-is-built). Everything else
on this page is planned: containers, HTTP stubs, security helpers, `@StratumIntegrationTest`,
`@ImportStratumWebTest` and the `stratum.test.*` properties.

[← Documentation home](../README.md) · Property prefix: `stratum.test`

## Add it

```xml
<dependency>
  <groupId>com.exepex.stratum</groupId>
  <artifactId>stratum-test</artifactId>
  <scope>test</scope>
</dependency>
```

It brings `spring-boot-starter-test` (JUnit 5, AssertJ, Mockito and Spring's test support), at the
version your Spring Boot parent sets. It does not depend on any other Stratum module.

Heavier test tools come as separate test modules, so a service adds only what its tests use. Both
are **planned**: `stratum-test-mock` for HTTP stubs of the services you call, and
`stratum-test-containers` for Testcontainers in integration tests. Their versions and container
images will be pinned in the BOM.

## What is built

Two helpers, in the package `com.exepex.stratum.test`.

### `ConfigFile`: a configuration file in a context test

An `ApplicationContextRunner` is Spring Boot's tool for testing auto-configuration: it starts a small
application context with only the configuration you give it. `ConfigFile.load` adds an
`application.properties` or `application.yml` file from your test resources to that context, read by
Spring Boot's own loaders, so you can check that a setting binds from both formats.

```java
class GreetingAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ValidationAutoConfiguration.class, GreetingAutoConfiguration.class));

  @ParameterizedTest
  @ValueSource(strings = {"greeting/application.properties", "greeting/application.yml"})
  void bindsTheMessageFromTheFile(String file) {
    contextRunner
        .withInitializer(ConfigFile.load(file))
        .run(context ->
            assertThat(context.getBean(Greeter.class).settings().message())
                .isEqualTo("Hi from a file"));
  }
}
```

| Behaviour | Detail |
| --- | --- |
| File types | Ends in `.properties`, `.xml`, `.yml` or `.yaml`. Any other ending is refused with an `IllegalArgumentException` when `load` is called. |
| Missing file | Refused with an `IllegalArgumentException` that names the file, when `load` is called. |
| Which value wins | The file's values win over every other value in the runner, including `withPropertyValues`. Of two files, the one loaded last wins. In a YAML file with several documents (`---`), a later document wins. |

### `LoggingContextIds`: sample trace, span and correlation IDs

Tracing and Stratum keep the current request's trace, span and correlation IDs in the logging context
(SLF4J's MDC), and Stratum's error reports read them from there. `LoggingContextIds` has a sample of
each ID, the key it is stored under (`traceId`, `spanId`, `correlationId`), and
`putInLoggingContext()`, which stores all three for the current thread. Clear the context after each
test, so the next one starts without IDs.

```java
@AfterEach
void clearLoggingContext() {
  MDC.clear();
}

@Test
void recordsTheTraceId() {
  LoggingContextIds.putInLoggingContext();

  assertThat(ErrorReport.of(OrderErrorCode.OUT_OF_STOCK).getExternal().traceId())
      .isEqualTo(LoggingContextIds.TRACE_ID);
}
```

## Three kinds of tests

| Kind | File name | Runs in | Starts Spring? | Docker? | Typical speed |
| --- | --- | --- | --- | --- | --- |
| **Unit test** | `*Test.java` | `mvn test` (Surefire) | No | No | Milliseconds |
| **Slice or mock test** | `*Test.java` | `mvn test` (Surefire) | Part of it | No | About a second |
| **Integration test** | `*IT.java` | `mvn verify` (Failsafe) | Fully | Yes | Seconds |

Write mostly unit tests, some slice tests, and integration tests for the paths that matter: the API
contract, database queries, messaging. The naming decides where a test runs, so name files
carefully.

### Unit test

Plain Java. No Spring, no Docker.

```java
class PriceCalculatorTest {

  private final PriceCalculator calculator = new PriceCalculator();

  @Test
  void appliesTenPercentDiscountAboveOneHundred() {
    assertThat(calculator.total(new BigDecimal("200"))).isEqualByComparingTo("180");
  }
}
```

### Mock test (web slice)

Starts only the web layer; other beans are mocked.

```java
@WebMvcTest(OrderController.class)
@ImportStratumWebTest            // applies Stratum's error format, correlation and security rules
class OrderControllerTest {

  @Autowired MockMvcTester mvc;
  @MockitoBean OrderService orders;

  @Test
  void returnsProblemDetailsForInvalidInput() {
    assertThat(mvc.post().uri("/orders").with(stratumJwt("orders:write"))
            .contentType(APPLICATION_JSON).content("{\"sku\":\"\",\"quantity\":0}"))
        .hasStatus(HttpStatus.BAD_REQUEST)
        .bodyJson().extractingPath("$.errors").asArray().hasSize(2);
  }
}
```

### Integration test

`@StratumIntegrationTest` starts the whole application with the `test` profile, and starts the
containers your service needs.

```java
@StratumIntegrationTest
@WithContainers({Postgres.class, Kafka.class})
class OrderFlowIT {

  @Autowired MockMvcTester mvc;
  @Autowired OrderRepository repository;

  @Test
  void storesTheOrder() {
    assertThat(mvc.post().uri("/orders").with(stratumJwt("orders:write"))
            .contentType(APPLICATION_JSON).content("{\"sku\":\"A-1\",\"quantity\":2}"))
        .hasStatus(HttpStatus.CREATED);

    assertThat(repository.count()).isEqualTo(1);
  }
}
```

## Containers

Containers are started **once per test run** and shared by all test classes, which keeps integration
tests fast. Connection properties (`spring.datasource.url`, `spring.kafka.bootstrap-servers`, ...)
are set automatically through Spring Boot's `@ServiceConnection` support.

| Container class | Image (pinned in the BOM) | Connects |
| --- | --- | --- |
| `Postgres` | PostgreSQL | `spring.datasource.*` |
| `MySql` | MySQL | `spring.datasource.*` |
| `Kafka` | Apache Kafka | `spring.kafka.*` |
| `Redis` | Redis | `spring.data.redis.*` |
| `Keycloak` | Keycloak | `stratum.security.resource-server.issuer-uri` |
| `LocalStack` | LocalStack (AWS emulator) | `stratum.cloud.*` (AWS) |

Because data stays between test classes, each test should create its own data and not depend on
what other tests left behind.

## Stubbing services you call

`@StubHttpClient("<name>")` starts a local HTTP stub and points the HTTP client group `<name>` at it.

```java
@StratumIntegrationTest
@StubHttpClient("inventory")
class ReservationIT {

  @Autowired HttpStub inventory;

  @Test
  void handlesInventoryOutage() {
    inventory.stubGet("/stock/A-1", 503);
    // ... call your API and assert it answers 503 with Problem Details
  }
}
```

## Testing secured endpoints

`stratumJwt(...)` creates a valid test token with the given scopes; no identity provider is needed.

| Call | Result |
| --- | --- |
| `.with(stratumJwt("orders:read"))` | Token with scope `orders:read` |
| `.with(stratumJwt().subject("user-42").roles("ADMIN"))` | Token for a given user with roles |
| no `.with(...)` | No token: expect `401` |

For the rare test that must check real token validation, use the `Keycloak` container.

## Properties

These go in `src/test/resources/application-test.yml` (or `.properties`).

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `stratum.test.containers.reuse` | boolean | `false` | Keeps containers running between local test runs (needs Testcontainers reuse enabled on the machine). Never on CI. |
| `stratum.test.containers.images.<container>` | string | *(from the BOM)* | Overrides one image, e.g. to test a newer PostgreSQL. |
| `stratum.test.jwt.default-issuer` | URI | `https://test-issuer.stratum.local` | Issuer used for test tokens. |

```yaml
stratum:
  test:
    containers:
      images:
        postgres: postgres:18-alpine
```

```properties
stratum.test.containers.images.postgres=postgres:18-alpine
```

## Configure in Java

**Planned.** You will also be able to set every property on this page in Java code, through this module's
customizer or builder, with the same names and defaults. This section will show how once that API is designed.

## Running tests

```bash
mvn test        # unit and slice tests only, no Docker needed
mvn verify      # everything, including *IT.java (Docker must be running)
mvn verify -Dit.test=OrderFlowIT   # one integration test class
```

## Troubleshooting

| Symptom | Likely cause and fix |
| --- | --- |
| `Could not find a valid Docker environment` | Docker is not running, or your user cannot access it. Run `docker info`. |
| An `*IT.java` test never runs with `mvn test` | Intended: integration tests run in `mvn verify`. |
| Tests pass alone but fail together | Shared containers keep data. Make each test create its own data, or clean up in `@AfterEach`. |
| First integration test run is slow | Images are being downloaded. Later runs reuse them. |
