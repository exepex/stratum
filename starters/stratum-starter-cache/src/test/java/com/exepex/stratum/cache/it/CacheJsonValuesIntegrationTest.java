package com.exepex.stratum.cache.it;

import static com.exepex.stratum.cache.testsupport.values.CachedValuesTestData.ORDER;
import static com.exepex.stratum.cache.testsupport.values.CachedValuesTestData.ORDER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.cache.CacheConstants;
import com.exepex.stratum.cache.it.orders.OrdersApplication;
import com.exepex.stratum.cache.testsupport.values.Order;
import com.exepex.stratum.cache.testsupport.values.OrderCatalog;
import com.exepex.stratum.test.RedisContainerConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

@SpringBootTest(
    classes = OrdersApplication.class,
    properties = {
      "spring.application.name=" + CacheJsonValuesIntegrationTest.SERVICE,
      CacheConstants.ALLOWED_PACKAGES + "=" + CacheJsonValuesIntegrationTest.VALUES_PACKAGE
    })
@Import(RedisContainerConfiguration.class)
@DisplayName("A service on Redis stores cached values as plain JSON and reads them as its types")
class CacheJsonValuesIntegrationTest {

  /** The service's name, which its Redis cache keys start with. */
  static final String SERVICE = "orders-service";

  /** The package of the cached value types, outside the service's own package. */
  static final String VALUES_PACKAGE = "com.exepex.stratum.cache.testsupport.values";

  /** The stored JSON of {@code ORDER}: its fields only, no class name. */
  private static final String ORDER_JSON = "{\"id\":\"order-1\",\"quantity\":3}";

  /** The order as an older release stored it, with a field whose type has since changed. */
  private static final String CHANGED_TYPE_JSON = "{\"id\":\"order-1\",\"quantity\":\"many\"}";

  /** The order as a later release stored it: a field removed and another added. */
  private static final String OTHER_RELEASE_JSON = "{\"id\":\"order-1\",\"note\":\"gift\"}";

  /** How long a test waits for Spring Data Redis to write in the background. */
  private static final Duration BACKGROUND_WAIT = Duration.ofSeconds(5);

  /** The two ways of loading an order: without and with {@code sync=true}. */
  private static final List<Arguments> LOADS =
      List.of(
          Arguments.of(
              OrderCatalog.ORDERS,
              (Function<OrderCatalog, Order>) orders -> orders.orderOf(ORDER_ID)),
          Arguments.of(
              OrderCatalog.SYNCED_ORDERS,
              (Function<OrderCatalog, Order>) orders -> orders.syncedOrderOf(ORDER_ID)));

  @Autowired private OrderCatalog catalog;

  @Autowired private StringRedisTemplate redis;

  @Autowired private MeterRegistry meterRegistry;

  @Autowired private GenericContainer<?> redisContainer;

  /** Empties Redis. */
  @BeforeEach
  void startFromEmptyRedis() {
    redis.execute(this::emptyRedis, true);
  }

  /** Empties Redis. */
  private Object emptyRedis(RedisConnection connection) {
    connection.serverCommands().flushDb();
    return true;
  }

  @Nested
  @DisplayName("a value of an allowed type")
  class RoundTrips {

    @Test
    @DisplayName("is stored as its fields only, and a hit returns an equal order without a load")
    void storesPlainJson() {
      catalog.orderOf(ORDER_ID);
      awaitValue(OrderCatalog.ORDERS, ORDER_JSON);
      var loads = catalog.loads();

      assertThat(catalog.orderOf(ORDER_ID)).isEqualTo(ORDER);
      assertThat(catalog.loads()).isEqualTo(loads);
    }

    @Test
    @DisplayName("is read back as the declared type: a list of instants, an Optional, a future")
    void readsDeclaredTypes() {
      catalog.timesOf(ORDER_ID);
      catalog.findOrder(ORDER_ID);
      catalog.futureOrderOf(ORDER_ID).join();
      awaitKey(OrderCatalog.ORDER_TIMES);
      awaitKey(OrderCatalog.OPTIONAL_ORDERS);
      awaitKey(OrderCatalog.FUTURE_ORDERS);
      var loads = catalog.loads();

      assertThat(catalog.timesOf(ORDER_ID)).isEqualTo(OrderCatalog.TIMES);
      assertThat(catalog.findOrder(ORDER_ID)).isEqualTo(Optional.of(ORDER));
      assertThat(catalog.futureOrderOf(ORDER_ID).join()).isEqualTo(ORDER);
      assertThat(catalog.loads()).isEqualTo(loads);
    }
  }

  @Nested
  @DisplayName("a value another release stored")
  class SchemaChanges {

    @Test
    @DisplayName("with a field missing and an unknown one is read: the missing int is 0")
    void readsOtherRelease() {
      redis.opsForValue().set(key(OrderCatalog.ORDERS), OTHER_RELEASE_JSON);
      var loads = catalog.loads();

      assertThat(catalog.orderOf(ORDER_ID)).isEqualTo(new Order(ORDER_ID, 0));
      assertThat(catalog.loads()).isEqualTo(loads);
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("com.exepex.stratum.cache.it.CacheJsonValuesIntegrationTest#LOADS")
    @DisplayName("with a field of another type is a miss: loaded, replaced and counted")
    void replacesChangedType(String cacheName, Function<OrderCatalog, Order> load) {
      redis.opsForValue().set(key(cacheName), CHANGED_TYPE_JSON);
      var loads = catalog.loads();

      assertThat(load.apply(catalog)).isEqualTo(ORDER);
      assertThat(catalog.loads()).isEqualTo(loads + 1);
      awaitValue(cacheName, ORDER_JSON);
      assertThat(skips(cacheName, CacheConstants.REASON_UNREADABLE)).isGreaterThanOrEqualTo(1);
    }
  }

  @Nested
  @DisplayName("a method whose type cannot be read")
  class Refusals {

    @Test
    @DisplayName("returning Object runs each time, sends Redis no read or write, and is counted")
    void neverUsesRedis() throws IOException, InterruptedException {
      redisCli("CONFIG", "RESETSTAT");
      var loads = catalog.loads();

      catalog.untypedOrderOf(ORDER_ID);
      catalog.untypedOrderOf(ORDER_ID);

      assertThat(catalog.loads()).isEqualTo(loads + 2);
      assertThat(redisCli("INFO", "commandstats"))
          .doesNotContain("cmdstat_get:", "cmdstat_set:", "cmdstat_getex:");
      assertThat(skips(OrderCatalog.UNTYPED_ORDERS, CacheConstants.REASON_TYPE_NOT_CONCRETE))
          .isGreaterThanOrEqualTo(2);
    }
  }

  @Nested
  @DisplayName("a method that names its cache manager, the stated gap")
  class NamedCacheManager {

    @Test
    @DisplayName("fails its hit with a ClassCastException that no Stratum metric counts")
    void failsHitUncounted() {
      catalog.namedManagerOrderOf(ORDER_ID);
      awaitKey(OrderCatalog.NAMED_MANAGER_ORDERS);

      assertThatExceptionOfType(ClassCastException.class)
          .isThrownBy(() -> catalog.namedManagerOrderOf(ORDER_ID));
      assertThat(
              meterRegistry
                  .find(CacheConstants.METRIC_FAILURES)
                  .tag(CacheConstants.TAG_CACHE, OrderCatalog.NAMED_MANAGER_ORDERS)
                  .counters())
          .isEmpty();
    }
  }

  /** Returns the Redis key of the test order in a cache. */
  private static String key(String cacheName) {
    return SERVICE + ":" + cacheName + "::" + ORDER_ID;
  }

  /** Waits until Redis holds the test order's key in a cache, as Spring Data Redis writes later. */
  private void awaitKey(String cacheName) {
    await().atMost(BACKGROUND_WAIT).until(() -> Boolean.TRUE.equals(redis.hasKey(key(cacheName))));
  }

  /** Waits until Redis holds the JSON under the test order's key in a cache. */
  private void awaitValue(String cacheName, String json) {
    await()
        .atMost(BACKGROUND_WAIT)
        .untilAsserted(() -> assertThat(redis.opsForValue().get(key(cacheName))).isEqualTo(json));
  }

  /** Runs redis-cli in the container and returns what it prints. */
  private String redisCli(String... command) throws IOException, InterruptedException {
    var line = Stream.concat(Stream.of("redis-cli"), Arrays.stream(command));
    return redisContainer.execInContainer(line.toArray(String[]::new)).getStdout();
  }

  /** Returns the skips counted for a cache and reason. */
  private double skips(String cache, String reason) {
    var counter =
        meterRegistry
            .find(CacheConstants.METRIC_SKIPS)
            .tag(CacheConstants.TAG_CACHE, cache)
            .tag(CacheConstants.TAG_REASON, reason)
            .counter();
    return counter == null ? 0 : counter.count();
  }
}
