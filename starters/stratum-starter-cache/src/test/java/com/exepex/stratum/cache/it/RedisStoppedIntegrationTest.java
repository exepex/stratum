package com.exepex.stratum.cache.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.cache.CacheConstants;
import com.exepex.stratum.cache.RedisRetryGate;
import com.exepex.stratum.cache.it.products.ProductsApplication;
import com.exepex.stratum.cache.testsupport.ProductCatalog;
import com.exepex.stratum.cache.testsupport.RedisOutageProxy;
import com.exepex.stratum.test.LogLineCapture;
import com.exepex.stratum.test.RedisContainerConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.GenericContainer;

/**
 * Cuts the connection to Redis after the service connected, through a proxy that keeps its port,
 * and checks that cached calls fail fast, skip Redis, are counted and warned once, and use Redis
 * again once it is back. Both of Lettuce's connection-loss errors are seen: the one for a command
 * in flight when the link drops, and the one for a command refused while it is down.
 */
@SpringBootTest(
    classes = ProductsApplication.class,
    properties = {
      "spring.application.name=stopped-service",
      CacheConstants.REDIS_RETRY_INTERVAL + "=1s",
      // The cut is checked with one in-flight command; RedisRetryGateTest covers the count.
      CacheConstants.REDIS_FAILURE_THRESHOLD + "=1",
      CacheConstants.WARNING_INTERVAL + "=1h",
      // Long enough that the command in flight fails because the link drops, not by timing out.
      CacheConstants.REDIS_TIMEOUT + "=5s"
    })
@Import({RedisContainerConfiguration.class, RedisStoppedIntegrationTest.ProxiedRedis.class})
@DisplayName(
    "With the connection to Redis cut, cached calls fail fast and skip Redis until it is back")
class RedisStoppedIntegrationTest {

  /** How long the in-flight command is held by a paused Redis, in milliseconds. */
  private static final String PAUSE_MILLIS = "2000";

  /** How long the test checks that the paused command is still waiting. */
  private static final Duration STILL_WAITING = Duration.ofMillis(300);

  /** How long Lettuce may take to reconnect once Redis is back. */
  private static final Duration RECONNECT_WAIT = Duration.ofSeconds(45);

  /** How many calls the fail-fast check makes while Redis is skipped. */
  private static final int FAST_CALLS = 50;

  /** The longest those calls may take together while Redis is skipped. */
  private static final Duration FAST_CALLS_MAX = Duration.ofSeconds(1);

  @Autowired private ProductCatalog catalog;

  @Autowired private RedisOutageProxy proxy;

  @Autowired private MeterRegistry meterRegistry;

  @Autowired private GenericContainer<?> redisContainer;

  @Test
  @DisplayName(
      "both connection-loss errors skip Redis and are counted, the gauge reads 0, one SX-CACHE-1006"
          + " is logged, and the gauge reads 1 once Redis is back")
  void cutConnectionSkipsRedisUntilBack() throws Exception {
    var warnings = new LogLineCapture(RedisRetryGate.class.getName());
    warnings.start();
    try {
      assertThat(catalog.priceOf("sku-1")).isEqualTo("price-of-sku-1");

      // A command in flight when the link drops fails with "Connection disconnected".
      redisContainer.execInContainer("redis-cli", "CLIENT", "PAUSE", PAUSE_MILLIS);
      var inFlight = CompletableFuture.supplyAsync(() -> catalog.syncedPriceOf("sku-2"));
      await()
          .during(STILL_WAITING)
          .atMost(STILL_WAITING.multipliedBy(2))
          .until(() -> !inFlight.isDone());
      proxy.cut();

      assertThat(inFlight.get()).isEqualTo("synced-price-of-sku-2");
      assertThat(connectFailures()).isOne();
      assertThat(available()).isZero();

      // After the retry interval, one call is refused with "Currently not connected".
      await().atMost(RECONNECT_WAIT).until(() -> connectFailuresAfterOneCall() == 2);
      assertThat(available()).isZero();
      assertThat(durationOfFastCalls()).isLessThan(FAST_CALLS_MAX);
      assertThat(warnings.onlyLine().getFormattedMessage()).startsWith("SX-CACHE-1006");

      proxy.restore();
      await().atMost(RECONNECT_WAIT).until(() -> availableAfterOneCall() == 1);
    } finally {
      warnings.stop();
      proxy.restore();
    }
  }

  /** Makes calls while Redis is skipped and returns how long they took together. */
  private Duration durationOfFastCalls() {
    var start = System.nanoTime();
    IntStream.range(0, FAST_CALLS).forEach(index -> catalog.priceOf("fast-" + index));
    return Duration.ofNanos(System.nanoTime() - start);
  }

  /** Makes one cached call and returns the connect failures counted so far. */
  private double connectFailuresAfterOneCall() {
    catalog.priceOf("sku-3");
    return connectFailures();
  }

  /** Makes one cached call and returns the gauge. */
  private double availableAfterOneCall() {
    catalog.priceOf("sku-4");
    return available();
  }

  /** Returns how many times Redis could not be reached. */
  private double connectFailures() {
    return meterRegistry.get(CacheConstants.METRIC_REDIS_CONNECT_FAILURES).counter().count();
  }

  /** Returns the gauge that tells whether Redis caches use Redis. */
  private double available() {
    return meterRegistry.get(CacheConstants.METRIC_REDIS_AVAILABLE).gauge().value();
  }

  /** Points the service at Redis through the proxy, which the test cuts and restores. */
  @TestConfiguration(proxyBeanMethods = false)
  static class ProxiedRedis {

    /** The port Redis listens on inside the container. */
    private static final int REDIS_PORT = 6379;

    /**
     * Starts the proxy in front of the Redis container.
     *
     * @param redisContainer the started Redis container
     * @return the proxy
     * @throws IOException if the proxy cannot open a port
     */
    @Bean
    RedisOutageProxy redisOutageProxy(GenericContainer<?> redisContainer) throws IOException {
      return new RedisOutageProxy(
          redisContainer.getHost(), redisContainer.getMappedPort(REDIS_PORT));
    }

    /**
     * Gives Spring Boot the proxy's address in place of the container's.
     *
     * @param proxy the proxy
     * @return the connection details
     */
    @Bean
    @Primary
    DataRedisConnectionDetails proxiedRedisConnection(RedisOutageProxy proxy) {
      var standalone = DataRedisConnectionDetails.Standalone.of("localhost", proxy.port());
      return new DataRedisConnectionDetails() {
        @Override
        public Standalone getStandalone() {
          return standalone;
        }
      };
    }
  }
}
