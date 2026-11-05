package com.exepex.stratum.cache.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.cache.CacheConstants;
import com.exepex.stratum.cache.RedisRetryGate;
import com.exepex.stratum.cache.it.products.ProductsApplication;
import com.exepex.stratum.cache.testsupport.PausedRedisSteps;
import com.exepex.stratum.cache.testsupport.ProductCatalog;
import com.exepex.stratum.test.RedisContainerConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

/**
 * Gives two caches their own settings on a real Redis: {@value ProductCatalog#REPORTS} a 30 s time
 * to live and a 3 s command timeout, {@value ProductCatalog#QUOTES} a 200 ms command timeout. The
 * global {@code spring.data.redis.timeout} stays 1 s. Redis is paused for 2.5 s, so each cache's
 * commands time out at their own value, or answer once the pause ends.
 */
@SpringBootTest(
    classes = ProductsApplication.class,
    properties = {
      "spring.application.name=" + PerCacheSettingsIntegrationTest.SERVICE,
      CacheConstants.WARNING_INTERVAL + "=1h",
      CacheConstants.CACHES + ".reports.time-to-live=30s",
      CacheConstants.CACHES + ".reports.command-timeout=3s",
      CacheConstants.CACHES + ".quotes.command-timeout=200ms"
    })
@Import(RedisContainerConfiguration.class)
@DisplayName("Single Redis caches keep their own time to live and command timeout")
class PerCacheSettingsIntegrationTest {

  /** The service's name, which its Redis cache keys start with. */
  static final String SERVICE = "per-cache-service";

  /** The time to live of the reports cache, in seconds, as the properties above set it. */
  private static final long REPORTS_TIME_TO_LIVE_SECONDS = 30;

  /** Stratum's time to live for every other cache, in seconds. */
  private static final long DEFAULT_TIME_TO_LIVE_SECONDS = 600;

  /** How much of a time to live may pass between the write and the check, in seconds. */
  private static final long TIME_TO_LIVE_MARGIN_SECONDS = 10;

  /** The product the tests look up. */
  private static final String SKU = "sku-1";

  /** How long Redis stays paused: longer than 1 s, shorter than the 3 s of the reports cache. */
  private static final long PAUSE_MILLIS = 2_500;

  /** The longest a 200 ms timeout may take on a slow machine. */
  private static final Duration SHORT_TIMEOUT_MAX = Duration.ofMillis(800);

  /** The shortest a call that waits for the 1 s global timeout takes, less a margin. */
  private static final Duration GLOBAL_TIMEOUT_MIN = Duration.ofMillis(800);

  /** The longest a call that waits for the 1 s global timeout may take. */
  private static final Duration GLOBAL_TIMEOUT_MAX = Duration.ofMillis(1_800);

  /** How long the tests wait for background writes, and for Redis to be in use again. */
  private static final Duration WAIT = Duration.ofSeconds(30);

  @Autowired private ProductCatalog catalog;

  @Autowired private StringRedisTemplate redis;

  @Autowired private MeterRegistry meterRegistry;

  @Autowired private GenericContainer<?> redisContainer;

  @Autowired private RedisRetryGate gate;

  /** Waits until the caches use Redis again, then empties it. */
  @BeforeEach
  void startFromEmptyRedis() {
    PausedRedisSteps.startFromEmptyRedis(catalog, gate, redis);
  }

  @Nested
  @DisplayName("time to live")
  class TimeToLive {

    @Test
    @DisplayName("each cache's entries expire after its own time to live, the others after 10 min")
    void eachCacheHasItsOwnTimeToLive() {
      catalog.reportOf(SKU);
      catalog.priceOf(SKU);

      var reportsKey = SERVICE + ":" + ProductCatalog.REPORTS + "::" + SKU;
      var productsKey = SERVICE + ":" + ProductCatalog.PRODUCTS + "::" + SKU;
      await().atMost(WAIT).until(() -> redis.hasKey(reportsKey) && redis.hasKey(productsKey));

      assertThat(redis.getExpire(reportsKey))
          .isBetween(
              REPORTS_TIME_TO_LIVE_SECONDS - TIME_TO_LIVE_MARGIN_SECONDS,
              REPORTS_TIME_TO_LIVE_SECONDS);
      assertThat(redis.getExpire(productsKey))
          .isBetween(
              DEFAULT_TIME_TO_LIVE_SECONDS - TIME_TO_LIVE_MARGIN_SECONDS,
              DEFAULT_TIME_TO_LIVE_SECONDS);
    }
  }

  @Nested
  @DisplayName("command timeout")
  class CommandTimeout {

    @Test
    @DisplayName(
        "with Redis paused, a 200 ms cache times out near 200 ms without making the other caches"
            + " skip Redis, and a 3 s cache waits for Redis and reads its entry")
    void shortAndLongCacheTimeouts() throws Exception {
      catalog.reportOf(SKU);
      var reportsKey = SERVICE + ":" + ProductCatalog.REPORTS + "::" + SKU;
      await().atMost(WAIT).until(() -> redis.hasKey(reportsKey));
      final var reportLookupFailures = failures(ProductCatalog.REPORTS);

      pauseRedis();
      var quote = PausedRedisSteps.durationOf(() -> catalog.quoteOf(SKU), "quote-of-" + SKU);
      var gateOpenAfterQuote = !gate.isAvailable();
      var report = PausedRedisSteps.durationOf(() -> catalog.reportOf(SKU), "report-of-" + SKU);

      assertThat(quote).isLessThan(SHORT_TIMEOUT_MAX);
      assertThat(gateOpenAfterQuote).as("the quotes timeout made every cache skip Redis").isFalse();
      assertThat(failures(ProductCatalog.QUOTES)).isOne();
      assertThat(report).isGreaterThan(Duration.ofMillis(PAUSE_MILLIS / 2));
      assertThat(failures(ProductCatalog.REPORTS)).isEqualTo(reportLookupFailures);
    }

    @Test
    @DisplayName(
        "with Redis paused, a cache without its own timeout and the service's own Redis calls time"
            + " out near the 1 s global timeout")
    void otherCommandsKeepGlobalTimeout() throws Exception {
      pauseRedis();
      var start = System.nanoTime();
      assertThatExceptionOfType(QueryTimeoutException.class).isThrownBy(() -> redis.hasKey(SKU));
      var template = Duration.ofNanos(System.nanoTime() - start);
      var product = PausedRedisSteps.durationOf(() -> catalog.priceOf(SKU), "price-of-" + SKU);

      assertThat(template).isBetween(GLOBAL_TIMEOUT_MIN, GLOBAL_TIMEOUT_MAX);
      assertThat(product).isBetween(GLOBAL_TIMEOUT_MIN, GLOBAL_TIMEOUT_MAX);
    }
  }

  /** Pauses every Redis command; the pause lifts itself, so no test leaves Redis paused. */
  private void pauseRedis() throws IOException, InterruptedException {
    PausedRedisSteps.pauseRedis(redisContainer, PAUSE_MILLIS);
  }

  /** Returns how many failed reads of one cache were counted, zero when none. */
  private double failures(String cache) {
    return PausedRedisSteps.failuresSoFar(meterRegistry, cache, CacheConstants.OPERATION_GET);
  }
}
