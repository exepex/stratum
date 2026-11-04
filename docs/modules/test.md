# Testing: `stratum-test`

**Status: Partly built.** Planned: one test dependency with everything a Stratum service needs for
unit, mock and integration tests: JUnit 5, AssertJ, Mockito, Testcontainers, HTTP stubs and security
helpers. Built today: `PostgresContainerConfiguration` and `RedisContainerConfiguration`, which
start PostgreSQL and Redis for a service's integration tests ([Containers](#containers)). The
module also holds what Stratum's own starters' tests share: `StarterTestSupport`, their shared
checks, and `OrderErrorCode`, the sample error codes of an orders service that the web tests use.
A service has no use for those, and each of them is marked `@StratumInternal`
([Public API](../guides/upgrading.md#public-api)).

[← Documentation home](../README.md) · Property prefix: `stratum.test`

## Add it

```xml
<dependency>
  <groupId>com.exepex.stratum</groupId>
  <artifactId>stratum-test</artifactId>
  <scope>test</scope>
</dependency>
```

To test against PostgreSQL, also add Spring Boot's Testcontainers support and Testcontainers'
PostgreSQL module, in test scope. Spring Boot manages both versions, so you write none. Your service
already has `spring-boot-starter-test`, the JDBC support and the PostgreSQL driver.

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-testcontainers</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>testcontainers-postgresql</artifactId>
  <scope>test</scope>
</dependency>
```

To test against Redis, add only Spring Boot's Testcontainers support above, in test scope, even if
your service uses no PostgreSQL: it brings Testcontainers' generic container, which runs Redis. Your
service already has `spring-boot-starter-data-redis`, the Redis client.

`stratum-test` declares `spring-boot-test`, `spring-boot-testcontainers` and
`testcontainers-postgresql` as optional, so a module or service gets none of them through
`stratum-test`, only the ones it adds itself.

**Planned:** `stratum-test` will bring `spring-boot-starter-test` (JUnit 5, AssertJ, Mockito, Spring
test support), Testcontainers with the modules Stratum supports, an HTTP stub server and Spring
Security's test support.

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

**Planned:** `@ImportStratumWebTest` and `stratumJwt` are not built yet.

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

**Planned:** `@StratumIntegrationTest` will start the whole application with the `test` profile, and
start the containers your service needs. Until then, use `@SpringBootTest` with
`PostgresContainerConfiguration` or `RedisContainerConfiguration`, as [Containers](#containers)
shows.

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

A container is a database or broker that Testcontainers runs in Docker for your tests, so they use
the real thing. Docker must be running.

Import `PostgresContainerConfiguration` into a `@SpringBootTest`. Spring Boot starts PostgreSQL and
points your `DataSource` at it through its `@ServiceConnection` support, so you set no
`spring.datasource.*` property:

```java
@SpringBootTest
@Import(PostgresContainerConfiguration.class)
class OrderRepositoryIntegrationTest {

  @Autowired OrderRepository repository;

  @Test
  void storesTheOrder() {
    repository.save(new Order("A-1", 2));

    assertThat(repository.count()).isEqualTo(1);
  }
}
```

For Redis, import `RedisContainerConfiguration` the same way, as
`@Import(RedisContainerConfiguration.class)`. Spring Boot starts Redis and points your Redis client
at it, so you set no `spring.data.redis.*` property. Its container bean carries
`@ServiceConnection(name = "redis")`: that name is what lets Spring Boot find its Redis connection
details for a container declared as a bean, so keep it if you declare your own.

The container lives as long as the test's Spring application context. Spring keeps that context,
and so the container, for every test class with the same configuration, so those classes share one
container and start it once. A test class with a different configuration, such as other properties
or `@MockitoBean`s, gets its own context and its own container.

| Container | Image | Connects | Status |
| --- | --- | --- | --- |
| `PostgresContainerConfiguration` | `postgres:18.6-alpine` | `spring.datasource.*` | Built |
| MySQL | MySQL | `spring.datasource.*` | Planned |
| Kafka | Apache Kafka | `spring.kafka.*` | Planned |
| `RedisContainerConfiguration` | `redis:8.10.3-alpine` | `spring.data.redis.*` | Built |
| Keycloak | Keycloak | `stratum.security.resource-server.issuer-uri` | Planned |
| LocalStack | LocalStack (AWS emulator) | `stratum.cloud.*` (AWS) | Planned |

Each image is pinned once, as one `testcontainers.image.<container>` property in the root
`pom.xml` holding the full image, and never set to `latest`, so every service tests against the
same version. Stratum's maintainers update it by hand.

Because data stays between test classes that share a container, each test should create its own data
and not depend on what other tests left behind.

## Stubbing services you call

**Planned.** `@StubHttpClient("<name>")` will start a local HTTP stub and point the HTTP client group `<name>` at it.

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

**Planned.** `stratumJwt(...)` will create a valid test token with the given scopes; no identity
provider will be needed.

| Call | Result |
| --- | --- |
| `.with(stratumJwt("orders:read"))` | Token with scope `orders:read` |
| `.with(stratumJwt().subject("user-42").roles("ADMIN"))` | Token for a given user with roles |
| no `.with(...)` | No token: expect `401` |

For the rare test that must check real token validation, use the `Keycloak` container.

## Properties

**Planned.** None of these keys exists yet. They will go in `src/test/resources/application-test.yml`
(or `.properties`).

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `stratum.test.containers.reuse` | boolean | `false` | Keeps containers running between local test runs (needs Testcontainers reuse enabled on the machine). Never on CI. |
| `stratum.test.containers.images.<container>` | string | *(the image pinned as the `testcontainers.image.<container>` property in the root `pom.xml`)* | Overrides one image, e.g. to test a newer PostgreSQL. |
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
