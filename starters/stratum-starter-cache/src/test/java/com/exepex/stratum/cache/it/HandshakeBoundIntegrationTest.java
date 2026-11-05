package com.exepex.stratum.cache.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatException;

import com.exepex.stratum.cache.CacheConstants;
import com.exepex.stratum.cache.it.products.ProductsApplication;
import com.exepex.stratum.cache.testsupport.MutePort;
import java.time.Duration;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Starts the service against a Redis host that takes the connect but never answers, and checks the
 * cost the docs state: the largest per-cache command timeout also bounds the connect handshake, for
 * every Redis use of the service.
 */
@SpringBootTest(
    classes = ProductsApplication.class,
    properties = {
      "spring.application.name=handshake-service",
      CacheConstants.REDIS_TIMEOUT + "=1s",
      CacheConstants.REDIS_CONNECT_TIMEOUT + "=2s"
    })
@DisplayName("The largest cache command timeout bounds every Redis connect handshake")
class HandshakeBoundIntegrationTest {

  /** The longest a connect may fail after with the 1 s global timeout, with a margin. */
  private static final Duration GLOBAL_BOUND_MAX = Duration.ofMillis(2_500);

  /** The shortest a connect may fail after with one cache at 5 s, less a margin. */
  private static final Duration CACHE_BOUND_MIN = Duration.ofMillis(4_500);

  /** The host that takes the connect and never answers. */
  @AutoClose private static final MutePort MUTE_REDIS = new MutePort();

  @Autowired private RedisConnectionFactory connectionFactory;

  /**
   * Points the service at the mute host.
   *
   * @param registry the service's properties
   */
  @DynamicPropertySource
  static void muteRedis(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", MUTE_REDIS::host);
    registry.add("spring.data.redis.port", MUTE_REDIS::port);
  }

  /** Returns how long a first connect took to fail. */
  private Duration failedConnect() {
    var start = System.nanoTime();
    assertThatException().isThrownBy(() -> connectionFactory.getConnection().ping());
    return Duration.ofNanos(System.nanoTime() - start);
  }

  @Nested
  @DisplayName("with no cache timeout")
  class WithoutCacheTimeout {

    @Test
    @DisplayName("a connect fails within about the 1 s global timeout")
    void globalBoundsHandshake() {
      assertThat(failedConnect()).isLessThan(GLOBAL_BOUND_MAX);
    }
  }

  @Nested
  @DisplayName("with one cache at 5 s")
  @TestPropertySource(properties = CacheConstants.CACHES + ".reports.command-timeout=5s")
  class WithCacheTimeout {

    @Test
    @DisplayName("a connect waits about 5 s")
    void largestCacheTimeoutBoundsHandshake() {
      assertThat(failedConnect()).isGreaterThanOrEqualTo(CACHE_BOUND_MIN);
    }
  }
}
