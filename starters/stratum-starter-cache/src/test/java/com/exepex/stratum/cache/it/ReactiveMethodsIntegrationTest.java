package com.exepex.stratum.cache.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.cache.CacheConstants;
import com.exepex.stratum.cache.RedisRetryGate;
import com.exepex.stratum.cache.StratumCacheErrorHandler;
import com.exepex.stratum.cache.it.products.ProductsApplication;
import com.exepex.stratum.cache.testsupport.AsyncProductCatalog;
import com.exepex.stratum.cache.testsupport.PausedRedisSteps;
import com.exepex.stratum.cache.testsupport.ProductCatalog;
import com.exepex.stratum.test.LogLineCapture;
import com.exepex.stratum.test.RedisContainerConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

/**
 * Checks that cached methods returning a {@code Mono} or a {@code Flux} get what blocking methods
 * get on Redis: values stored and read back, a null result neither stored nor reported, a failed
 * write after a load reported once with the method run once, and the method's value while Redis is
 * paused. Caching does not depend on WebFlux, so a plain service is enough.
 */
@SpringBootTest(
    classes = ProductsApplication.class,
    properties = {
      "spring.application.name=" + ReactiveMethodsIntegrationTest.SERVICE,
      CacheConstants.WARNING_INTERVAL + "=1h"
    })
@Import({RedisContainerConfiguration.class, AsyncProductCatalog.class})
@DisplayName("On Redis, Mono and Flux methods are cached like blocking ones")
class ReactiveMethodsIntegrationTest {

  /** The service's name, which its Redis cache keys start with. */
  static final String SERVICE = "reactive-service";

  /** How long Redis stays paused, in milliseconds: one round of command timeouts. */
  private static final long PAUSE_MILLIS = 5_000;

  /** The product whose price the tests look up. */
  private static final String SKU = "sku-1";

  /** The method's real value for {@value #SKU}. */
  private static final String PRICE = "price-of-" + SKU;

  @Autowired private AsyncProductCatalog catalog;

  @Autowired private ProductCatalog warmUpCatalog;

  @Autowired private MeterRegistry meterRegistry;

  @Autowired private StringRedisTemplate redis;

  @Autowired private GenericContainer<?> redisContainer;

  @Autowired private RedisRetryGate gate;

  /** The real lookups made before the test, as the catalog counts them across tests. */
  private int lookupsBefore;

  /** Waits until the caches use Redis again, then empties it, so a test's calls all miss. */
  @BeforeEach
  void startFromEmptyRedis() {
    PausedRedisSteps.startFromEmptyRedis(warmUpCatalog, gate, redis);
    lookupsBefore = catalog.lookups();
  }

  @Nested
  @DisplayName("with Redis up")
  class RedisUp {

    @Test
    @DisplayName("a Mono's value is stored and read back, so the method runs once")
    void monoStoredAndReadBack() {
      assertThat(catalog.monoPriceOf(SKU).block()).isEqualTo(PRICE);
      assertThat(catalog.monoPriceOf(SKU).block()).isEqualTo(PRICE);

      assertThat(lookupsSinceStart()).isOne();
      assertThat(redis.hasKey(keyOf(AsyncProductCatalog.MONO_PRODUCTS))).isTrue();
    }

    @Test
    @DisplayName("a Flux's values are stored as one list and read back in order")
    void fluxStoredAndReadBack() {
      var first = catalog.fluxPricesOf(SKU).collectList().block();
      var second = catalog.fluxPricesOf(SKU).collectList().block();

      assertThat(second).isEqualTo(first).isEqualTo(List.of(PRICE, "sale-" + SKU));
      assertThat(lookupsSinceStart()).isOne();
    }

    @Test
    @DisplayName(
        "an empty Mono with sync is not stored: the method runs once per call and no failure is"
            + " counted or warned")
    void emptyMonoNotStoredNorReported() {
      var capture = new LogLineCapture(StratumCacheErrorHandler.class.getName());
      capture.start();
      try {
        assertThat(catalog.monoUnpricedPriceOf(SKU).block()).isNull();
        assertThat(catalog.monoUnpricedPriceOf(SKU).block()).isNull();

        assertThat(lookupsSinceStart()).isEqualTo(2);
        assertThat(capture.lines()).isEmpty();
      } finally {
        capture.stop();
      }
      assertThat(redis.hasKey(keyOf(AsyncProductCatalog.MONO_UNPRICED_PRODUCTS))).isFalse();
      assertThat(failures(AsyncProductCatalog.MONO_UNPRICED_PRODUCTS, CacheConstants.OPERATION_GET))
          .isZero();
    }
  }

  @Nested
  @DisplayName("with Redis paused")
  class RedisPaused {

    @Test
    @DisplayName(
        "only writes paused: the Mono gives the loaded value, the method ran once and one put"
            + " failure is counted")
    void loadedValueSurvivesItsFailedWrite() throws Exception {
      var cache = AsyncProductCatalog.MONO_WRITE_TEST_PRODUCTS;
      var putFailuresBefore = failures(cache, CacheConstants.OPERATION_PUT);
      PausedRedisSteps.pauseRedis(redisContainer, PAUSE_MILLIS, "WRITE");
      try {
        assertThat(catalog.monoWriteTestPriceOf(SKU).block()).isEqualTo(PRICE);

        assertThat(lookupsSinceStart()).isOne();
        assertThat(failures(cache, CacheConstants.OPERATION_PUT)).isEqualTo(putFailuresBefore + 1);
        assertThat(failures(cache, CacheConstants.OPERATION_GET)).isZero();
      } finally {
        unpauseRedis();
      }
    }

    @Test
    @DisplayName("every command paused: the Mono still gives the method's value, run once")
    void monoGivesValueWhileRedisIsPaused() throws Exception {
      PausedRedisSteps.pauseRedis(redisContainer, PAUSE_MILLIS);
      try {
        assertThat(catalog.monoPriceOf(SKU).block()).isEqualTo(PRICE);

        assertThat(lookupsSinceStart()).isOne();
      } finally {
        unpauseRedis();
      }
    }
  }

  /** Returns the Redis key of {@value #SKU} in a cache. */
  private String keyOf(String cacheName) {
    return SERVICE + ":" + cacheName + "::" + SKU;
  }

  /** Returns the real lookups made since the test started. */
  private int lookupsSinceStart() {
    return catalog.lookups() - lookupsBefore;
  }

  /** Returns the counted failures of one operation on one cache. */
  private double failures(String cacheName, String operation) {
    return PausedRedisSteps.failuresSoFar(meterRegistry, cacheName, operation);
  }

  /** Lifts the pause, so the next test starts with Redis answering. */
  private void unpauseRedis() throws IOException, InterruptedException {
    redisContainer.execInContainer("redis-cli", "CLIENT", "UNPAUSE");
  }
}
