package com.exepex.stratum.cache.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.cache.CacheConstants;
import com.exepex.stratum.cache.RedisRetryGate;
import com.exepex.stratum.cache.StratumCacheErrorHandler;
import com.exepex.stratum.cache.it.products.ProductsApplication;
import com.exepex.stratum.cache.testsupport.PausedRedisSteps;
import com.exepex.stratum.cache.testsupport.ProductCatalog;
import com.exepex.stratum.test.LogLineCapture;
import com.exepex.stratum.test.RedisContainerConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.IntStream;
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
 * returns the method's real value, and after the first command timeout every Redis cache skips
 * Redis for {@code stratum.cache.redis-retry-interval} instead of waiting. A {@code sync=false}
 * call waits for one timeout, not two, because the write after its timed-out read is skipped. The
 * warning interval is set to an hour, so the failures stay inside one interval.
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
   * How long Redis stays paused, in milliseconds: long enough for one retry after the 5 s retry
   * interval. Redis does not process an unpause while every command is paused, so a test lasts as
   * long as its pause.
   */
  private static final long PAUSE_MILLIS = 12_000;

  /** How long Redis stays paused for the tests that wait for a single round of timeouts. */
  private static final long SHORT_PAUSE_MILLIS = 5_000;

  /** The product whose price the tests look up. */
  private static final String SKU = "sku-1";

  /** The shortest a call that waits for one command timeout takes, less a margin for the clock. */
  private static final Duration ONE_TIMEOUT_MIN = Duration.ofMillis(800);

  /** The longest a one-timeout call may take before it counts as having waited for two. */
  private static final Duration ONE_TIMEOUT_MAX = Duration.ofMillis(1_600);

  /** The longest a call may take on a slow test machine. */
  private static final Duration SLOW_MACHINE_MAX = Duration.ofMillis(3_000);

  /** The longest all the calls made while Redis is skipped may take together. */
  private static final Duration SKIPPED_CALLS_MAX = Duration.ofSeconds(1);

  /** The shortest wait before one call tries Redis again: the 5 s retry interval, less a margin. */
  private static final Duration RETRY_MIN = Duration.ofMillis(4_500);

  /** How long the tests wait for Redis to be in use again after a pause. */
  private static final Duration RECOVERY_WAIT = Duration.ofSeconds(30);

  @Autowired private ProductCatalog catalog;

  @Autowired private MeterRegistry meterRegistry;

  @Autowired private StringRedisTemplate redis;

  @Autowired private GenericContainer<?> redisContainer;

  @Autowired private RedisRetryGate gate;

  /** Waits until the caches use Redis again, then empties it, so a test's calls all miss. */
  @BeforeEach
  void startFromEmptyRedis() {
    PausedRedisSteps.startFromEmptyRedis(catalog, gate, redis);
  }

  @Nested
  @DisplayName("with every Redis command paused")
  class FullPause {

    @Test
    @DisplayName(
        "a sync=false call waits for one timeout, not two; the next calls skip Redis; one call"
            + " retries after the interval; one warning per cache")
    void syncFalseWaitsOnceThenSkips() throws Exception {
      var capture = new LogLineCapture(StratumCacheErrorHandler.class.getName());
      capture.start();
      try {
        PausedRedisSteps.pauseRedis(redisContainer, PAUSE_MILLIS);
        var start = System.nanoTime();
        var first = PausedRedisSteps.durationOf(() -> catalog.priceOf(SKU), "price-of-" + SKU);
        var skipped = durationOfCalls(1, () -> catalog.priceOf(SKU), "price-of-" + SKU);

        assertThat(first).isBetween(ONE_TIMEOUT_MIN, ONE_TIMEOUT_MAX);
        assertThat(skipped).isLessThan(SKIPPED_CALLS_MAX);
        assertThat(failuresOf(ProductCatalog.PRODUCTS, CacheConstants.OPERATION_GET)).isOne();
        assertThat(
                PausedRedisSteps.failureCounter(
                    meterRegistry, ProductCatalog.PRODUCTS, CacheConstants.OPERATION_PUT))
            .isNull();

        await().atMost(RECOVERY_WAIT).until(this::secondFailureAfterOneCall);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThan(RETRY_MIN);
      } finally {
        unpauseRedis();
        capture.stop();
      }
      assertThat(capture.onlyLine().getFormattedMessage())
          .contains("SX-CACHE-1002")
          .contains(ProductCatalog.PRODUCTS);
    }

    @Test
    @DisplayName(
        "concurrent sync=true calls: only those already waiting on Redis wait for the timeout,"
            + " the rest skip Redis")
    void syncTrueOnlyFirstBurstWaits() throws Exception {
      final var failuresBefore =
          PausedRedisSteps.failuresSoFar(
              meterRegistry, ProductCatalog.SYNCED_PRODUCTS, CacheConstants.OPERATION_GET);
      List<Duration> durations;
      PausedRedisSteps.pauseRedis(redisContainer, SHORT_PAUSE_MILLIS);
      try {
        durations =
            durationsOf(THREADS, () -> catalog.syncedPriceOf(SKU), "synced-price-of-" + SKU);
      } finally {
        unpauseRedis();
      }

      assertThat(durations)
          .allSatisfy(duration -> assertThat(duration).isLessThan(SLOW_MACHINE_MAX));
      assertThat(
              PausedRedisSteps.failuresSoFar(
                  meterRegistry, ProductCatalog.SYNCED_PRODUCTS, CacheConstants.OPERATION_GET))
          .isBetween(failuresBefore + 1, failuresBefore + THREADS);
    }

    /** Makes one call and returns whether a second failed read was counted. */
    private boolean secondFailureAfterOneCall() {
      catalog.priceOf(SKU);
      return failuresOf(ProductCatalog.PRODUCTS, CacheConstants.OPERATION_GET) >= 2;
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
      PausedRedisSteps.pauseRedis(redisContainer, SHORT_PAUSE_MILLIS, "WRITE");
      try {
        var lookupsBefore = catalog.syncedLookups();
        var failuresBefore =
            PausedRedisSteps.failuresSoFar(
                meterRegistry, ProductCatalog.WRITE_TEST_PRODUCTS, CacheConstants.OPERATION_PUT);

        var duration =
            PausedRedisSteps.durationOf(
                () -> catalog.writeTestPriceOf(SKU), "synced-price-of-" + SKU);

        assertThat(catalog.syncedLookups()).isEqualTo(lookupsBefore + 1);
        assertThat(
                PausedRedisSteps.failuresSoFar(
                    meterRegistry,
                    ProductCatalog.WRITE_TEST_PRODUCTS,
                    CacheConstants.OPERATION_PUT))
            .isEqualTo(failuresBefore + 1);
        // The write after the load is synchronous, so the call waits out one command timeout.
        assertThat(duration).isBetween(ONE_TIMEOUT_MIN, SLOW_MACHINE_MAX);
      } finally {
        unpauseRedis();
      }
    }
  }

  /**
   * Runs the call {@value #CALLS} times over the given number of threads and returns how long each
   * call took. Every call must return the method's real value.
   */
  private List<Duration> durationsOf(int threads, Supplier<String> call, String expectedValue)
      throws InterruptedException, ExecutionException {
    List<Callable<Duration>> calls =
        IntStream.range(0, CALLS)
            .<Callable<Duration>>mapToObj(
                index -> () -> PausedRedisSteps.durationOf(call, expectedValue))
            .toList();
    try (var executor = Executors.newFixedThreadPool(threads)) {
      List<Duration> durations = new ArrayList<>();
      // A loop rather than a stream: Future.get throws checked exceptions.
      for (var future : executor.invokeAll(calls)) {
        durations.add(future.get());
      }
      return durations;
    }
  }

  /** Runs the call {@value #CALLS} times and returns how long they took together. */
  private Duration durationOfCalls(int threads, Supplier<String> call, String expectedValue)
      throws InterruptedException, ExecutionException {
    return durationsOf(threads, call, expectedValue).stream().reduce(Duration.ZERO, Duration::plus);
  }

  /** Returns how many failures of one operation on one cache were counted. */
  private double failuresOf(String cache, String operation) {
    return meterRegistry
        .get(CacheConstants.METRIC_FAILURES)
        .tags(CacheConstants.TAG_CACHE, cache, CacheConstants.TAG_OPERATION, operation)
        .counter()
        .count();
  }

  /** Lets Redis answer again; always runs, so a failed test never leaves a paused Redis behind. */
  private void unpauseRedis() throws IOException, InterruptedException {
    redisContainer.execInContainer("redis-cli", "CLIENT", "UNPAUSE");
  }
}
