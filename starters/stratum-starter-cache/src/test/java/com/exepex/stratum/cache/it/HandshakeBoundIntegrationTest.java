package com.exepex.stratum.cache.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatException;

import com.exepex.stratum.cache.CacheConstants;
import com.exepex.stratum.cache.autoconfigure.RedisCacheWriterAutoConfiguration;
import com.exepex.stratum.cache.testsupport.MutePort;
import java.time.Duration;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Connects to a Redis host that takes the connect but never answers, and checks the cost the docs
 * state: the largest per-cache command timeout also bounds the connect handshake, for every Redis
 * use of the service.
 */
@DisplayName("The largest cache command timeout bounds every Redis connect handshake")
class HandshakeBoundIntegrationTest {

  /** The longest a connect may fail after with the 1 s global timeout, with a margin. */
  private static final Duration GLOBAL_BOUND_MAX = Duration.ofMillis(2_500);

  /** The shortest a connect may fail after with one cache at 5 s, less a margin. */
  private static final Duration CACHE_BOUND_MIN = Duration.ofMillis(4_500);

  /** The host that takes the connect and never answers. */
  @AutoClose private final MutePort muteRedis = new MutePort();

  /** Spring Boot's Redis set-up with Stratum's, pointed at the mute host. */
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withPropertyValues(
              CacheConstants.REDIS_TIMEOUT + "=1s",
              CacheConstants.REDIS_CONNECT_TIMEOUT + "=2s",
              "spring.data.redis.host=" + muteRedis.host(),
              "spring.data.redis.port=" + muteRedis.port())
          .withConfiguration(
              AutoConfigurations.of(
                  DataRedisAutoConfiguration.class, RedisCacheWriterAutoConfiguration.class));

  /** Returns how long a first connect took to fail. */
  private Duration failedConnect(RedisConnectionFactory connectionFactory) {
    var start = System.nanoTime();
    assertThatException().isThrownBy(() -> connectionFactory.getConnection().ping());
    return Duration.ofNanos(System.nanoTime() - start);
  }

  @Test
  @DisplayName("with no cache timeout, a connect fails within about the 1 s global timeout")
  void globalBoundsHandshake() {
    contextRunner.run(
        context ->
            assertThat(failedConnect(context.getBean(RedisConnectionFactory.class)))
                .isLessThan(GLOBAL_BOUND_MAX));
  }

  @Test
  @DisplayName("with one cache at 5 s, a connect waits about 5 s")
  void largestCacheTimeoutBoundsHandshake() {
    contextRunner
        .withPropertyValues(CacheConstants.CACHES + ".reports.command-timeout=5s")
        .run(
            context ->
                assertThat(failedConnect(context.getBean(RedisConnectionFactory.class)))
                    .isGreaterThanOrEqualTo(CACHE_BOUND_MIN));
  }
}
