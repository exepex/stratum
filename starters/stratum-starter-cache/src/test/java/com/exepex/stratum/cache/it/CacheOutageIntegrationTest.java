package com.exepex.stratum.cache.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.cache.CacheConstants;
import com.exepex.stratum.cache.StratumCacheErrorHandler;
import com.exepex.stratum.cache.it.products.ProductsApplication;
import com.exepex.stratum.cache.testsupport.ProductCatalog;
import com.exepex.stratum.test.LogLineCapture;
import com.exepex.stratum.test.RedisContainerConfiguration;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
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
 * Pauses Redis itself and checks that the fail-open layer keeps cached calls working: every call
 * returns the method's real value, each failed read is counted, and one warning per cache covers
 * the whole outage. The cost of an outage call is the one-second command timeout of {@code
 * spring.data.redis.timeout} for the one command the call waits for; a standalone write is
 * fire-and-forget on the cache writer, so it never waits and never reports. The warning interval is
 * set to an hour, so the hundred sequential failures stay inside one interval.
 */
@SpringBootTest(
    classes = ProductsApplication.class,
    properties = {
      "spring.application.name=" + CacheOutageIntegrationTest.SERVICE,
      CacheConstants.WARNING_INTERVAL + "=1h"
    })
@Import(RedisContainerConfiguration.class)
@DisplayName("With Redis paused, a cached call still returns the method's value")
class CacheOutageIntegrationTest {

  /** The service's name, which its Redis cache keys start with. */
  static final String SERVICE = "outage-service";

  /** How many times each outage test calls the cached method. */
  private static final int CALLS = 100;

  /** How many threads make the calls at the same time. */
  private static final int THREADS = 10;

  /**
   * How long Redis stays paused, in milliseconds; five minutes cover the hundred sequential calls
   * of one second each, and a missed unpause still heals itself.
   */
  private static final String PAUSE_MILLIS = "300000";

  /** The product whose price the tests look up. */
  private static final String SKU = "sku-1";

  /** Nanoseconds in a millisecond, for the duration measurements. */
  private static final long NANOS_PER_MILLI = 1_000_000;

  /**
   * The shortest a call that waits for one command timeout takes, in milliseconds: the one-second
   * timeout of {@code spring.data.redis.timeout}, less a margin for the clock.
   */
  private static final double ONE_TIMEOUT_MIN_MILLIS = 800;

  /** The longest a one-timeout call may take before it counts as having waited for two. */
  private static final double ONE_TIMEOUT_MAX_MILLIS = 1_600;

  /** The longest a one-timeout call may take on a slow test machine, in milliseconds. */
  private static final double SLOW_MACHINE_MAX_MILLIS = 3_000;

  @Autowired private ProductCatalog catalog;

  @Autowired private MeterRegistry meterRegistry;

  @Autowired private StringRedisTemplate redis;

  @Autowired private GenericContainer<?> redisContainer;

  /** Empties Redis, so a test's calls all miss the cache. */
  @BeforeEach
  void startFromEmptyRedis() {
    redis.execute(
        connection -> {
          connection.serverCommands().flushDb();
          return true;
        },
        true);
  }

  @Nested
  @DisplayName("with every Redis command paused")
  class FullPause {

    @Test
    @DisplayName(
        "a sync=false call costs one command timeout, counts a failed get and no put, and warns"
            + " once per cache")
    void syncFalseCostsOneTimeout() throws Exception {
      var capture = new LogLineCapture(StratumCacheErrorHandler.class.getName());
      capture.start();
      List<Long> durations;
      try {
        pauseRedis();
        try {
          // One thread: the timed-out fire-and-forget writes churn reconnects on the one shared
          // connection, so ten threads would measure the churn (about 6 s a call), not the timeout.
          durations = durationsOf(1, () -> catalog.priceOf(SKU), "price-of-" + SKU);
        } finally {
          unpauseRedis();
        }
      } finally {
        capture.stop();
      }

      assertThat(averageOf(durations)).isBetween(ONE_TIMEOUT_MIN_MILLIS, ONE_TIMEOUT_MAX_MILLIS);
      assertThat(failuresOf(ProductCatalog.PRODUCTS, CacheConstants.OPERATION_GET))
          .isEqualTo(CALLS);
      assertThat(failureCounter(ProductCatalog.PRODUCTS, CacheConstants.OPERATION_PUT))
          .as("a standalone write is fire-and-forget and never reports")
          .isNull();
      assertThat(capture.lines()).hasSize(1);
      assertThat(capture.lines().getFirst().getFormattedMessage())
          .contains("SX-CACHE-1002")
          .contains(ProductCatalog.PRODUCTS);
    }

    @Test
    @DisplayName(
        "a sync=true call costs one command timeout, counts a failed get, and warns once per"
            + " cache")
    void syncTrueCostsOneTimeout() throws Exception {
      var capture = new LogLineCapture(StratumCacheErrorHandler.class.getName());
      capture.start();
      List<Long> durations;
      try {
        pauseRedis();
        try {
          durations =
              durationsOf(THREADS, () -> catalog.syncedPriceOf(SKU), "synced-price-of-" + SKU);
        } finally {
          unpauseRedis();
        }
      } finally {
        capture.stop();
      }

      assertThat(averageOf(durations)).isBetween(ONE_TIMEOUT_MIN_MILLIS, ONE_TIMEOUT_MAX_MILLIS);
      assertThat(failuresOf(ProductCatalog.SYNCED_PRODUCTS, CacheConstants.OPERATION_GET))
          .isEqualTo(CALLS);
      assertThat(capture.lines()).hasSize(1);
      assertThat(capture.lines().getFirst().getFormattedMessage())
          .contains("SX-CACHE-1002")
          .contains(ProductCatalog.SYNCED_PRODUCTS);
    }
  }

  @Nested
  @DisplayName("with only Redis writes paused")
  class WritePause {

    @Test
    @DisplayName(
        "the write after a load fails: the call still returns the loaded value, the loader ran"
            + " once, and one put failure is counted")
    void loadedValueSurvivesItsFailedWrite() throws Exception {
      pauseRedis("WRITE");
      try {
        var lookupsBefore = catalog.syncedLookups();
        var failuresBefore =
            failuresSoFar(ProductCatalog.SYNCED_PRODUCTS, CacheConstants.OPERATION_PUT);

        var start = System.nanoTime();
        var price = catalog.syncedPriceOf(SKU);
        var millis = (System.nanoTime() - start) / NANOS_PER_MILLI;

        assertThat(price).isEqualTo("synced-price-of-" + SKU);
        assertThat(catalog.syncedLookups()).isEqualTo(lookupsBefore + 1);
        assertThat(failuresSoFar(ProductCatalog.SYNCED_PRODUCTS, CacheConstants.OPERATION_PUT))
            .isEqualTo(failuresBefore + 1);
        // The write after the load is synchronous, so the call waits out one command timeout.
        assertThat((double) millis).isBetween(ONE_TIMEOUT_MIN_MILLIS, SLOW_MACHINE_MAX_MILLIS);
      } finally {
        unpauseRedis();
      }
    }
  }

  /**
   * Runs the call {@value #CALLS} times over the given number of threads and returns how long each
   * call took, in milliseconds. Every call must return the method's real value.
   */
  private List<Long> durationsOf(int threads, Supplier<String> call, String expectedValue)
      throws InterruptedException, ExecutionException {
    List<Callable<Long>> calls = new ArrayList<>();
    for (var index = 0; index < CALLS; index++) {
      calls.add(
          () -> {
            var start = System.nanoTime();
            assertThat(call.get()).isEqualTo(expectedValue);
            return (System.nanoTime() - start) / NANOS_PER_MILLI;
          });
    }
    var executor = Executors.newFixedThreadPool(threads);
    try {
      var futures = executor.invokeAll(calls);
      List<Long> durations = new ArrayList<>();
      for (var future : futures) {
        durations.add(future.get());
      }
      return durations;
    } finally {
      executor.shutdown();
    }
  }

  /** Returns the average of the measured durations, in milliseconds. */
  private double averageOf(List<Long> durations) {
    return durations.stream().mapToLong(Long::longValue).average().orElseThrow();
  }

  /** Returns how many failures of one operation on one cache were counted. */
  private double failuresOf(String cache, String operation) {
    return meterRegistry
        .get(CacheConstants.METRIC_FAILURES)
        .tags(CacheConstants.TAG_CACHE, cache, CacheConstants.TAG_OPERATION, operation)
        .counter()
        .count();
  }

  /** Returns the counter of one operation's failures on one cache, or {@code null} when none. */
  private @Nullable Counter failureCounter(String cache, String operation) {
    return meterRegistry
        .find(CacheConstants.METRIC_FAILURES)
        .tags(CacheConstants.TAG_CACHE, cache, CacheConstants.TAG_OPERATION, operation)
        .counter();
  }

  /** Returns the counted failures of one operation on one cache, or zero when none exist yet. */
  private double failuresSoFar(String cache, String operation) {
    var counter = failureCounter(cache, operation);
    return counter == null ? 0 : counter.count();
  }

  /**
   * Pauses Redis, so every command of the paused kind times out instead of answering. The pause
   * lifts itself after {@link #PAUSE_MILLIS} even when a test never unpauses.
   */
  private void pauseRedis(String... mode) throws IOException, InterruptedException {
    var command = new ArrayList<>(List.of("redis-cli", "CLIENT", "PAUSE", PAUSE_MILLIS));
    command.addAll(List.of(mode));
    redisContainer.execInContainer(command.toArray(String[]::new));
  }

  /** Lets Redis answer again; always runs, so a failed test never leaves a paused Redis behind. */
  private void unpauseRedis() throws IOException, InterruptedException {
    redisContainer.execInContainer("redis-cli", "CLIENT", "UNPAUSE");
  }
}
