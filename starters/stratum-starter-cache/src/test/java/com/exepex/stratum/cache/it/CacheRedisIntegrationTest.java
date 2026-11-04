package com.exepex.stratum.cache.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.cache.it.products.ProductsApplication;
import com.exepex.stratum.cache.testsupport.ProductCatalog;
import com.exepex.stratum.test.RedisContainerConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

@SpringBootTest(
    classes = ProductsApplication.class,
    properties = {
      "spring.application.name=" + CacheRedisIntegrationTest.SERVICE,
      "spring.cache.cache-names=" + ProductCatalog.PRODUCTS
    })
@Import(RedisContainerConfiguration.class)
@DisplayName(
    "A service on Redis gets Stratum's time to live, key prefix, statistics and SCAN clears")
class CacheRedisIntegrationTest {

  /** The service's name, which its Redis cache keys start with. */
  static final String SERVICE = "products-service";

  /** The product whose price the tests cache. */
  private static final String SKU = "sku-1";

  /** Another product, so that a clear has more than one key to find. */
  private static final String OTHER_SKU = "sku-2";

  /** The Redis key of that price: the service, the cache and the product. */
  private static final String PRICE_KEY = SERVICE + ":" + ProductCatalog.PRODUCTS + "::" + SKU;

  /** Stratum's time to live, in milliseconds. */
  private static final long TEN_MINUTES_MILLIS = 600_000;

  /** How much of the time to live may pass between the write and the check, in milliseconds. */
  private static final long TIME_TO_LIVE_MARGIN_MILLIS = 10_000;

  /** How long a test waits for Spring Data Redis to write or clear in the background. */
  private static final Duration BACKGROUND_WAIT = Duration.ofSeconds(5);

  /** How many entries of Redis's slow log the clear test reads. */
  private static final String SLOW_LOG_ENTRIES = "128";

  @Autowired private ProductCatalog catalog;

  @Autowired private StringRedisTemplate redis;

  @Autowired private MeterRegistry meterRegistry;

  @Autowired private GenericContainer<?> redisContainer;

  /** Empties Redis and its slow log, and makes the slow log record every command. */
  @BeforeEach
  void startFromEmptyRedis() {
    redis.execute(this::emptyRedis, true);
  }

  /** Empties Redis and its slow log, and makes the slow log record every command. */
  private Object emptyRedis(RedisConnection connection) {
    connection.serverCommands().flushDb();
    connection.serverCommands().setConfig("slowlog-log-slower-than", "0");
    connection.execute("SLOWLOG", bytes("RESET"));
    return true;
  }

  @Nested
  @DisplayName("where and for how long a price is stored")
  class Storage {

    @Test
    @DisplayName("a cached price lives 10 minutes, under a key that starts with the service's name")
    void storesWithPrefixAndTimeToLive() {
      cachePrice(SKU);

      assertThat(redis.getExpire(PRICE_KEY, TimeUnit.MILLISECONDS))
          .isBetween(TEN_MINUTES_MILLIS - TIME_TO_LIVE_MARGIN_MILLIS, TEN_MINUTES_MILLIS);
    }
  }

  @Nested
  @DisplayName("hits and misses are counted")
  class Statistics {

    @Test
    @DisplayName("a cache hit is counted in cache.gets, beside cache.puts and cache.removals")
    void countsHits() {
      cachePrice(SKU);
      catalog.priceOf(SKU);

      var hits =
          meterRegistry
              .get("cache.gets")
              .tag("cache", ProductCatalog.PRODUCTS)
              .tag("result", "hit")
              .functionCounter()
              .count();
      assertThat(hits).isGreaterThanOrEqualTo(1);
      assertThat(meterRegistry.find("cache.puts").tag("cache", ProductCatalog.PRODUCTS).meter())
          .isNotNull();
      assertThat(meterRegistry.find("cache.removals").tag("cache", ProductCatalog.PRODUCTS).meter())
          .isNotNull();
    }
  }

  @Nested
  @DisplayName("a cache is cleared without blocking Redis")
  class Clearing {

    @Test
    @DisplayName("clearing every entry uses SCAN, never KEYS")
    void clearsWithScan() throws IOException, InterruptedException {
      cachePrice(SKU);
      cachePrice(OTHER_SKU);

      catalog.forgetAllPrices();

      await().atMost(BACKGROUND_WAIT).until(CacheRedisIntegrationTest.this::pricesAreGone);
      assertThat(slowLogWords()).contains("SCAN").doesNotContain("KEYS");
    }
  }

  /** Returns the Redis key of a product's price: the service, the cache and the product. */
  private String priceKey(String sku) {
    return SERVICE + ":" + ProductCatalog.PRODUCTS + "::" + sku;
  }

  /**
   * Caches a product's price and waits until Redis holds it, because Spring Data Redis writes in
   * the background.
   */
  private void cachePrice(String sku) {
    catalog.priceOf(sku);
    await().atMost(BACKGROUND_WAIT).until(() -> Boolean.TRUE.equals(redis.hasKey(priceKey(sku))));
  }

  /** Returns whether both cached prices have left Redis, which clears in the background. */
  private boolean pricesAreGone() {
    return Long.valueOf(0).equals(redis.countExistingKeys(List.of(PRICE_KEY, priceKey(OTHER_SKU))));
  }

  /**
   * Returns every word of every command in Redis's slow log, in capitals, one per line as {@code
   * redis-cli} prints them.
   */
  private List<String> slowLogWords() throws IOException, InterruptedException {
    return redisContainer
        .execInContainer("redis-cli", "SLOWLOG", "GET", SLOW_LOG_ENTRIES)
        .getStdout()
        .lines()
        .map(word -> word.toUpperCase(Locale.ROOT))
        .toList();
  }

  /** Returns the bytes of a word of a Redis command. */
  private byte[] bytes(String word) {
    return word.getBytes(StandardCharsets.UTF_8);
  }
}
