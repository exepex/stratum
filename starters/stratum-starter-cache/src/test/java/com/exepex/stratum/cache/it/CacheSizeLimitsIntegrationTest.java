package com.exepex.stratum.cache.it;

import static com.exepex.stratum.cache.testsupport.HashedKeys.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.cache.CacheConstants;
import com.exepex.stratum.cache.it.products.ProductsApplication;
import com.exepex.stratum.cache.testsupport.HashedKeys;
import com.exepex.stratum.cache.testsupport.ReportCatalog;
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
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

@SpringBootTest(
    classes = ProductsApplication.class,
    properties = "spring.application.name=" + CacheSizeLimitsIntegrationTest.SERVICE)
@Import({RedisContainerConfiguration.class, ReportCatalog.class})
@DisplayName("A service on Redis stores long keys hashed and never stores a value over its limit")
class CacheSizeLimitsIntegrationTest {

  /** The service's name, which its Redis cache keys start with. */
  static final String SERVICE = "reports-service";

  /** The prefix of every key of the reports cache. */
  private static final String PREFIX = SERVICE + ":" + ReportCatalog.REPORTS + "::";

  /** A report id of 10 MB, far over the 1 KiB key limit. */
  private static final String HUGE_ID = "i".repeat(10 * 1024 * 1024);

  /** The id of the report over the value limit. */
  private static final String LARGE_ID = "large";

  /** The id of the report that is replaced. */
  private static final String WEEKLY_ID = "weekly";

  /** The length of a small report. */
  private static final int SMALL = 10;

  /** The length of a report of 2 MB, over the 1 MiB value limit. */
  private static final int LARGE = 2 * 1024 * 1024;

  /** The most bytes Redis may use for the entry under the hashed key. */
  private static final long MAX_HASHED_ENTRY_BYTES = 200;

  /** How long a test waits for Spring Data Redis to write or delete in the background. */
  private static final Duration BACKGROUND_WAIT = Duration.ofSeconds(5);

  @Autowired private ReportCatalog catalog;

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
  @DisplayName("a key over 1 KiB")
  class LongKeys {

    @Test
    @DisplayName("a 10 MB key is stored under its hash in about 100 bytes, and still hits")
    void storesHugeKeyHashed() throws IOException, InterruptedException {
      catalog.reportOf(HUGE_ID, SMALL);
      var hashedKey = PREFIX + "sha256:" + HashedKeys.sha256Hex(bytes(PREFIX + HUGE_ID));
      awaitKey(hashedKey, true);
      var builds = catalog.builds();

      catalog.reportOf(HUGE_ID, SMALL);

      assertThat(catalog.builds()).isEqualTo(builds);
      assertThat(memoryUsage(hashedKey)).isLessThan(MAX_HASHED_ENTRY_BYTES);
      assertThat(skips(ReportCatalog.REPORTS, CacheConstants.REASON_KEY_HASHED))
          .isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("clearing every entry also removes a hashed key")
    void clearsHashedKey() {
      catalog.reportOf(HUGE_ID, SMALL);
      var hashedKey = PREFIX + "sha256:" + HashedKeys.sha256Hex(bytes(PREFIX + HUGE_ID));
      awaitKey(hashedKey, true);

      catalog.forgetAllReports();

      awaitKey(hashedKey, false);
      assertThat(redis.hasKey(hashedKey)).isFalse();
    }
  }

  @Nested
  @DisplayName("a value over 1 MiB")
  class LargeValues {

    @Test
    @DisplayName("is never stored, so the method runs each time, and each skip is counted")
    void neverStoresLargeValue() {
      var builds = catalog.builds();

      catalog.reportOf(LARGE_ID, LARGE);
      catalog.reportOf(LARGE_ID, LARGE);

      assertThat(catalog.builds()).isEqualTo(builds + 2);
      assertThat(redis.hasKey(PREFIX + LARGE_ID)).isFalse();
      assertThat(skips(ReportCatalog.REPORTS, CacheConstants.REASON_VALUE_TOO_LARGE))
          .isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("loaded with sync=true, it is returned and nothing fails or is reported")
    void returnsLargeSyncedValue() {
      var report = catalog.syncedReportOf(LARGE_ID, LARGE);

      assertThat(report).hasSize(LARGE);
      assertThat(redis.hasKey(SERVICE + ":" + ReportCatalog.SYNCED_REPORTS + "::" + LARGE_ID))
          .isFalse();
      assertThat(meterRegistry.find(CacheConstants.METRIC_FAILURES).counters()).isEmpty();
    }

    @Test
    @DisplayName("a replacement too large removes the older entry, so no stale report is read")
    void removesOlderEntry() {
      catalog.rebuildReport(WEEKLY_ID, SMALL);
      awaitKey(PREFIX + WEEKLY_ID, true);

      catalog.rebuildReport(WEEKLY_ID, LARGE);

      awaitKey(PREFIX + WEEKLY_ID, false);
      assertThat(catalog.reportOf(WEEKLY_ID, SMALL)).hasSize(SMALL);
    }
  }

  /** Waits until Redis holds the key or no longer does, as Spring Data Redis writes later. */
  private void awaitKey(String key, boolean present) {
    await().atMost(BACKGROUND_WAIT).until(() -> present == Boolean.TRUE.equals(redis.hasKey(key)));
  }

  /** Returns how many bytes Redis uses for the key and its value, as redis-cli prints it. */
  private long memoryUsage(String key) throws IOException, InterruptedException {
    return Long.parseLong(
        redisContainer.execInContainer("redis-cli", "MEMORY", "USAGE", key).getStdout().strip());
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
