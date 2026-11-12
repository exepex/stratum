# Testing: `stratum-test`

`stratum-test` gives a service's integration tests two containers, `PostgresContainerConfiguration`
and `RedisContainerConfiguration`, which start PostgreSQL and Redis with pinned images
([Containers](#containers)). It also holds the test helpers that several of Stratum's own modules
share, such as `StarterTestSupport`, `WebTestSupport`, `LogLineCapture`, `CpuCostMeter` and the sample
`OrderErrorCode`. A service has no use for those, and each of them is marked `@StratumInternal`
([Public API](../guides/upgrading.md#public-api)). It has no `stratum.test.*` properties.

[← Documentation home](../README.md)

## Add it

With `stratum-microservice-parent` as your parent, skip this step: the parent already brings
`stratum-test` and `spring-boot-starter-test` in test scope
([The microservice parent](parent-and-bom.md#the-microservice-parent)). Under any other parent, add
both:

```xml
<dependency>
  <groupId>io.github.exepex.stratum</groupId>
  <artifactId>stratum-test</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-test</artifactId>
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

JUnit 5, AssertJ and Mockito come from Spring Boot's `spring-boot-starter-test`, which
`stratum-microservice-parent` brings and a service under any other parent adds in test scope, as
above; `stratum-test` does not bring it.

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

Starts only the web layer; other beans are mocked. Spring Boot gives you this with `@WebMvcTest`.

```java
@WebMvcTest(OrderController.class)
class OrderControllerTest {

  @Autowired MockMvcTester mvc;
  @MockitoBean OrderService orders;

  @Test
  void returnsProblemDetailsForInvalidInput() {
    assertThat(mvc.post().uri("/orders")
            .contentType(APPLICATION_JSON).content("{\"sku\":\"\",\"quantity\":0}"))
        .hasStatus(HttpStatus.BAD_REQUEST);
  }
}
```

### Integration test

Starts the whole application with `@SpringBootTest`, and the containers it needs, as
[Containers](#containers) shows.

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

| Container | Image | Connects |
| --- | --- | --- |
| `PostgresContainerConfiguration` | `postgres:18.6-alpine` | `spring.datasource.*` |
| `RedisContainerConfiguration` | `redis:8.10.3-alpine` | `spring.data.redis.*` |

Each image is pinned once, as one `testcontainers.image.<container>` property in the root
`pom.xml` holding the full image, and never set to `latest`, so every service tests against the
same version. Stratum's maintainers update it by hand.

Because data stays between test classes that share a container, each test should create its own data
and not depend on what other tests left behind.

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
