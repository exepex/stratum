package com.exepex.stratum.cache.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.cache.CacheConstants;
import com.exepex.stratum.cache.it.products.ProductsApplication;
import com.exepex.stratum.cache.testsupport.ProductCatalog;
import com.exepex.stratum.cache.testsupport.SilentPort;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Starts the service with Redis unreachable from the start, at a port that never answers, and
 * checks that concurrent first calls do not queue behind each other's connect: only one call waits
 * for the 2 s connect timeout, the others skip Redis.
 */
@SpringBootTest(
    classes = ProductsApplication.class,
    properties = "spring.application.name=unreachable-service")
@DisplayName("With Redis unreachable from the start, concurrent first calls do not queue")
class RedisUnreachableIntegrationTest {

  /** How many calls run at the same time. */
  private static final int CALLS = 8;

  /** The longest one call may take: the 2 s connect timeout plus a margin for a slow machine. */
  private static final Duration CALL_MAX = Duration.ofMillis(3_500);

  /** The port Redis is looked for at, where each connect times out. */
  @AutoClose private static final SilentPort SILENT_REDIS = new SilentPort();

  @Autowired private ProductCatalog catalog;

  @Autowired private MeterRegistry meterRegistry;

  /**
   * Points the service at the silent port.
   *
   * @param registry the service's properties
   */
  @DynamicPropertySource
  static void silentRedis(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", SILENT_REDIS::host);
    registry.add("spring.data.redis.port", SILENT_REDIS::port);
  }

  @Test
  @DisplayName(
      "8 concurrent calls each return the method's value within the connect timeout, and one"
          + " connect failure is counted")
  void concurrentFirstCallsDoNotQueue() throws Exception {
    List<Callable<Duration>> calls =
        IntStream.range(0, CALLS).<Callable<Duration>>mapToObj(this::timedCall).toList();

    List<Future<Duration>> durations;
    try (var executor = Executors.newFixedThreadPool(CALLS)) {
      durations = executor.invokeAll(calls);
    }

    // A loop rather than a stream: Future.get throws checked exceptions.
    for (var duration : durations) {
      assertThat(duration.get()).isLessThan(CALL_MAX);
    }
    assertThat(meterRegistry.get(CacheConstants.METRIC_REDIS_CONNECT_FAILURES).counter().count())
        .isOne();
  }

  /** Returns a call that checks the method's value and measures how long it took. */
  private Callable<Duration> timedCall(int index) {
    return () -> timed("sku-" + index);
  }

  /** Calls the cached method, checks its value and returns how long it took. */
  private Duration timed(String sku) {
    var start = System.nanoTime();
    assertThat(catalog.priceOf(sku)).isEqualTo("price-of-" + sku);
    return Duration.ofNanos(System.nanoTime() - start);
  }
}
