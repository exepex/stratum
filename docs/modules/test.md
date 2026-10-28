# Testing: `stratum-test`

**Status: Planned.** One test dependency with everything a Stratum service needs for unit, mock and
integration tests: JUnit 5, AssertJ, Mockito, Testcontainers, HTTP stubs and security helpers.

[← Documentation home](../README.md) · Property prefix: `stratum.test`

## Add it

```xml
<dependency>
  <groupId>com.exepex.stratum</groupId>
  <artifactId>stratum-test</artifactId>
  <scope>test</scope>
</dependency>
```

It brings `spring-boot-starter-test` (JUnit 5, AssertJ, Mockito, Spring test support), Testcontainers
with the modules Stratum supports, an HTTP stub server and Spring Security's test support. Versions
and container images are pinned in the BOM.

## Three kinds of tests

| Kind | File name | Runs in | Starts Spring? | Docker? | Typical speed |
| --- | --- | --- | --- | --- | --- |
| **Unit test** | `*Test.java` | `mvn test` (Surefire) | No | No | Milliseconds |
| **Slice or mock test** | `*Test.java` | `mvn test` (Surefire) | Part of it | No | About a second |
| **Integration test** | `*IntegrationTest.java` | `mvn verify` (Failsafe) | Fully | Yes | Seconds |

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
class OrderFlowIntegrationTest {

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
class ReservationIntegrationTest {

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
mvn verify      # everything, including *IntegrationTest.java (Docker must be running)
mvn verify -Dit.test=OrderFlowIntegrationTest   # one integration test class
```

## Troubleshooting

| Symptom | Likely cause and fix |
| --- | --- |
| `Could not find a valid Docker environment` | Docker is not running, or your user cannot access it. Run `docker info`. |
| An `*IntegrationTest.java` test never runs with `mvn test` | Intended: integration tests run in `mvn verify`. |
| Tests pass alone but fail together | Shared containers keep data. Make each test create its own data, or clean up in `@AfterEach`. |
| First integration test run is slow | Images are being downloaded. Later runs reuse them. |
